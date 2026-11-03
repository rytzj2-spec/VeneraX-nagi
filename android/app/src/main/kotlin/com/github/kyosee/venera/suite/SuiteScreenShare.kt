package io.github.kyosee.venera.suite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 手机屏幕共享（手机自己当服务器）：录屏（MediaProjection）→ 每秒约 12 张 JPEG（MJPEG）+ 手机正在播放的声音（Android 10+，PCM），
 * 用一个很小的 HTTP 服务发出去；对方用浏览器打开 `http://<手机 IP>:<端口>/<口令>/` 就能看，不用装 App。
 *
 * - 同一个 Wi-Fi 直接能看；不在同一个网络时，两边装 Tailscale 之类的组网工具（手机一般没有公网地址，外面连不进来）。
 * - 链接里带随机口令，同一个 Wi-Fi 下的别人猜不到。
 * - 有版权保护的 App（很多视频 App）录出来是黑的；别的 App 不允许录声音时只有画面。
 */
object SuiteScreenShare {
    const val PORT = 8686
    @Volatile internal var service: SuiteScreenShareService? = null

    val running: Boolean get() = service != null

    fun links(): List<Pair<String, String>> {
        val s = service ?: return emptyList()
        return addresses().map { (label, host) -> label to "http://$host:${s.port}/${s.token}/" }
    }

    /** 手机的地址：Wi-Fi / 热点 / Tailscale（100.64.0.0/10）/ 公网 IPv6 */
    private fun addresses(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress?.substringBefore('%') ?: continue
                    when {
                        addr is Inet4Address -> {
                            val b = addr.address
                            val first = b[0].toInt() and 0xff
                            val second = b[1].toInt() and 0xff
                            val label = when {
                                first == 100 && second in 64..127 -> "Tailscale / 组网"
                                ni.name.startsWith("wlan") || ni.name.startsWith("ap") || ni.name.startsWith("swlan") -> "Wi-Fi / 热点"
                                addr.isSiteLocalAddress -> "局域网（${ni.name}）"
                                else -> "公网 IPv4（${ni.name}）"
                            }
                            out += label to ip
                        }
                        addr is Inet6Address && !addr.isSiteLocalAddress && (ip.startsWith("2") || ip.startsWith("3")) ->
                            out += "IPv6（${ni.name}，不一定连得上）" to "[$ip]"
                    }
                }
            }
        } catch (_: Throwable) {}
        return out.sortedBy { if (it.first.startsWith("Wi-Fi")) 0 else if (it.first.startsWith("Tailscale")) 1 else 2 }
    }

    fun start(a: Activity) {
        a.startActivity(Intent(a, SuiteShareActivity::class.java))
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, SuiteScreenShareService::class.java).setAction(SuiteScreenShareService.ACTION_STOP))
    }

    /** 共享中：显示链接（复制 / 分享 / 停止） */
    fun showLinks(a: Activity, onClose: (() -> Unit)? = null) {
        val s = service
        val root = SuitePanels.sheet(a)
        if (s == null) {
            root.addView(SuitePanels.title(a, "屏幕共享没有在运行"))
        } else {
            val ls = links()
            root.addView(SuitePanels.title(a, "正在共享屏幕",
                "把链接发给对方，对方用浏览器打开就能看（${s.viewers} 人在看）。" +
                    (if (s.audioOn) "声音：对方点一下页面打开。" else "声音：没开（Android 10+ 并允许录音才有）。") +
                    "\n不在同一个 Wi-Fi 时两边要装 Tailscale 之类的组网工具。"))
            if (ls.isEmpty()) root.addView(TextView(a).apply { text = "没找到手机的网络地址：先连上 Wi-Fi 或打开热点"; setTextColor(0xFFF87171.toInt()) })
            for ((label, url) in ls) {
                root.addView(TextView(a).apply {
                    text = "$label\n$url"; setTextColor(SuitePanels.TEXT); textSize = 14f
                    setPadding(0, (8 * a.resources.displayMetrics.density).toInt(), 0, 0)
                    setTextIsSelectable(true)
                    setOnClickListener {
                        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Nagisaland 屏幕共享", url))
                        Toast.makeText(a, "已复制", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
        lateinit var dialog: Dialog
        val dp = a.resources.displayMetrics.density
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (14 * dp).toInt(), 0, 0) }
        fun lp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (s != null) {
            row.addView(SuitePanels.button(a, "停止共享") { dialog.dismiss(); stop(a) }, lp())
            row.addView(android.view.View(a), LinearLayout.LayoutParams((8 * dp).toInt(), 1))
            row.addView(SuitePanels.button(a, "分享链接", primary = true) {
                val text = links().joinToString("\n") { "${it.first}：${it.second}" }
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "来看我的手机屏幕（浏览器打开）：\n$text"), "分享屏幕共享链接"))
            }, lp())
        } else {
            row.addView(SuitePanels.button(a, "关闭") { dialog.dismiss() }, lp())
        }
        root.addView(row)
        dialog = SuitePanels.bottomDialog(a, root)
        dialog.setOnDismissListener { onClose?.invoke() }
        dialog.show()
    }
}

