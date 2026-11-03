package com.nagisaland.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/** 识屏翻译的入口：申请悬浮窗 / 录屏权限，启动 [OcrService] */
object OcrOverlay {
    const val REQ = 0x4f43

    @JvmStatic fun isRunning() = OcrService.running

    /** 返回 "running" / "overlay"（去开悬浮窗权限）/ "ok"（已弹录屏授权）/ 错误信息 */
    @JvmStatic
    fun start(a: Activity): String = try {
        when {
            OcrService.running -> "running"
            !Settings.canDrawOverlays(a) -> {
                a.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "overlay"
            }
            else -> {
                val mpm = a.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                a.startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
                "ok"
            }
        }
    } catch (e: Throwable) { e.toString() }

    @JvmStatic
    fun stop(ctx: Context) {
        try { ctx.stopService(Intent(ctx, OcrService::class.java)) } catch (_: Throwable) {}
    }

    @JvmStatic
    fun onActivityResult(a: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ || resultCode != Activity.RESULT_OK || data == null) return
        val i = Intent(a, OcrService::class.java).putExtra("code", resultCode).putExtra("data", data)
        if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i) else a.startService(i)
    }
}

/** 浮窗网页（ocr.html?w=frame|ctl）里的 window.Ocr */
class OcrBridge(private val s: OcrService) {
    /** 截取取词框里的画面，结果（JPEG base64，或 "!错误"）用 result(id) 取 */
    @JavascriptInterface fun capture(id: String?, hide: Boolean, maxW: Int) = s.capture(id, hide, maxW)

    @JavascriptInterface
    fun config(): String = try {
        FileInputStream(File(Environment.getExternalStorageDirectory(), "Nagisaland/translate.json")).use {
            String(GameClient.readAll(it), Charsets.UTF_8)
        }
    } catch (e: Throwable) { "" }

    @JavascriptInterface fun ctlPos(): String = s.ctlPos()

    @JavascriptInterface fun rect(): String = s.frameRect()

    @JavascriptInterface fun moveCtl(x: Int, y: Int) = s.moveCtl(x, y)

    @JavascriptInterface fun setFrame(x: Int, y: Int, w: Int, h: Int) = s.setFrame(x, y, w, h)

    @JavascriptInterface fun setTouchable(on: Boolean) = s.setTouchable(on)

    @JavascriptInterface fun stop() = s.stopSelf()

    @JavascriptInterface fun toCtl(js: String?) = s.eval("ctl", js)

    @JavascriptInterface fun toFrame(js: String?) = s.eval("frame", js)

    @JavascriptInterface fun which(): String = ""

    @JavascriptInterface fun result(id: String?): String? = id?.let { HttpJob.RESULTS.remove(it) }

    /** 翻译接口请求（后台线程），结果用 result(id) 取 */
    @JavascriptInterface
    fun http(id: String?, method: String?, url: String?, headers: String?, body: String?) {
        Thread {
            val o = JSONObject()
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 90000
                c.requestMethod = method
                if (!headers.isNullOrEmpty()) {
                    val h = JSONObject(headers)
                    for (k in h.keys()) c.setRequestProperty(k, h.getString(k))
                }
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val bytes = if (stream != null) GameClient.readAll(stream) else ByteArray(0)
                o.put("status", code).put("body", String(bytes, Charsets.UTF_8))
            } catch (e: Throwable) {
                try { o.put("status", 0).put("error", e.toString()) } catch (_: Throwable) {}
            }
            id?.let { HttpJob.RESULTS[it] = o.toString() }
        }.start()
    }
}

/**
 * 识屏翻译：录屏（MediaProjection）+ 两个悬浮 WebView：
 * frame = 取词框（默认不接收触摸，调整时 setTouchable(true)），ctl = 工具条（左边 32dp 是拖动把手）。
 * 位置按横屏 / 竖屏分别记住（SharedPreferences "ocr"）。
 */
