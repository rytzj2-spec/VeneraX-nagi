package io.github.kyosee.venera.suite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 屏幕共享「经服务器」：WebRTC。手机是房主，服务器（小站那台，/share/ws，见 server/nagi_share/）只牵线：
 * 能直连就手机 ↔ 观看者直连（不花服务器流量）；连不上走 TURN（Cloudflare 或自建 coturn，服务器下发临时凭据）。
 * 视频：屏幕 → 硬件 H.264（观看页把 H.264 排第一）；声音：Android 10+ 录「手机正在播放的声音」。
 *
 * WebRTC 的声音只能从「录音」来（JavaAudioDeviceModule 开麦克风），所以麦克风会打开（系统会显示麦克风绿点），
 * 但录到的每一段都在 AudioRecordDataCallback 里整段换成播放声音（没有就静音），麦克风的声音不会发出去。
 */
object SuiteRtcShare {
    /** 不写死（仓库是公开的）：第一次自己填，之后记住；「一键配置」导出时带上（见 SuiteConfigPack 的 shareServer） */
    const val DEFAULT_SERVER = ""
    private const val PREFS = "nagi_share"

    @Volatile internal var service: SuiteRtcShareService? = null
    val running: Boolean get() = service != null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun server(ctx: Context) = prefs(ctx).getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
    fun password(ctx: Context) = prefs(ctx).getString("password", "") ?: ""
    fun quality(ctx: Context) = prefs(ctx).getInt("quality", 1)          // 0 清晰 / 1 标准 / 2 省流
    fun save(ctx: Context, server: String, password: String, quality: Int) =
        prefs(ctx).edit().putString("server", server).putString("password", password).putInt("quality", quality).apply()

    val QUALITY_LABELS = listOf("清晰（约 3 Mbps）", "标准（约 2 Mbps）", "省流（约 1 Mbps）")
    fun bitrate(q: Int) = when (q) { 0 -> 3_000_000; 2 -> 1_000_000; else -> 2_000_000 }

    /** 观看链接：服务器地址 + #r=房间号（有密码就带上） */
    fun inviteLink(): String? {
        val s = service ?: return null
        val room = s.room ?: return null
        val base = s.serverBase.let { if (it.endsWith("/")) it else "$it/" }
        return base + "#r=" + room + if (s.password.isNotEmpty()) "&p=" + android.net.Uri.encode(s.password) else ""
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, SuiteRtcShareService::class.java).setAction(SuiteRtcShareService.ACTION_STOP))
    }
}

class SuiteRtcShareService : Service() {
    companion object {
        const val ACTION_STOP = "nagisaland.rtcshare.STOP"
        private const val TAG = "nagi-rtc"
        private const val NOTIFY_ID = 0x5352
        private const val MAX_SIDE = 1280
        private const val FPS = 30
        private const val RATE = 48000
        @Volatile private var factoryInitialized = false
    }

    // 给界面看的状态
    @Volatile var room: String? = null
    @Volatile var error: String? = null
    @Volatile var serverBase: String = SuiteRtcShare.DEFAULT_SERVER
    @Volatile var password: String = ""
    @Volatile var audioOn = false
    val viewers: Int get() = peers.size

    private val exec = Executors.newSingleThreadExecutor()    // 所有状态都在这一个线程上改
    private val main = Handler(Looper.getMainLooper())
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var adm: JavaAudioDeviceModule? = null
    private var playback: AudioRecord? = null
    private var ws: WebSocket? = null
    private var http: OkHttpClient? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private val peers = HashMap<String, PeerConnection>()
    private var bitrate = 2_000_000
    @Volatile private var stopped = false
    private var displayListener: DisplayManager.DisplayListener? = null
    private var capW = 0
    private var capH = 0

