package com.nagisaland.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

/**
 * Nagisaland 主界面：全屏 WebView，界面是 assets/launcher/ 里的网页（https://app.nagisaland.game/）。
 * 网页通过 window.NagisaNative（[Bridge]）调原生功能；网页游戏按游戏分配虚拟域名，由 [GameClient] 映射到游戏文件夹。
 *
 * 由原来的 smali（Java 源码丢失）改写成 Kotlin，行为保持一致，只有两处改动：
 * - onPause 不再调用 WebView.pauseTimers()：它是全进程的，会冻结番剧解析 / 漫画验证 / H 登录用的 WebView
 *   （以前由电脑上的 merge_venerax.py 删掉）。
 * - 在游戏库根页面按返回键：整个 App 退到后台（以前是 finishAndRemoveTask，会把漫画 / 番剧 / H 一起关掉）。
 */
class MainActivity : Activity() {
    companion object {
        const val LAUNCHER = "https://app.nagisaland.game/index.html"

        /** 虚拟域名 → 游戏文件夹 */
        @JvmField val GAMES = ConcurrentHashMap<String, String>()

        /** 虚拟域名 → 引擎（决定注入 bridge-<引擎>.js） */
        @JvmField val ENGINES = ConcurrentHashMap<String, String>()

        private val MIME = arrayOf(
            ".js", "application/javascript", ".mjs", "application/javascript", ".json", "application/json",
            ".html", "text/html", ".htm", "text/html", ".css", "text/css", ".wasm", "application/wasm",
            ".png", "image/png", ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".webp", "image/webp",
            ".gif", "image/gif", ".svg", "image/svg+xml", ".ico", "image/x-icon", ".ogg", "audio/ogg",
            ".m4a", "audio/mp4", ".mp3", "audio/mpeg", ".wav", "audio/wav", ".webm", "video/webm",
            ".mp4", "video/mp4", ".woff2", "font/woff2", ".woff", "font/woff", ".ttf", "font/ttf",
            ".otf", "font/otf", ".txt", "text/plain",
        )

        @JvmStatic
        fun mimeFor(path: String): String {
            val p = path.lowercase()
            var i = 0
            while (i < MIME.size) {
                if (p.endsWith(MIME[i])) return MIME[i + 1]
                i += 2
            }
            return "application/octet-stream"
        }
    }

    var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val wv = WebView(this)
        webView = wv
        wv.setBackgroundColor(0xFF000000.toInt())
        wv.settings.apply {
            @Suppress("SetJavaScriptEnabled")
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            allowFileAccess = false
            textZoom = 100
        }
        wv.webViewClient = GameClient(this)
        wv.webChromeClient = WebChromeClient()
        wv.addJavascriptInterface(Bridge(this), "NagisaNative")
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        setContentView(wv)
        hideSystemUi()
        wv.loadUrl(LAUNCHER)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        // LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION | LAYOUT_FULLSCREEN | HIDE_NAVIGATION | FULLSCREEN | IMMERSIVE_STICKY
        window.decorView.systemUiVisibility = 0x1706
    }

    fun goHome() {
        val wv = webView ?: return
        requestedOrientation = -1
        wv.loadUrl(LAUNCHER)
    }

    /** 启动器网页根页面退出（galport://exit）：整个任务退到后台，各分区界面都保留 */
    fun exitToBackground() {
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        OcrOverlay.onActivityResult(this, requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView?.evaluateJavascript("if(window.__galportBack){window.__galportBack()}else{location.href='galport://exit'}", null)
    }

    override fun onPause() {
        super.onPause()
        // 只暂停这个 WebView；不调用全局的 pauseTimers()（见类注释）
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.let {
            it.onResume()
            it.resumeTimers()
            it.evaluateJavascript("window.__nagisaResume&&window.__nagisaResume()", null)
        }
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }
}