class OcrService : Service() {
    companion object {
        @Volatile @JvmField var running = false
        @JvmField var self: OcrService? = null
    }

    private var bg: Handler? = null
    private var ui: Handler? = null
    private var thread: HandlerThread? = null
    private lateinit var cp: WindowManager.LayoutParams
    private lateinit var fp: WindowManager.LayoutParams
    private var ctl: WebView? = null
    private var frame: WebView? = null
    private var density = 1f
    private var dpi = 0
    private var sw = 0
    private var sh = 0
    private var last: Image? = null
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || running) return START_NOT_STICKY
        self = this
        ui = Handler(Looper.getMainLooper())
        goForeground()
        try {
            val code = intent.getIntExtra("code", 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data")!!
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
                ?: throw IllegalStateException("getMediaProjection 返回 null（屏幕录制授权失败）")
            projection = proj
            val t = HandlerThread("nl-ocr")
            t.start()
            thread = t
            bg = Handler(t.looper)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { ui?.post { stopSelf() } }
            }, bg)
            val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            w.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels
            sh = dm.heightPixels
            dpi = dm.densityDpi
            density = dm.density
            startCapture()
            createWindows()
            running = true
        } catch (e: Throwable) {
            Log.e("nl-ocr", "start failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("nl_ocr", "识屏翻译", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "nl_ocr")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n = b.setSmallIcon(applicationInfo.icon)
            .setContentTitle("Nagisaland 识屏翻译")
            .setContentText("正在显示翻译浮窗，点工具条上的 ✕ 关闭")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(0x4f43, n, 0x20 /* FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION */)
        else startForeground(0x4f43, n)
    }

    @SuppressLint("WrongConstant")
    private fun newReader() {
        val r = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Throwable) { null } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                if (rd !== reader) { img.close(); return@setOnImageAvailableListener }
                last?.close()
                last = img
            }
        }, bg)
    }

    private fun startCapture() {
        newReader()
        vdisplay = projection!!.createVirtualDisplay(
            "nagisaland-ocr", sw, sh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, bg
        )
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(which: String): WebView {
        val v = WebView(this)
        v.setBackgroundColor(0)
        v.settings.javaScriptEnabled = true
        v.settings.domStorageEnabled = true
        v.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != "app.nagisaland.game") return null
                val path = u.path?.replaceFirst(Regex("^/+"), "") ?: ""
                return try {
                    val input = assets.open("launcher/$path")
                    val mime = when {
                        path.endsWith(".js") -> "application/javascript"
                        path.endsWith(".css") -> "text/css"
                        path.endsWith(".png") -> "image/png"
                        else -> "text/html"
                    }
                    WebResourceResponse(mime, "UTF-8", input)
                } catch (e: Exception) { null }
            }
        }
        v.addJavascriptInterface(OcrBridge(this), "Ocr")
        v.loadUrl("https://app.nagisaland.game/ocr.html?w=$which")
        return v
    }

    private fun createWindows() {
        val w = wm!!
        frame = makeWeb("frame")
        // NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_NO_LIMITS
        fp = WindowManager.LayoutParams(100, 100, overlayType(), 0x218, PixelFormat.TRANSLUCENT)
        fp.gravity = Gravity.TOP or Gravity.LEFT
        placeFrame()
        w.addView(frame, fp)
        ctl = makeWeb("ctl")
        // NOT_FOCUSABLE | LAYOUT_NO_LIMITS | WATCH_OUTSIDE_TOUCH
        cp = WindowManager.LayoutParams((300 * density).toInt(), (48 * density).toInt(), overlayType(), 0x40208, PixelFormat.TRANSLUCENT)
        cp.gravity = Gravity.TOP or Gravity.LEFT
        placeCtl()
        w.addView(ctl, cp)
        ctl!!.setOnTouchListener(CtlDrag())
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("ocr", Context.MODE_PRIVATE)

    /** 横屏 / 竖屏分开存 */
    private fun k(name: String) = (if (sw > sh) "L" else "P") + name

    private fun placeFrame() {
        val p = prefs()
        val land = sw > sh
        val dw = (sw * (if (land) 0.7 else 0.9)).toInt()
        val dh = (sh * (if (land) 0.3 else 0.28)).toInt()
        fp.width = minOf(p.getInt(k("fw"), dw), sw)
        fp.height = minOf(p.getInt(k("fh"), dh), sh)
        fp.x = maxOf(-fp.width / 2, minOf(sw - fp.width / 2, p.getInt(k("fx"), (sw - fp.width) / 2)))
        fp.y = maxOf(0, minOf(sh - (40 * density).toInt(), p.getInt(k("fy"), sh - fp.height - (16 * density).toInt())))
    }

    private fun placeCtl() {
        val p = prefs()
        cp.x = maxOf(0, minOf(sw - cp.width, p.getInt(k("cx"), (12 * density).toInt())))
        cp.y = maxOf(0, minOf(sh - cp.height, p.getInt(k("cy"), (80 * density).toInt())))
    }

    private fun saveFrame() {
        prefs().edit().putInt(k("fx"), fp.x).putInt(k("fy"), fp.y).putInt(k("fw"), fp.width).putInt(k("fh"), fp.height).apply()
    }

    private fun saveCtl() {
        prefs().edit().putInt(k("cx"), cp.x).putInt(k("cy"), cp.y).apply()
    }

    fun ctlPos() = "${cp.x},${cp.y}"

    fun frameRect() = "${fp.x},${fp.y},${fp.width},${fp.height},$sw,$sh,$density"

    fun eval(which: String, js: String?) {
        ui?.post { (if (which == "frame") frame else ctl)?.evaluateJavascript(js ?: "", null) }
    }

    fun moveCtl(x: Int, y: Int) {
        ui?.post {
            val v = ctl ?: return@post
            cp.x = maxOf(0, minOf(sw - cp.width, x))
            cp.y = maxOf(0, minOf(sh - cp.height, y))
            wm?.updateViewLayout(v, cp)
            saveCtl()
        }
    }

    fun setFrame(x: Int, y: Int, w: Int, h: Int) {
        ui?.post {
            val v = frame ?: return@post
            fp.x = x
            fp.y = y
            fp.width = maxOf((60 * density).toInt(), w)
            fp.height = maxOf((40 * density).toInt(), h)
            wm?.updateViewLayout(v, fp)
            saveFrame()
        }
    }

    fun setTouchable(on: Boolean) {
        ui?.post {
            val v = frame ?: return@post
            val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            fp.flags = if (on) fp.flags and notTouchable.inv() else fp.flags or notTouchable
            v.setOnTouchListener(if (on) FrameDrag() else null)
            wm?.updateViewLayout(v, fp)
        }
    }

    /** hide = 截图前先把浮窗藏起来（不然会把翻译文字也截进去） */
    fun capture(id: String?, hide: Boolean, maxW: Int) {
        ui?.post {
            if (hide) {
                frame?.visibility = View.INVISIBLE
                ctl?.visibility = View.INVISIBLE
            }
            val x = fp.x; val y = fp.y; val w = fp.width; val h = fp.height
            ui?.postDelayed({
                bg?.post {
                    val r = try { grab(x, y, w, h, maxW) } catch (e: Throwable) { "!$e" }
                    id?.let { HttpJob.RESULTS[it] = r }
                    ui?.post {
                        frame?.visibility = View.VISIBLE
                        ctl?.visibility = View.VISIBLE
                    }
                }
            }, if (hide) 220L else 10L)
        }
    }

    private fun grab(x0: Int, y0: Int, w0: Int, h0: Int, maxW: Int): String {
        val full: Bitmap = synchronized(lock) {
            val img = last ?: return "!还没有画面（稍等一下再试）"
            val plane = img.planes[0]
            val buf = plane.buffer
            val b = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            buf.rewind()
            b.copyPixelsFromBuffer(buf)
            b
        }
        val x = maxOf(0, x0)
        val y = maxOf(0, y0)
        val w = minOf(w0, minOf(sw, full.width) - x)
        val h = minOf(h0, full.height - y)
        if (w <= 0 || h <= 0) return "!框在屏幕外面"
        var bmp = Bitmap.createBitmap(full, x, y, w, h)
        if (maxW > 0 && bmp.width > maxW) bmp = Bitmap.createScaledBitmap(bmp, maxW, maxOf(1, bmp.height * maxW / bmp.width), true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ui?.postDelayed({ reconfig() }, 300)
    }

    /** 转屏：按新尺寸重建截屏、重新摆放浮窗 */
    private fun reconfig() {
        val w = wm ?: return
        val vd = vdisplay ?: return
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        w.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels == sw && dm.heightPixels == sh) return
        sw = dm.widthPixels
        sh = dm.heightPixels
        dpi = dm.densityDpi
        val old = reader
        synchronized(lock) {
            last?.close()
            last = null
        }
        newReader()
        vd.resize(sw, sh, dpi)
        vd.surface = reader!!.surface
        try { old?.close() } catch (_: Throwable) {}
        frame?.let { placeFrame(); w.updateViewLayout(it, fp) }
        ctl?.let { placeCtl(); w.updateViewLayout(it, cp) }
    }

    override fun onDestroy() {
        running = false
        self = null
        try { frame?.let { wm?.removeView(it); it.destroy() } } catch (_: Throwable) {}
        try { ctl?.let { wm?.removeView(it); it.destroy() } } catch (_: Throwable) {}
        frame = null
        ctl = null
        try { vdisplay?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        synchronized(lock) {
            last?.close()
            last = null
        }
        thread?.quitSafely()
        super.onDestroy()
    }

    /** 工具条：按住左边 32dp 拖动；点浮窗外面时通知网页（收起菜单） */
    private inner class CtlDrag : View.OnTouchListener {
        var drag = false
        var rx = 0f
        var ry = 0f
        var x0 = 0
        var y0 = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    eval("ctl", "window.onOutside&&onOutside()")
                    return false
                }
                MotionEvent.ACTION_DOWN -> {
                    drag = e.x < 32 * density
                    if (!drag) return false
                    rx = e.rawX; ry = e.rawY; x0 = cp.x; y0 = cp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!drag) return false
                    cp.x = maxOf(0, minOf(sw - cp.width, x0 + (e.rawX - rx).toInt()))
                    cp.y = maxOf(0, minOf(sh - cp.height, y0 + (e.rawY - ry).toInt()))
                    wm?.updateViewLayout(ctl, cp)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!drag) return false
                    drag = false
                    saveCtl()
                }
                else -> return drag
            }
            return true
        }
    }

    /** 取词框（可调整时）：拖右下角 56dp 改大小，拖其它地方移动 */
    private inner class FrameDrag : View.OnTouchListener {
        var size = false
        var rx = 0f
        var ry = 0f
        var x0 = 0
        var y0 = 0
        var w0 = 0
        var h0 = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rx = e.rawX; ry = e.rawY; x0 = fp.x; y0 = fp.y; w0 = fp.width; h0 = fp.height
                    size = e.x > fp.width - 56 * density && e.y > fp.height - 56 * density
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - rx).toInt()
                    val dy = (e.rawY - ry).toInt()
                    if (size) {
                        fp.width = maxOf((60 * density).toInt(), w0 + dx)
                        fp.height = maxOf((40 * density).toInt(), h0 + dy)
                    } else {
                        fp.x = maxOf(-fp.width / 2, minOf(sw - fp.width / 2, x0 + dx))
                        fp.y = maxOf(0, minOf(sh - (40 * density).toInt(), y0 + dy))
                    }
                    wm?.updateViewLayout(frame, fp)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveFrame()
            }
            return true
        }
    }
}
