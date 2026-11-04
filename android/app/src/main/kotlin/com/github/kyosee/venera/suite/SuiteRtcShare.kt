package io.github.kyosee.venera.suite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
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
import org.webrtc.CapturerObserver
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 屏幕共享「经服务器」：WebRTC。手机是房主，服务器（server/nagi_share/，/share/ws）只牵线：
 * 能直连就手机 ↔ 观看者直连；连不上走 TURN（Cloudflare / 自建 coturn，服务器下发临时凭据）。
 *
 * 稳定性（v0.23.0）：
 * - 录屏用自己的 [NagiScreenCapturer]：整个共享只建一次 VirtualDisplay，转屏 / 分辨率变了用 resize()。
 *   （库自带的 ScreenCapturerAndroid.changeCaptureFormat 会释放后重建 VirtualDisplay，Android 14 不允许
 *   一次授权建第二个，录屏权限被收回 → v0.22.0「点全屏共享就结束」就是这个）
 * - 牵线断了自动重连（带房主钥匙回到原房间，服务器保留 60 秒），已经建好的 WebRTC 连接不动；断开期间要发的消息排队。
 * - ICE 断开 5 秒 / 失败 → ICE restart（房主重新发 offer），连续 3 次不行才记失败。
 * - 只在用户停止、系统收回录屏权限、启动失败时结束；结束原因发给观看者，写进 内部存储/Nagisaland/debug/share.txt。
 *
 * 声音：WebRTC 只能从「录音」来，JavaAudioDeviceModule 会开麦克风（系统显示麦克风绿点），
 * 但每 10ms 录到的一段都整段换成 AudioPlaybackCapture 录到的播放声音（没有就静音），麦克风的声音不发出去。
 */
object SuiteRtcShare {
    /** 不写死（仓库是公开的）：第一次自己填，之后记住；「一键配置」导出时带上（见 SuiteConfigPack 的 shareServer） */
    const val DEFAULT_SERVER = ""
    private const val PREFS = "nagi_share"

    @Volatile internal var service: SuiteRtcShareService? = null
    val running: Boolean get() = service != null
    /** 上次共享（经服务器）是因为什么结束的（「一起看」页面上显示） */
    @Volatile var lastEnd: String? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** 编译时带进来的默认服务器（android/nagi_local.properties 的 shareServer，不进 Git）；没有就是空 */
    fun builtInServer(ctx: Context): String {
        val id = ctx.resources.getIdentifier("nagi_share_server", "string", ctx.packageName)
        return if (id != 0) ctx.getString(id).trim() else DEFAULT_SERVER
    }
    fun server(ctx: Context) = (prefs(ctx).getString("server", "") ?: "").ifBlank { builtInServer(ctx) }
    /** 口令（房间号 + 密码）→ 观看链接；认不出返回 null */
    fun linkFromCode(ctx: Context, input: String): String? {
        val m = Regex("^([a-z0-9]{4,12})(?:[\\s/#:：]+(\\S+))?$", RegexOption.IGNORE_CASE).find(input.trim()) ?: return null
        val base = server(ctx).ifBlank { return null }.let { if (it.endsWith("/")) it else "$it/" }
        val pw = m.groupValues[2]
        return base + "#r=" + m.groupValues[1].lowercase() + if (pw.isNotEmpty()) "&p=" + android.net.Uri.encode(pw) else ""
    }
    /** 口令：房间号（有密码就「房间号 密码」） */
    fun inviteCode(): String? {
        val sv = service ?: return null
        val room = sv.room ?: return null
        return if (sv.password.isNotEmpty()) "$room ${sv.password}" else room
    }
    /** 共享中开 / 关手机麦克风（和播放的声音混在一起发出去） */
    fun setMic(on: Boolean) { service?.micOn = on }
    fun password(ctx: Context) = prefs(ctx).getString("password", "") ?: ""
    fun quality(ctx: Context) = prefs(ctx).getInt("quality2", 0)          // 见 QUALITY_LABELS
    fun prefer(ctx: Context) = prefs(ctx).getInt("prefer", 0)             // 见 PREFER_LABELS
    fun turn(ctx: Context) = prefs(ctx).getString("turn", "auto") ?: "auto"
    fun save(ctx: Context, server: String, password: String, quality: Int) =
        prefs(ctx).edit().putString("server", server).putString("password", password).putInt("quality2", quality).apply()
    fun savePrefer(ctx: Context, v: Int) = prefs(ctx).edit().putInt("prefer", v).apply()
    fun saveTurn(ctx: Context, v: String) = prefs(ctx).edit().putString("turn", v).apply()