/**
 * 透明界面：选「经服务器 / 局域网直连」→ 要录音权限（可选）→ 要录屏授权 → 启动对应的共享服务 → 显示链接。
 * 正在共享时打开 = 显示链接 / 停止。
 */
class SuiteShareActivity : Activity() {
    companion object {
        private const val REQ_CAPTURE = 0x5348
        private const val REQ_AUDIO = 0x5349
    }

    private var viaServer = true
    private var server = SuiteRtcShare.DEFAULT_SERVER
    private var password = ""
    private var quality = 1
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        when {
            SuiteRtcShare.running -> showRtcLinks()
            SuiteScreenShare.running -> SuiteScreenShare.showLinks(this) { finish() }
            else -> showChooser()
        }
    }

    private fun showChooser() {
        server = SuiteRtcShare.server(this)
        password = SuiteRtcShare.password(this)
        quality = SuiteRtcShare.quality(this)
        val root = SuitePanels.sheet(this)
        root.addView(SuitePanels.title(this, "共享我的屏幕"))
        fun edit(hint: String, value: String) = android.widget.EditText(this).apply {
            this.hint = hint; setText(value); isSingleLine = true
            setTextColor(SuitePanels.TEXT); setHintTextColor(SuitePanels.SUB); textSize = 14f
        }
        root.addView(TextView(this).apply {
            text = "经服务器：对方在任何网络都能看（能直连就直连，不费服务器流量；连不上由 TURN 中转）。"
            setTextColor(SuitePanels.SUB); textSize = 12.5f
        })
        val serverEdit = edit("服务器地址，例如 https://你的域名/share/", server)
        val pwEdit = edit("房间密码（可不填；会带在分享链接里）", password)
        root.addView(serverEdit)
        root.addView(pwEdit)
        val qRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * dp).toInt(), 0, 0) }
        val qButtons = mutableListOf<android.widget.Button>()
        fun paintQ() = qButtons.forEachIndexed { i, b ->
            b.alpha = if (i == quality) 1f else 0.45f
        }
        SuiteRtcShare.QUALITY_LABELS.forEachIndexed { i, label ->
            if (i > 0) qRow.addView(android.view.View(this), LinearLayout.LayoutParams((6 * dp).toInt(), 1))
            val b = SuitePanels.button(this, label.substringBefore("（")) { quality = i; paintQ() }
            qButtons += b
            qRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paintQ()
        root.addView(qRow)
        lateinit var dialog: Dialog
        var chosen = false
        fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * dp).toInt() }
        root.addView(SuitePanels.button(this, "经服务器共享", primary = true) {
            server = serverEdit.text.toString().trim()
            if (server.isEmpty()) {
                Toast.makeText(this, "先填服务器地址（问给你配置的人要，或者导入他的一键配置）", Toast.LENGTH_LONG).show(); return@button
            }
            if (!server.startsWith("http://", true) && !server.startsWith("https://", true)) server = "https://$server"
            password = pwEdit.text.toString().trim()
            SuiteRtcShare.save(this, server, password, quality)
            viaServer = true; chosen = true; dialog.dismiss(); askAudio()
        }, full())
        root.addView(SuitePanels.button(this, "局域网直连（同一个 Wi-Fi / Tailscale）") {
            viaServer = false; chosen = true; dialog.dismiss(); askAudio()
        }, full())
        root.addView(TextView(this).apply {
            text = "声音（Android 10+）：录的是手机正在播放的声音。经服务器时系统会显示麦克风在用，但麦克风的声音不会发出去。" +
                "有版权保护的视频 App 录出来是黑的。"
            setTextColor(SuitePanels.SUB); textSize = 12f
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        dialog = SuitePanels.bottomDialog(this, android.widget.ScrollView(this).apply { addView(root) })
        dialog.setOnDismissListener { if (!chosen) finish() }
        dialog.show()
    }

    private fun askAudio() {
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 只用来录「手机正在播放的声音」；不给也能共享画面
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        } else {
            askCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) askCapture()
    }

    @Suppress("DEPRECATION")
    private fun askCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Throwable) {
            Toast.makeText(this, "打不开录屏授权：${e.message}", Toast.LENGTH_LONG).show(); finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { finish(); return }
        val i = if (viaServer) {
            Intent(this, SuiteRtcShareService::class.java).putExtra("data", data)
                .putExtra("server", server).putExtra("password", password).putExtra("quality", quality)
        } else {
            Intent(this, SuiteScreenShareService::class.java).putExtra("code", resultCode).putExtra("data", data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        if (viaServer) { showRtcLinks(); return }
        // 等服务起来再显示链接
        val h = Handler(Looper.getMainLooper())
        var tries = 0
        fun waitShow() {
            if (SuiteScreenShare.running || tries++ > 20) SuiteScreenShare.showLinks(this) { finish() }
            else h.postDelayed({ waitShow() }, 150)
        }
        waitShow()
    }

    /** 经服务器：等服务器分好房间号，再显示邀请链接 */
    private fun showRtcLinks() {
        val h = Handler(Looper.getMainLooper())
        var tries = 0
        fun waitRoom() {
            val s = SuiteRtcShare.service
            when {
                s != null && s.room != null -> rtcDialog()
                (s == null && tries > 6) || tries > 100 -> {
                    if (SuiteRtcShare.service?.error == null && s == null) Toast.makeText(this, "屏幕共享没能启动", Toast.LENGTH_LONG).show()
                    finish()
                }
                else -> { tries++; h.postDelayed({ waitRoom() }, 150) }
            }
        }
        waitRoom()
    }

    private fun rtcDialog() {
        val s = SuiteRtcShare.service ?: run { finish(); return }
        val link = SuiteRtcShare.inviteLink() ?: ""
        val root = SuitePanels.sheet(this)
        root.addView(SuitePanels.title(this, "正在共享屏幕（经服务器）",
            "房间 ${s.room} · ${s.viewers} 人在看。把链接发给对方，对方用浏览器打开就能看，还能发弹幕。" +
                (if (s.audioOn) "" else "\n声音没开（Android 10+ 并允许录音才有）。")))
        root.addView(TextView(this).apply {
            text = link; setTextColor(SuitePanels.TEXT); textSize = 14f; setTextIsSelectable(true)
            setPadding(0, (6 * dp).toInt(), 0, 0)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Nagisaland 屏幕共享", link))
                Toast.makeText(this@SuiteShareActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        })
        lateinit var dialog: Dialog
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (14 * dp).toInt(), 0, 0) }
        row.addView(SuitePanels.button(this, "停止共享") { dialog.dismiss(); SuiteRtcShare.stop(this) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(android.view.View(this), LinearLayout.LayoutParams((8 * dp).toInt(), 1))
        row.addView(SuitePanels.button(this, "分享链接", primary = true) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "来看我的手机屏幕（浏览器打开）：$link"), "分享屏幕共享链接"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = SuitePanels.bottomDialog(this, root)
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }
}

class SuiteScreenShareService : Service() {
    companion object {
        const val ACTION_STOP = "nagisaland.share.STOP"
        private const val TAG = "nagi-share"
        private const val NOTIFY_ID = 0x5348
        private const val MAX_SIDE = 1280
        private const val FRAME_INTERVAL_MS = 80L     // 约 12 帧 / 秒
        private const val JPEG_QUALITY = 60
        const val AUDIO_RATE = 48000
        const val AUDIO_CHANNELS = 2
    }

    val token: String = SecureRandom().let { r -> (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)] }.joinToString("") }
    @Volatile var port = SuiteScreenShare.PORT
    @Volatile var audioOn = false
    val viewers: Int get() = videoClients.get()

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var server: ServerSocket? = null
    private var audio: AudioRecord? = null
    @Volatile private var stopped = false
    private var sw = 0
    private var sh = 0
    private var dpi = 0

    // 最新一帧 JPEG（MJPEG 客户端都发这一帧）
    private val frameLock = java.util.concurrent.locks.ReentrantLock()
    private val frameCond = frameLock.newCondition()
    @Volatile private var frame: ByteArray? = null
    @Volatile private var frameSeq = 0L
    private var lastEncode = 0L
    private val videoClients = java.util.concurrent.atomic.AtomicInteger(0)
    private val audioQueues = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()
    private val pool = Executors.newCachedThreadPool()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent == null || projection != null) return START_NOT_STICKY
        goForeground()    // Android 14：拿 MediaProjection 之前必须已经是 mediaProjection 类型的前台服务
        try {
            val code = intent.getIntExtra("code", 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data") ?: throw IllegalStateException("没有录屏授权")
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("录屏授权失败")
            projection = proj
            val t = HandlerThread("nagi-share").apply { start() }
            thread = t
            bg = Handler(t.looper)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Handler(Looper.getMainLooper()).post { stopSelf() } }
            }, bg)
            measure()
            startCapture()
            startAudio(proj)
            startServer()
            SuiteScreenShare.service = this
            updateNotification()
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            SuitePerf.error(this, "屏幕共享启动失败", e)
            Handler(Looper.getMainLooper()).post { Toast.makeText(this, "屏幕共享启动失败：${e.message}", Toast.LENGTH_LONG).show() }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("nagi_share", "屏幕共享", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "nagi_share")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, SuiteScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, SuiteShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        return b.setSmallIcon(applicationInfo.icon)
            .setContentTitle("Nagisaland 正在共享屏幕")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "停止共享", stop).build())
            .build()
    }

    private fun goForeground() {
        val n = notification("准备中…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_ID, n, 0x20 /* FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION */)
        else startForeground(NOTIFY_ID, n)
    }

    private fun updateNotification() {
        val link = SuiteScreenShare.links().firstOrNull()?.second ?: "没有网络地址：先连 Wi-Fi 或开热点"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, notification("$link（${viewers} 人在看）点这里看链接"))
    }

    // ---------------------------------------------------------------- 画面

    private fun measure() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(dm.widthPixels, dm.heightPixels))
        // 宽高取偶数，有的编码 / 显示路径对奇数尺寸不友好
        sw = ((dm.widthPixels * scale).roundToInt() / 2) * 2
        sh = ((dm.heightPixels * scale).roundToInt() / 2) * 2
        dpi = (dm.densityDpi * scale).roundToInt().coerceAtLeast(120)
    }

    @SuppressLint("WrongConstant")
    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, bg)
        return r
    }

    private fun startCapture() {
        val r = newReader()
        reader = r
        vdisplay = projection!!.createVirtualDisplay(
            "nagisaland-share", sw, sh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bg
        )
        // 横竖屏切换：按新方向重建画面大小
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY || stopped) return
                val oldW = sw; val oldH = sh
                measure()
                if (sw == oldW && sh == oldH) return
                try {
                    val nr = newReader()
                    vdisplay?.resize(sw, sh, dpi)
                    vdisplay?.surface = nr.surface
                    val old = reader
                    reader = nr
                    old?.close()
                } catch (e: Throwable) { Log.w(TAG, "resize", e) }
            }
        }, bg)
    }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Throwable) { null } ?: return
        try {
            if (rd !== reader || stopped) return
            val now = SystemClock.uptimeMillis()
            // 没人看就不编码（省电）；限制帧率
            if (videoClients.get() == 0 || now - lastEncode < FRAME_INTERVAL_MS) return
            lastEncode = now
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = img.width
            val h = img.height
            val padded = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (padded.width != w) Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() } else padded
            val out = ByteArrayOutputStream(64 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bmp.recycle()
            val bytes = out.toByteArray()
            frameLock.lock()
            try {
                frame = bytes
                frameSeq++
                frameCond.signalAll()
            } finally {
                frameLock.unlock()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "frame", e)
        } finally {
            img.close()
        }
    }

    // ---------------------------------------------------------------- 声音（Android 10+，录手机正在播放的声音，不录麦克风）

    @SuppressLint("MissingPermission")
    private fun startAudio(proj: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(minBuf * 2, 16384))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            rec.startRecording()
            audio = rec
            audioOn = true
            pool.execute {
                val chunk = ByteArray(AUDIO_RATE * AUDIO_CHANNELS * 2 / 25)   // 40ms
                while (!stopped) {
                    val n = try { rec.read(chunk, 0, chunk.size) } catch (e: Throwable) { -1 }
                    if (n < 0) break
                    if (n == 0 || audioQueues.isEmpty()) continue
                    val copy = chunk.copyOf(n)
                    for (q in audioQueues) q.offer(copy)   // 满了就丢（听的人卡住时不拖慢别人）
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "audio capture", e)
            audioOn = false
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun startServer() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(SuiteScreenShare.PORT))
        } catch (e: Throwable) {
            ss.bind(InetSocketAddress(0))       // 8686 被占了就随便一个端口
        }
        server = ss
        port = ss.localPort
        pool.execute {
            while (!stopped) {
                val c = try { ss.accept() } catch (e: Throwable) { break }
                pool.execute { serve(c) }
            }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.tcpNoDelay = true
            c.soTimeout = 15000
            val input = c.getInputStream()
            val line = readLine(input) ?: return
            while (true) { val h = readLine(input) ?: break; if (h.isEmpty()) break }   // 不需要请求头
            c.soTimeout = 0
            val path = line.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
            val prefix = "/$token"
            when (path) {
                prefix -> respond(out, "302 Found", "text/plain", "跳转".toByteArray(), "Location: $prefix/\r\n")
                "$prefix/" -> respond(out, "200 OK", "text/html; charset=utf-8", viewerHtml().toByteArray())
                "$prefix/info" -> respond(out, "200 OK", "application/json",
                    """{"audio":$audioOn,"rate":$AUDIO_RATE,"channels":$AUDIO_CHANNELS}""".toByteArray())
                "$prefix/v.mjpg" -> streamVideo(out)
                "$prefix/a.pcm" -> streamAudio(out)
                "/favicon.ico" -> respond(out, "404 Not Found", "text/plain", ByteArray(0))
                else -> respond(out, "403 Forbidden", "text/html; charset=utf-8",
                    "<meta name=viewport content='width=device-width'><p style='font:16px sans-serif'>链接不对：请用分享出来的完整链接（结尾有口令）。</p>".toByteArray())
            }
        } catch (_: Throwable) {
        } finally {
            try { c.close() } catch (_: Throwable) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray, extra: String = "") {
        out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun streamVideo(out: OutputStream) {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=nagiframe\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.flush()
        videoClients.incrementAndGet()
        updateNotificationLater()
        try {
            var sent = -1L
            while (!stopped) {
                frameLock.lock()
                val f = try {
                    if (frameSeq == sent) frameCond.await(2, TimeUnit.SECONDS)
                    sent = frameSeq
                    frame ?: ByteArray(0)
                } finally {
                    frameLock.unlock()
                }
                if (f.isEmpty()) continue
                out.write("--nagiframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                out.write(f)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            videoClients.decrementAndGet()
            updateNotificationLater()
        }
    }

    private fun streamAudio(out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        if (!audioOn) return
        val q = ArrayBlockingQueue<ByteArray>(50)   // 约 2 秒
        audioQueues += q
        try {
            while (!stopped) {
                val b = q.poll(1, TimeUnit.SECONDS) ?: continue
                out.write(b)
                out.flush()
            }
        } finally {
            audioQueues -= q
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private fun updateNotificationLater() { main.post { if (!stopped) updateNotification() } }

    /** 浏览器里看的页面：画面是 <img> 显示 MJPEG；声音用 fetch 读 PCM、Web Audio 排队播放 */
    private fun viewerHtml(): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Nagisaland 屏幕共享</title>
<style>
html,body{margin:0;height:100%;background:#000;color:#fafaf9;font:14px system-ui,sans-serif;overflow:hidden}
#v{position:fixed;inset:0;width:100%;height:100%;object-fit:contain}
#bar{position:fixed;left:0;right:0;bottom:0;display:flex;gap:8px;justify-content:center;padding:10px;background:linear-gradient(transparent,#000a)}
button{border:0;border-radius:10px;padding:10px 16px;font-size:14px;color:#18181b;background:linear-gradient(90deg,#e879a9,#a78bfa)}
button.s{background:#27272a;color:#fafaf9}
#msg{position:fixed;top:10px;left:0;right:0;text-align:center;color:#a1a1aa}
</style></head><body>
<img id="v" src="v.mjpg" alt="">
<div id="msg"></div>
<div id="bar"><button id="snd">🔊 打开声音</button><button class="s" id="fs">全屏</button></div>
<script>
const v=document.getElementById('v'),msg=document.getElementById('msg'),snd=document.getElementById('snd');
v.onerror=()=>{msg.textContent='连接断开，3 秒后重连…';setTimeout(()=>{v.src='v.mjpg?t='+Date.now()},3000)};
v.onload=()=>{msg.textContent=''};
document.getElementById('fs').onclick=()=>{const e=document.documentElement;(e.requestFullscreen||e.webkitRequestFullscreen||function(){}).call(e)};
let hideT=0;function showBar(){bar.style.opacity=1;clearTimeout(hideT);hideT=setTimeout(()=>bar.style.opacity=0,4000)}
const bar=document.getElementById('bar');document.body.onclick=showBar;showBar();
let ctx=null;
snd.onclick=async(e)=>{e.stopPropagation();
  if(ctx){ctx.close();ctx=null;snd.textContent='🔊 打开声音';return}
  const info=await fetch('info').then(r=>r.json()).catch(()=>({audio:false}));
  if(!info.audio){msg.textContent='手机那边没开声音（Android 10+ 并允许录音才有）';return}
  ctx=new (window.AudioContext||window.webkitAudioContext)({sampleRate:info.rate});
  snd.textContent='🔇 关掉声音';
  const ch=info.channels;let t=0,rest=null;
  try{
    const r=(await fetch('a.pcm')).body.getReader();
    while(ctx){
      const {value,done}=await r.read();if(done)break;
      let u=value;if(rest){const m=new Uint8Array(rest.length+u.length);m.set(rest);m.set(u,rest.length);u=m;rest=null}
      const usable=u.length-(u.length%(2*ch));if(usable<u.length)rest=u.slice(usable);
      const n=usable/(2*ch);if(!n)continue;
      const dv=new DataView(u.buffer,u.byteOffset,usable);
      const buf=ctx.createBuffer(ch,n,info.rate);
      for(let c=0;c<ch;c++){const d=buf.getChannelData(c);for(let i=0;i<n;i++)d[i]=dv.getInt16((i*ch+c)*2,true)/32768}
      const now=ctx.currentTime;
      if(t<now+0.05||t>now+0.6)t=now+0.12;   // 落后太多或攒太多：重新对齐，延迟保持在 0.1~0.6 秒
      const s=ctx.createBufferSource();s.buffer=buf;s.connect(ctx.destination);s.start(t);t+=buf.duration;
    }
  }catch(err){msg.textContent='声音断开：'+err}
};
</script></body></html>
"""

    override fun onDestroy() {
        stopped = true
        SuiteScreenShare.service = null
        try { server?.close() } catch (_: Throwable) {}
        frameLock.lock(); try { frameCond.signalAll() } finally { frameLock.unlock() }
        try { audio?.stop(); audio?.release() } catch (_: Throwable) {}
        try { vdisplay?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        thread?.quitSafely()
        pool.shutdownNow()
        super.onDestroy()
    }
}