    // 播放声音的环形缓冲（AudioPlaybackCapture 写，ADM 的录音回调读）
    private val ring = ByteArray(RATE * 2 * 2)          // 1 秒
    private var ringW = 0
    private var ringR = 0
    private var ringN = 0
    private val ringLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent == null || factory != null) return START_NOT_STICKY
        val mic = Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        goForeground(mic)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        serverBase = intent.getStringExtra("server") ?: SuiteRtcShare.DEFAULT_SERVER
        password = intent.getStringExtra("password").orEmpty()
        bitrate = SuiteRtcShare.bitrate(intent.getIntExtra("quality", 1))
        SuiteRtcShare.service = this
        exec.execute {
            try {
                startMedia(data, mic)
                connectSignaling()
            } catch (e: Throwable) {
                fail("启动失败：${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        return START_NOT_STICKY
    }

    private fun fail(msg: String, e: Throwable? = null) {
        Log.w(TAG, msg, e)
        error = msg
        if (e != null) SuitePerf.error(this, "屏幕共享（经服务器）：$msg", e)
        main.post {
            Toast.makeText(this, "屏幕共享：$msg", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun notification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("nagi_share", "屏幕共享", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "nagi_share")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stop = PendingIntent.getService(this, 3, Intent(this, SuiteRtcShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 4, Intent(this, SuiteShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        return b.setSmallIcon(applicationInfo.icon)
            .setContentTitle("Nagisaland 正在共享屏幕（经服务器）")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "停止共享", stop).build())
            .build()
    }

    private fun goForeground(mic: Boolean) {
        val n = notification("连接服务器…")
        if (Build.VERSION.SDK_INT >= 29) {
            // mediaProjection = 0x20，microphone = 0x80（WebRTC 的声音要开录音；没有录音权限就只要画面）
            startForeground(NOTIFY_ID, n, 0x20 or if (mic) 0x80 else 0)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun updateNotification() = main.post {
        if (stopped) return@post
        val text = if (room == null) "连接服务器…" else "房间 $room · ${peers.size} 人在看 · 点这里看链接"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_ID, notification(text))
    }

    // ---------------------------------------------------------------- 媒体

    private fun captureSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val scale = min(1f, MAX_SIDE.toFloat() / max(dm.widthPixels, dm.heightPixels))
        // 编码器要求偶数（很多硬件编码器要 16 对齐，WebRTC 会自己裁）
        return ((dm.widthPixels * scale).roundToInt() / 2 * 2) to ((dm.heightPixels * scale).roundToInt() / 2 * 2)
    }

    private fun startMedia(data: Intent, mic: Boolean) {
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
            factoryInitialized = true
        }
        val e = EglBase.create()
        egl = e
        val admBuilder = JavaAudioDeviceModule.builder(applicationContext)
            .setSampleRate(RATE)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordDataCallback { _, channels, _, buffer -> fillWithPlayback(buffer, channels) }
        val a = admBuilder.createAudioDeviceModule()
        adm = a
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(a)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f

        // 画面
        val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        })
        capturer = cap
        val helper = SurfaceTextureHelper.create("nagi-rtc-capture", e.eglBaseContext)
        textureHelper = helper
        val vs = f.createVideoSource(true)
        videoSource = vs
        cap.initialize(helper, applicationContext, vs.capturerObserver)
        val (w, h) = captureSize()
        capW = w; capH = h
        cap.startCapture(w, h, FPS)
        videoTrack = f.createVideoTrack("nagi-video", vs)
        watchRotation()

        // 声音：播放声音录下来放进环形缓冲；WebRTC 的录音回调从这里取
        if (mic) {
            val proj = cap.mediaProjection
            if (proj != null) startPlaybackCapture(proj)
            val c = MediaConstraints()
            for (k in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")) {
                c.mandatory.add(MediaConstraints.KeyValuePair(k, "false"))
            }
            val src = f.createAudioSource(c)
            audioSource = src
            audioTrack = f.createAudioTrack("nagi-audio", src)
        }
    }

    /** 横竖屏切换：按新方向改采集大小 */
    private fun watchRotation() {
        val dmgr = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                exec.execute {
                    if (stopped) return@execute
                    val (w, h) = captureSize()
                    if (w == capW && h == capH) return@execute
                    capW = w; capH = h
                    try { capturer?.changeCaptureFormat(w, h, FPS) } catch (e: Throwable) { Log.w(TAG, "resize", e) }
                }
            }
        }
        displayListener = l
        dmgr.registerDisplayListener(l, main)
    }

    @SuppressLint("MissingPermission")
    private fun startPlaybackCapture(proj: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(max(minBuf * 2, 19200))
                .setAudioPlaybackCaptureConfig(cfg).build()
            rec.startRecording()
            playback = rec
            audioOn = true
            Thread({
                val buf = ByteArray(1920)            // 10ms 立体声
                while (!stopped) {
                    val n = try { rec.read(buf, 0, buf.size) } catch (e: Throwable) { -1 }
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(ringLock) {
                        for (i in 0 until n) {
                            ring[ringW] = buf[i]
                            ringW = (ringW + 1) % ring.size
                            if (ringN < ring.size) ringN++ else ringR = (ringR + 1) % ring.size   // 满了丢最旧的
                        }
                    }
                }
            }, "nagi-rtc-playback").start()
        } catch (e: Throwable) {
            Log.w(TAG, "playback capture", e)
            audioOn = false
        }
    }

    /** WebRTC 每 10ms 录一段麦克风：整段换成播放声音（不够就补静音），麦克风的声音不发出去 */
    private fun fillWithPlayback(buffer: ByteBuffer, channels: Int) {
        val len = buffer.remaining()
        val pos = buffer.position()
        val out = ByteArray(len)
        synchronized(ringLock) {
            if (channels == 2) {
                val take = min(len, ringN)
                for (i in 0 until take) { out[i] = ring[ringR]; ringR = (ringR + 1) % ring.size }
                ringN -= take
                // 攒得太多（延迟变大）：丢掉一部分，保持在 0.2 秒以内
                if (ringN > RATE * 4 / 5) { val drop = ringN - RATE * 2 * 2 / 10; ringR = (ringR + drop) % ring.size; ringN -= drop }
            } else {
                // 单声道：左右取平均
                var i = 0
                while (i + 1 < len && ringN >= 4) {
                    val l = (ring[ringR].toInt() and 0xff) or (ring[(ringR + 1) % ring.size].toInt() shl 8)
                    val r = (ring[(ringR + 2) % ring.size].toInt() and 0xff) or (ring[(ringR + 3) % ring.size].toInt() shl 8)
                    val m = ((l.toShort() + r.toShort()) / 2)
                    out[i] = (m and 0xff).toByte(); out[i + 1] = (m shr 8).toByte()
                    ringR = (ringR + 4) % ring.size; ringN -= 4; i += 2
                }
            }
        }
        buffer.position(pos)
        buffer.put(out)
        buffer.position(pos)
    }

    // ---------------------------------------------------------------- 牵线

    private fun wsUrl(base: String): String {
        var b = base.trim()
        if (!b.endsWith("/")) b += "/"
        return when {
            b.startsWith("https://", true) -> "wss://" + b.substring(8)
            b.startsWith("http://", true) -> "ws://" + b.substring(7)
            else -> "wss://$b"
        } + "ws"
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun connectSignaling() {
        val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
        http = client
        ws = client.newWebSocket(Request.Builder().url(wsUrl(serverBase)).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(JSONObject().put("t", "host").put("pw", password).put("name", Build.MODEL))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                exec.execute { try { onSignal(JSONObject(text)) } catch (e: Throwable) { Log.w(TAG, "signal", e) } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped) fail("连不上服务器：${t.message ?: t.javaClass.simpleName}（${serverBase}）")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped) fail("和服务器的连接断了")
            }
        })
    }

    private fun onSignal(m: JSONObject) {
        if (stopped) return
        when (m.optString("t")) {
            "hosted" -> {
                room = m.optString("room")
                ice = parseIce(m.optJSONArray("ice"))
                updateNotification()
                Log.i(TAG, "房间 $room，ICE ${ice.size} 个")
            }
            "error" -> fail(m.optString("msg", "服务器拒绝了"))
            "peer-join" -> {
                val peer = m.optString("peer")
                newPeer(peer)
                val name = m.optString("name")
                main.post { Toast.makeText(this, "$name 开始看你的屏幕", Toast.LENGTH_SHORT).show() }
                updateNotification()
            }
            "peer-leave" -> {
                peers.remove(m.optString("peer"))?.let { try { it.close(); it.dispose() } catch (_: Throwable) {} }
                updateNotification()
            }
            "signal" -> {
                val pc = peers[m.optString("from")] ?: return
                val d = m.optJSONObject("data") ?: return
                if (d.has("sdp")) {
                    pc.setRemoteDescription(Sdp("answer"), SessionDescription(SessionDescription.Type.ANSWER, d.optString("sdp")))
                } else d.optJSONObject("candidate")?.let { c ->
                    val cand = c.optString("candidate")
                    if (cand.isNotEmpty()) pc.addIceCandidate(IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), cand))
                }
            }
            "chat" -> {
                val text = m.optString("name") + "：" + m.optString("text")
                main.post { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun parseIce(a: JSONArray?): List<PeerConnection.IceServer> {
        val out = mutableListOf<PeerConnection.IceServer>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val s = a.optJSONObject(i) ?: continue
            val urls = mutableListOf<String>()
            when (val u = s.opt("urls")) {
                is JSONArray -> for (j in 0 until u.length()) urls += u.optString(j)
                is String -> urls += u
            }
            if (urls.isEmpty()) continue
            val b = PeerConnection.IceServer.builder(urls)
            if (s.has("username")) b.setUsername(s.optString("username"))
            if (s.has("credential")) b.setPassword(s.optString("credential"))
            out += b.createIceServer()
        }
        return out
    }

    private fun newPeer(peer: String) {
        val f = factory ?: return
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "观众 $peer ICE $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: IceCandidate) {
                exec.execute {
                    send(JSONObject().put("t", "signal").put("to", peer).put("data", JSONObject().put("candidate",
                        JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))))
                }
            }
            override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return
        peers[peer] = pc
        val streams = listOf("nagi")
        videoTrack?.let { vt ->
            val sender = pc.addTrack(vt, streams)
            // 码率上限 + 优先保清晰度（屏幕上的字）
            try {
                val p = sender.parameters
                p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                for (en in p.encodings) { en.maxBitrateBps = bitrate; en.maxFramerate = FPS }
                sender.setParameters(p)
            } catch (e: Throwable) { Log.w(TAG, "bitrate", e) }
            // H.264 排第一（硬件编码）
            try {
                val caps = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                val sorted = caps.filter { it.name.equals("H264", true) } + caps.filter { !it.name.equals("H264", true) }
                pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.setCodecPreferences(sorted)
            } catch (e: Throwable) { Log.w(TAG, "codec prefs", e) }
        }
        audioTrack?.let { pc.addTrack(it, streams) }
        pc.createOffer(object : Sdp("offer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                exec.execute {
                    pc.setLocalDescription(Sdp("local"), desc)
                    send(JSONObject().put("t", "signal").put("to", peer)
                        .put("data", JSONObject().put("type", "offer").put("sdp", desc.description)))
                }
            }
        }, MediaConstraints())
    }

    private open inner class Sdp(val what: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(msg: String?) { Log.w(TAG, "$what create: $msg") }
        override fun onSetFailure(msg: String?) { Log.w(TAG, "$what set: $msg") }
    }

    // ---------------------------------------------------------------- 结束

    override fun onDestroy() {
        stopped = true
        SuiteRtcShare.service = null
        displayListener?.let { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        exec.execute {
            for (pc in peers.values) { try { pc.close(); pc.dispose() } catch (_: Throwable) {} }
            peers.clear()
            try { capturer?.stopCapture() } catch (_: Throwable) {}
            try { capturer?.dispose() } catch (_: Throwable) {}
            try { playback?.stop(); playback?.release() } catch (_: Throwable) {}
            try { videoTrack?.dispose(); videoSource?.dispose() } catch (_: Throwable) {}
            try { audioTrack?.dispose(); audioSource?.dispose() } catch (_: Throwable) {}
            try { textureHelper?.dispose() } catch (_: Throwable) {}
            try { factory?.dispose() } catch (_: Throwable) {}
            try { adm?.release() } catch (_: Throwable) {}
            try { egl?.release() } catch (_: Throwable) {}
            http?.dispatcher?.executorService?.shutdown()
        }
        exec.shutdown()
        super.onDestroy()
    }
}