    val QUALITY_LABELS = listOf("自动", "清晰", "标准", "省流")   // 旧版档位（只用来兼容旧设置）

    // 分辨率 / 帧率 / 码率上限：开始前和共享中都能改，改了马上生效
    val RES_LABELS = listOf("720p", "1080p", "原生")
    private val RES_SIDE = listOf(1280, 1920, 0)                 // 0 = 手机原始分辨率
    val FPS_LABELS = listOf("30 帧", "60 帧")
    private val FPS_VALUES = listOf(30, 60)
    val RATE_LABELS = listOf("自动", "4M", "8M", "16M")
    private val RATE_MAX = listOf(10_000_000, 4_000_000, 8_000_000, 16_000_000)
    fun res(ctx: Context) = prefs(ctx).getInt("res", 1)
    fun fpsIdx(ctx: Context) = prefs(ctx).getInt("fps", 0)
    fun rate(ctx: Context) = prefs(ctx).getInt("rate", 0)
    fun fps(ctx: Context) = FPS_VALUES.getOrElse(fpsIdx(ctx)) { 30 }
    fun currentQuality(ctx: Context): Quality {
        val max = RATE_MAX.getOrElse(rate(ctx)) { RATE_MAX[0] }
        return Quality(start = minOf(max, 3_000_000), min = 300_000, max = max, maxSide = RES_SIDE.getOrElse(res(ctx)) { 1920 })
    }
    /** 改画质：存下来；正在共享就马上应用 */
    fun setLive(ctx: Context, res: Int? = null, fps: Int? = null, rate: Int? = null) {
        val e = prefs(ctx).edit()
        res?.let { e.putInt("res", it) }; fps?.let { e.putInt("fps", it) }; rate?.let { e.putInt("rate", it) }
        e.apply()
        service?.applyLive(currentQuality(ctx), fps(ctx))
    }
    val PREFER_LABELS = listOf("流畅优先", "清晰优先", "平衡")
    val TURN_LABELS = linkedMapOf("auto" to "自动", "cloudflare" to "Cloudflare", "coturn" to "自建")

    /** 清晰度 → (起始, 最低, 最高) 码率 bps 和录屏最长边 */
    class Quality(val start: Int, val min: Int, val max: Int, val maxSide: Int)
    fun qualityOf(q: Int) = when (q) {
        1 -> Quality(4_000_000, 800_000, 6_000_000, 1920)
        2 -> Quality(2_500_000, 500_000, 3_500_000, 1280)
        3 -> Quality(1_200_000, 300_000, 1_500_000, 960)
        else -> Quality(2_500_000, 300_000, 8_000_000, 1280)   // 自动：起点不低，网好就往上走
    }

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

/** 共享事件日志：内部存储/Nagisaland/debug/share.txt（测完发给开发者看） */
object SuiteShareLog {
    private val io = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    fun log(line: String) {
        val text = "${fmt.format(Date())}  $line\n"
        Log.i("nagi-share", line)
        io.execute {
            try {
                val dir = File(Environment.getExternalStorageDirectory(), "Nagisaland/debug").apply { mkdirs() }
                val f = File(dir, "share.txt")
                if (f.length() > 1024 * 1024) f.renameTo(File(dir, "share.old.txt"))
                f.appendText(text)
            } catch (_: Throwable) {}
        }
    }
}

/**
 * 录屏 → WebRTC。和 ScreenCapturerAndroid 一样用 SurfaceTextureHelper 接 VirtualDisplay 的画面，
 * 区别：一次授权只建一个 VirtualDisplay，改尺寸用 resize()（Android 14 不许重建）。
 */
class NagiScreenCapturer(
    private val permission: Intent,
    private val onProjectionStopped: () -> Unit,
) : VideoCapturer {
    private var helper: SurfaceTextureHelper? = null
    private var ctx: Context? = null
    private var observer: CapturerObserver? = null
    @Volatile var projection: MediaProjection? = null; private set
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var dpi = 160
    @Volatile private var stopped = false

    override fun initialize(helper: SurfaceTextureHelper, ctx: Context, observer: CapturerObserver) {
        this.helper = helper
        this.ctx = ctx
        this.observer = observer
        val dm = ctx.resources.displayMetrics
        dpi = dm.densityDpi
    }

    override fun startCapture(width: Int, height: Int, fps: Int) {
        val c = ctx ?: return
        val h = helper ?: return
        this.width = width
        this.height = height
        val mpm = c.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(Activity.RESULT_OK, permission) ?: throw IllegalStateException("录屏授权失败")
        projection = p
        // Android 14：先注册回调再建 VirtualDisplay
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopped) onProjectionStopped()
            }
        }, h.handler)
        h.setTextureSize(width, height)
        display = p.createVirtualDisplay("nagisaland-share", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, Surface(h.surfaceTexture), null, h.handler)
        observer?.onCapturerStarted(true)
        h.startListening { frame: VideoFrame -> observer?.onFrameCaptured(frame) }
    }

    /** 转屏 / 换清晰度：只改尺寸，不重建 */
    override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
        val h = helper ?: return
        h.handler.post {
            if (stopped) return@post
            this.width = width
            this.height = height
            try {
                h.setTextureSize(width, height)
                display?.resize(width, height, dpi)
            } catch (e: Throwable) {
                SuiteShareLog.log("改录屏尺寸失败：${e.message}")
            }
        }
    }

    override fun stopCapture() {
        stopped = true
        val h = helper ?: return
        val done = java.util.concurrent.CountDownLatch(1)
        h.handler.post {
            try { h.stopListening() } catch (_: Throwable) {}
            try { display?.release() } catch (_: Throwable) {}
            display = null
            try { projection?.stop() } catch (_: Throwable) {}
            observer?.onCapturerStopped()
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
    }

    override fun dispose() { stopped = true }
    override fun isScreencast() = true
}

class SuiteRtcShareService : Service() {
    companion object {
        const val ACTION_STOP = "nagisaland.rtcshare.STOP"
        private const val TAG = "nagi-rtc"
        private const val NOTIFY_ID = 0x5352
        private const val RATE = 48000
        private val BACKOFF = longArrayOf(1, 2, 4, 8, 15, 30)
        @Volatile private var factoryInitialized = false
    }

    /** 给「一起看」页面看的状态 */
    class Stats {
        @Volatile var bitrateKbps = 0
        @Volatile var fps = 0.0
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var codec = ""
        @Volatile var encoder = ""
        @Volatile var limitedBy = ""
        @Volatile var rttMs = -1
        @Volatile var path = ""          // 直连 / 经中转（UDP / TCP / TLS）
        @Volatile var playbackPeak = 0   // 最近录到的播放声音最大幅度（0 = 一直是静音）
    }
    /** 手机麦克风混进去（共享中可开关） */
    @Volatile var micOn = false

    @Volatile var room: String? = null
    @Volatile var error: String? = null
    @Volatile var signalingUp = false
    @Volatile var serverBase: String = ""
    @Volatile var password: String = ""
    @Volatile var audioOn = false
    val stats = Stats()
    val viewers: Int get() = peers.size

    private val exec = Executors.newSingleThreadExecutor()    // 所有状态都在这一个线程上改
    private val main = Handler(Looper.getMainLooper())
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: NagiScreenCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var adm: JavaAudioDeviceModule? = null
    private var playback: AudioRecord? = null
    private var http: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var wsGen = 0                       // 每次重连 +1，旧连接的回调不理
    private var hostKey: String? = null
    private var reconnectTry = 0
    private val outbox = ArrayList<JSONObject>()
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private var turnPref = "auto"

    private class Peer(val id: String, val name: String, val pc: PeerConnection) {
        var restarts = 0
        var lastBytes = 0.0
        var lastAt = 0L
        var disconnectedAt = 0L
        var connected = false
        var kbps = 0.0
    }
    private val peers = HashMap<String, Peer>()

    private var quality = SuiteRtcShare.qualityOf(0)
    private var fps = 30
    private var prefer = 0
    @Volatile private var stopped = false
    private var displayListener: DisplayManager.DisplayListener? = null
    private var capW = 0
    private var capH = 0
    private var lastStatLog = 0L

    // 播放声音的环形缓冲（AudioPlaybackCapture 写，ADM 的录音回调读）
    private val ring = ByteArray(RATE * 2 * 2)          // 1 秒
    private var ringW = 0
    private var ringR = 0
    private var ringN = 0
    private val ringLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { end("你停止了共享"); return START_NOT_STICKY }
        if (intent == null || factory != null) return START_NOT_STICKY
        val mic = Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        goForeground(mic)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        serverBase = intent.getStringExtra("server").orEmpty()
        password = intent.getStringExtra("password").orEmpty()
        quality = SuiteRtcShare.currentQuality(this)
        fps = SuiteRtcShare.fps(this)
        prefer = SuiteRtcShare.prefer(this)
        turnPref = SuiteRtcShare.turn(this)
        SuiteRtcShare.service = this
        SuiteShareLog.log("==== 开始共享（经服务器 $serverBase，画质 ${SuiteRtcShare.RES_LABELS.getOrNull(SuiteRtcShare.res(this))} / ${fps} 帧 / 上限 ${quality.max / 1_000_000}M，" +
            "${SuiteRtcShare.PREFER_LABELS.getOrNull(prefer)}，中转 $turnPref，录音 $mic，Android ${Build.VERSION.SDK_INT} ${Build.MODEL}）")
        exec.execute {
            try {
                startMedia(data, mic)
                connectSignaling()
            } catch (e: Throwable) {
                SuitePerf.error(this, "屏幕共享（经服务器）启动失败", e)
                end("启动失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
        exec.execute { tickStats() }
        return START_NOT_STICKY
    }

    /** 结束共享（唯一出口）：告诉观看者原因、写日志、停服务 */
    private fun end(reason: String) {
        if (stopped) return
        SuiteShareLog.log("结束：$reason")
        error = reason
        SuiteRtcShare.lastEnd = if (reason == "你停止了共享") null else reason
        try {
            ws?.send(JSONObject().put("t", "bye").put("reason", reason).toString())
        } catch (_: Throwable) {}
        main.post {
            if (reason != "你停止了共享") Toast.makeText(this, "屏幕共享结束：$reason", Toast.LENGTH_LONG).show()
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
        val open = PendingIntent.getActivity(this, 4, Intent(this, SuiteTogetherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
        val text = when {
            room == null -> "连接服务器…"
            !signalingUp -> "房间 $room · 和服务器断开，重连中（画面不受影响）"
            else -> "房间 $room · ${peers.size} 人在看 · 点这里看链接"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_ID, notification(text))
    }

    // ---------------------------------------------------------------- 媒体

    private fun captureSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val side = if (quality.maxSide <= 0) max(dm.widthPixels, dm.heightPixels) else quality.maxSide
        val scale = min(1f, side.toFloat() / max(dm.widthPixels, dm.heightPixels))
        // 硬件编码器喜欢 16 的倍数
        return ((dm.widthPixels * scale).roundToInt() / 16 * 16) to ((dm.heightPixels * scale).roundToInt() / 16 * 16)
    }

    private fun startMedia(data: Intent, mic: Boolean) {
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
            factoryInitialized = true
        }
        val e = EglBase.create()
        egl = e
        val a = JavaAudioDeviceModule.builder(applicationContext)
            .setSampleRate(RATE)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .setUseHardwareAcousticEchoCanceler(true)    // 只影响真麦克风（开麦时才混进去），播放声音不经过它
            .setUseHardwareNoiseSuppressor(true)
            .setAudioRecordDataCallback { _, channels, _, buffer -> fillWithPlayback(buffer, channels) }
            .createAudioDeviceModule()
        adm = a
        val enc = DefaultVideoEncoderFactory(e.eglBaseContext, true, true)
        SuiteShareLog.log("可用的视频编码：" + enc.supportedCodecs.joinToString { it.name })
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(a)
            .setVideoEncoderFactory(enc)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f

        // 画面
        val cap = NagiScreenCapturer(data) { end("系统收回了录屏权限") }
        capturer = cap
        val helper = SurfaceTextureHelper.create("nagi-rtc-capture", e.eglBaseContext)
        textureHelper = helper
        val vs = f.createVideoSource(true)
        videoSource = vs
        cap.initialize(helper, applicationContext, vs.capturerObserver)
        val (w, h) = captureSize()
        capW = w; capH = h
        cap.startCapture(w, h, fps)
        try { vs.adaptOutputFormat(w, h, fps) } catch (_: Throwable) {}
        SuiteShareLog.log("录屏 ${w}x$h")
        videoTrack = f.createVideoTrack("nagi-video", vs)
        watchRotation()

        // 声音：播放声音录下来放进环形缓冲；WebRTC 的录音回调从这里取
        if (mic) {
            cap.projection?.let { startPlaybackCapture(it) }
            val c = MediaConstraints()
            for (k in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")) {
                c.mandatory.add(MediaConstraints.KeyValuePair(k, "false"))
            }
            val src = f.createAudioSource(c)
            audioSource = src
            audioTrack = f.createAudioTrack("nagi-audio", src)
        }
    }

    /** 共享中改画质（分辨率 / 帧率 / 码率上限）：改采集尺寸、编码参数和码率，不用重新连接 */
    fun applyLive(q: SuiteRtcShare.Quality, newFps: Int) = exec.execute {
        if (stopped) return@execute
        quality = q
        fps = newFps
        val (w, h) = captureSize()
        if (w != capW || h != capH) {
            capW = w; capH = h
            capturer?.changeCaptureFormat(w, h, fps)
        }
        try { videoSource?.adaptOutputFormat(w, h, fps) } catch (_: Throwable) {}
        for (p in peers.values) {
            try {
                for (sender in p.pc.senders) {
                    if (sender.track()?.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) continue
                    val params = sender.parameters
                    for (en in params.encodings) { en.maxBitrateBps = q.max; en.maxFramerate = fps }
                    sender.setParameters(params)
                }
                p.pc.setBitrate(q.min, null, q.max)
            } catch (e: Throwable) { Log.w(TAG, "apply live", e) }
        }
        SuiteShareLog.log("改画质 → ${w}x$h，${fps} 帧，码率上限 ${q.max / 1_000_000}M")
    }

    /** 横竖屏切换：按新方向改采集大小（resize，不重建） */
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
                    SuiteShareLog.log("屏幕方向 / 尺寸变了 → 录屏改成 ${w}x$h")
                    capturer?.changeCaptureFormat(w, h, fps)
                    try { videoSource?.adaptOutputFormat(w, h, fps) } catch (_: Throwable) {}
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
                    var peak = 0
                    var j = 0
                    while (j + 1 < n) { val v = abs(((buf[j].toInt() and 0xff) or (buf[j + 1].toInt() shl 8)).toShort().toInt()); if (v > peak) peak = v; j += 2 }
                    if (peak > stats.playbackPeak || SystemClock.elapsedRealtime() % 3000 < 20) stats.playbackPeak = peak
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
            SuiteShareLog.log("录播放声音失败：${e.message}")
            audioOn = false
        }
    }

    /** WebRTC 每 10ms 录一段麦克风：整段换成播放声音（不够就补静音），麦克风的声音不发出去 */
    private fun fillWithPlayback(buffer: ByteBuffer, channels: Int) {
        // 不能用 position / remaining：库交过来时 position 可能已经在末尾（remaining = 0），
        // 那样一个字节都换不掉，发出去的就是麦克风原声。按整块（capacity）处理，用 duplicate 不动原 position。
        val whole = buffer.duplicate().apply { clear() }
        val len = whole.capacity()
        if (len <= 0) return
        val out = ByteArray(len)
        synchronized(ringLock) {
            if (channels == 2) {
                val take = min(len, ringN)
                for (i in 0 until take) { out[i] = ring[ringR]; ringR = (ringR + 1) % ring.size }
                ringN -= take
                // 攒得太多（延迟变大）：丢掉一部分，保持在 0.1 秒左右
                if (ringN > RATE * 4 / 5) { val drop = ringN - RATE * 2 * 2 / 10; ringR = (ringR + drop) % ring.size; ringN -= drop }
            } else {
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
        if (micOn) {
            // 开麦：把真麦克风录到的（还在 buffer 里）叠加进去
            val mic = ByteArray(len)
            buffer.duplicate().apply { clear() }.get(mic)
            var i = 0
            while (i + 1 < len) {
                val a = ((out[i].toInt() and 0xff) or (out[i + 1].toInt() shl 8)).toShort().toInt()
                val b = ((mic[i].toInt() and 0xff) or (mic[i + 1].toInt() shl 8)).toShort().toInt()
                val v = (a + b).coerceIn(-32768, 32767)
                out[i] = (v and 0xff).toByte(); out[i + 1] = (v shr 8).toByte()
                i += 2
            }
        }
        whole.clear()
        whole.put(out)
        if (!loggedAudioBuf) {
            loggedAudioBuf = true
            SuiteShareLog.log("声音回调：每段 $len 字节，$channels 声道，position=${buffer.position()} limit=${buffer.limit()}")
        }
    }
    @Volatile private var loggedAudioBuf = false

    // ---------------------------------------------------------------- 牵线（自动重连）

    private fun wsUrl(base: String): String {
        var b = base.trim()
        if (!b.endsWith("/")) b += "/"
        return when {
            b.startsWith("https://", true) -> "wss://" + b.substring(8)
            b.startsWith("http://", true) -> "ws://" + b.substring(7)
            else -> "wss://$b"
        } + "ws"
    }

    /** 发给服务器；牵线断着的话先排队（offer / 候选都要送到） */
    private fun send(o: JSONObject) {
        val w = ws
        if (!signalingUp || w == null || !w.send(o.toString())) {
            if (o.optString("t") != "ping") {
                outbox += o
                if (outbox.size > 500) outbox.removeAt(0)
            }
        }
    }

    private fun connectSignaling() {
        if (stopped) return
        val client = http ?: OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build().also { http = it }
        val gen = ++wsGen
        ws = client.newWebSocket(Request.Builder().url(wsUrl(serverBase)).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                exec.execute {
                    if (gen != wsGen || stopped) return@execute
                    val hello = JSONObject().put("t", "host").put("pw", password).put("name", Build.MODEL).put("turn", turnPref)
                    val r = room; val k = hostKey
                    if (r != null && k != null) hello.put("resume", JSONObject().put("room", r).put("key", k))
                    webSocket.send(hello.toString())
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                exec.execute {
                    if (gen != wsGen) return@execute
                    try { onSignal(JSONObject(text)) } catch (e: Throwable) { Log.w(TAG, "signal", e) }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                exec.execute { if (gen == wsGen) signalingLost("连接失败：${t.message ?: t.javaClass.simpleName}") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                exec.execute { if (gen == wsGen) signalingLost("服务器关闭了连接（$code $reason）") }
            }
        })
    }

    /** 牵线断了：不结束共享，退避重连（WebRTC 连接照常） */
    private fun signalingLost(why: String) {
        if (stopped) return
        signalingUp = false
        ws = null
        updateNotification()
        // 一次都没连上过（地址错 / 服务器没开）：试 4 次就结束并说原因；连上过的断开了就一直重连
        if (room == null && reconnectTry >= 4) {
            end("连不上服务器（$serverBase）：$why"); return
        }
        val delay = BACKOFF[min(reconnectTry, BACKOFF.size - 1)]
        reconnectTry++
        SuiteShareLog.log("牵线断开：$why → ${delay} 秒后第 $reconnectTry 次重连")
        main.postDelayed({ exec.execute { connectSignaling() } }, delay * 1000)
    }

    private fun onSignal(m: JSONObject) {
        if (stopped) return
        when (m.optString("t")) {
            "hosted" -> {
                val resumed = m.optBoolean("resumed")
                val newRoom = m.optString("room")
                if (!resumed && room != null && room != newRoom) {
                    SuiteShareLog.log("原来的房间 $room 已经关了，换成新房间 $newRoom（要重新发链接）")
                    for (p in peers.values) closePeer(p)
                    peers.clear()
                    main.post { Toast.makeText(this, "掉线太久，房间换成 $newRoom，请重新发链接", Toast.LENGTH_LONG).show() }
                }
                room = newRoom
                hostKey = m.optString("key")
                ice = parseIce(m.optJSONArray("ice"))
                signalingUp = true
                reconnectTry = 0
                SuiteShareLog.log((if (resumed) "牵线重连成功，回到房间 " else "牵线连上，房间 ") + "$room，ICE 服务器 ${ice.size} 个，服务器上的中转：${m.optJSONArray("turnModes")}")
                if (resumed) {
                    // 断开期间进来的观看者
                    val list = m.optJSONArray("viewers") ?: JSONArray()
                    val ids = HashSet<String>()
                    for (i in 0 until list.length()) {
                        val v = list.optJSONObject(i) ?: continue
                        val id = v.optString("peer"); ids += id
                        if (!peers.containsKey(id)) newPeer(id, v.optString("name"))
                    }
                    for (id in peers.keys.toList()) if (id !in ids) peers.remove(id)?.let { closePeer(it) }
                }
                val pending = ArrayList(outbox); outbox.clear()
                for (o in pending) send(o)
                updateNotification()
            }
            "resume-failed" -> SuiteShareLog.log("回不到原房间：${m.optString("msg")}")
            "error" -> {
                SuiteShareLog.log("服务器拒绝：${m.optString("msg")}")
                if (room == null) end(m.optString("msg", "服务器拒绝了"))
            }
            "peer-join" -> {
                val peer = m.optString("peer")
                val name = m.optString("name")
                SuiteShareLog.log("$name 进来了（$peer）")
                newPeer(peer, name)
                main.post { Toast.makeText(this, "$name 开始看你的屏幕", Toast.LENGTH_SHORT).show() }
                updateNotification()
            }
            "peer-leave" -> {
                peers.remove(m.optString("peer"))?.let { SuiteShareLog.log("${it.name} 走了"); closePeer(it) }
                updateNotification()
            }
            "signal" -> {
                val p = peers[m.optString("from")] ?: return
                val d = m.optJSONObject("data") ?: return
                if (d.has("sdp")) {
                    p.pc.setRemoteDescription(Sdp("answer"), SessionDescription(SessionDescription.Type.ANSWER, d.optString("sdp")))
                } else d.optJSONObject("candidate")?.let { c ->
                    val cand = c.optString("candidate")
                    if (cand.isNotEmpty()) p.pc.addIceCandidate(IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), cand))
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

    // ---------------------------------------------------------------- WebRTC

    private fun degradation() = when (prefer) {
        1 -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        2 -> RtpParameters.DegradationPreference.BALANCED
        else -> RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
    }

    private fun newPeer(peerId: String, name: String) {
        val f = factory ?: return
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        lateinit var peer: Peer
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                exec.execute { onIceState(peer, state) }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: IceCandidate) {
                exec.execute {
                    send(JSONObject().put("t", "signal").put("to", peerId).put("data", JSONObject().put("candidate",
                        JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))))
                }
            }
            override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return
        peer = Peer(peerId, name, pc)
        peers[peerId] = peer
        val streams = listOf("nagi")
        videoTrack?.let { vt ->
            val sender = pc.addTrack(vt, streams)
            try {
                val p = sender.parameters
                p.degradationPreference = degradation()
                for (en in p.encodings) { en.maxBitrateBps = quality.max; en.maxFramerate = fps }
                sender.setParameters(p)
            } catch (e: Throwable) { Log.w(TAG, "encoding params", e) }
            // H.264 排第一（硬件编码）；没有 H.264 时 VP8 自然排前面
            try {
                val caps = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                val sorted = caps.filter { it.name.equals("H264", true) } + caps.filter { !it.name.equals("H264", true) }
                pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.setCodecPreferences(sorted)
            } catch (e: Throwable) { Log.w(TAG, "codec prefs", e) }
        }
        val at = audioTrack
        if (at != null) pc.addTrack(at, streams)   // 默认 sendrecv：观看者开麦的声音也能传过来
        else pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        // 码率：起点不要太低（默认从 300k 慢慢涨，前十几秒很糊）
        pc.setBitrate(quality.min, quality.start, quality.max)
        makeOffer(peer, iceRestart = false)
    }

    private fun makeOffer(peer: Peer, iceRestart: Boolean) {
        val c = MediaConstraints()
        if (iceRestart) c.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        peer.pc.createOffer(object : Sdp("offer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                exec.execute {
                    if (peers[peer.id] !== peer) return@execute
                    peer.pc.setLocalDescription(Sdp("local"), desc)
                    send(JSONObject().put("t", "signal").put("to", peer.id)
                        .put("data", JSONObject().put("type", "offer").put("sdp", desc.description)))
                }
            }
        }, c)
    }

    private fun onIceState(peer: Peer, state: PeerConnection.IceConnectionState) {
        if (peers[peer.id] !== peer || stopped) return
        SuiteShareLog.log("${peer.name}：ICE $state")
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                peer.connected = true; peer.restarts = 0; peer.disconnectedAt = 0
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                peer.disconnectedAt = SystemClock.elapsedRealtime()
                // 5 秒还没恢复就重启 ICE
                main.postDelayed({
                    exec.execute {
                        if (peers[peer.id] === peer && peer.disconnectedAt != 0L &&
                            SystemClock.elapsedRealtime() - peer.disconnectedAt >= 4900) restartIce(peer)
                    }
                }, 5000)
            }
            PeerConnection.IceConnectionState.FAILED -> restartIce(peer)
            else -> {}
        }
    }

    private fun restartIce(peer: Peer) {
        peer.restarts++
        if (peer.restarts > 3) {
            SuiteShareLog.log("${peer.name}：ICE 重启 3 次都没连上（网络不通、中转也不行），等他重新打开页面")
            return
        }
        SuiteShareLog.log("${peer.name}：ICE restart（第 ${peer.restarts} 次）")
        peer.disconnectedAt = 0
        try { peer.pc.restartIce() } catch (_: Throwable) {}
        makeOffer(peer, iceRestart = true)
    }

    private fun closePeer(p: Peer) {
        try { p.pc.close(); p.pc.dispose() } catch (_: Throwable) {}
    }

    private open inner class Sdp(val what: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(msg: String?) { SuiteShareLog.log("SDP $what 创建失败：$msg") }
        override fun onSetFailure(msg: String?) { SuiteShareLog.log("SDP $what 设置失败：$msg") }
    }

    // ---------------------------------------------------------------- 统计 + 心跳（每 2 秒）

    private fun num(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toDouble()

    private fun tickStats() {
        if (stopped) return
        if (signalingUp) send(JSONObject().put("t", "ping"))     // Cloudflare 代理的 WebSocket 空闲约 100 秒会断
        val list = peers.values.toList()
        if (list.isEmpty()) {
            stats.bitrateKbps = 0; stats.path = ""
        }
        var total = 0.0
        for (p in list) {
            p.pc.getStats { report: RTCStatsReport -> exec.execute { readStats(p, report) } }
        }
        list.forEach { total += it.kbps }
        stats.bitrateKbps = total.roundToInt()
        val now = SystemClock.elapsedRealtime()
        if (list.isNotEmpty() && now - lastStatLog > 30_000) {
            lastStatLog = now
            SuiteShareLog.log("统计：${list.size} 人，${stats.bitrateKbps} kbps，${stats.width}x${stats.height}@${"%.0f".format(stats.fps)}，" +
                "${stats.codec} ${stats.encoder}，播放声音电平 ${stats.playbackPeak}（录声音：${if (audioOn) "开" else "关"}，麦克风：${if (micOn) "开" else "关"}），受限于 ${stats.limitedBy.ifEmpty { "无" }}，RTT ${stats.rttMs} ms，${stats.path}")
        }
        main.postDelayed({ exec.execute { tickStats() } }, 2000)
    }

    private fun readStats(p: Peer, report: RTCStatsReport) {
        val all = report.statsMap
        var localCand: String? = null
        for (s in all.values) {
            val m = s.members
            when (s.type) {
                "outbound-rtp" -> if (m["kind"] == "video") {
                    val bytes = num(m, "bytesSent") ?: 0.0
                    val now = SystemClock.elapsedRealtime()
                    if (p.lastAt > 0 && now > p.lastAt) p.kbps = (bytes - p.lastBytes) * 8 / (now - p.lastAt)
                    p.lastBytes = bytes; p.lastAt = now
                    num(m, "framesPerSecond")?.let { stats.fps = it }
                    num(m, "frameWidth")?.let { stats.width = it.toInt() }
                    num(m, "frameHeight")?.let { stats.height = it.toInt() }
                    (m["encoderImplementation"] as? String)?.let { stats.encoder = it }
                    (m["qualityLimitationReason"] as? String)?.let { stats.limitedBy = if (it == "none") "" else it }
                    (m["codecId"] as? String)?.let { id -> (all[id]?.members?.get("mimeType") as? String)?.let { stats.codec = it.removePrefix("video/") } }
                }
                "candidate-pair" -> if (m["state"] == "succeeded" && (m["nominated"] == true)) {
                    num(m, "currentRoundTripTime")?.let { stats.rttMs = (it * 1000).roundToInt() }
                    localCand = m["localCandidateId"] as? String
                }
            }
        }
        localCand?.let { id ->
            val lc = all[id]?.members ?: return@let
            val relay = lc["candidateType"] == "relay"
            val proto = ((lc["relayProtocol"] ?: lc["protocol"]) as? String)?.uppercase() ?: ""
            stats.path = if (relay) "经中转（$proto）" else "直连（$proto）"
        }
    }

    // ---------------------------------------------------------------- 结束

    override fun onDestroy() {
        if (!stopped) SuiteShareLog.log("服务被系统结束")
        stopped = true
        SuiteRtcShare.service = null
        displayListener?.let { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        val w = ws
        main.postDelayed({ try { w?.close(1000, "bye") } catch (_: Throwable) {} }, 300)   // 先让 bye 发出去
        exec.execute {
            for (p in peers.values) closePeer(p)
            peers.clear()
            try { capturer?.stopCapture() } catch (_: Throwable) {}
            try { playback?.stop(); playback?.release() } catch (_: Throwable) {}
            try { videoTrack?.dispose(); videoSource?.dispose() } catch (_: Throwable) {}
            try { audioTrack?.dispose(); audioSource?.dispose() } catch (_: Throwable) {}
            try { textureHelper?.dispose() } catch (_: Throwable) {}
            try { factory?.dispose() } catch (_: Throwable) {}
            try { adm?.release() } catch (_: Throwable) {}
            try { egl?.release() } catch (_: Throwable) {}
        }
        exec.shutdown()
        main.postDelayed({ http?.dispatcher?.executorService?.shutdown() }, 1000)
        super.onDestroy()
    }
}
