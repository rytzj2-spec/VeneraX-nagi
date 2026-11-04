package io.github.kyosee.venera.suite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 在 App 里看别人的屏幕共享：全屏 WebView 打开同一个观看网页（经服务器的 /share/#r=房间号）。
 * - 自动出声（不用点「打开声音」）；UA 带 NagisalandApp，网页据此直接取消静音
 * - 网页里点「🎤」开麦：这里把 WebView 的录音请求转成系统录音权限
 * - 横竖屏随手机转，返回键退出
 */
class SuiteWatchActivity : Activity() {
    companion object {
        private const val EXTRA_URL = "url"
        private const val REQ_MIC = 0x4d49

        fun open(ctx: Context, url: String) {
            ctx.startActivity(Intent(ctx, SuiteWatchActivity::class.java).putExtra(EXTRA_URL, url)
                .addFlags(if (ctx is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var web: WebView
    private var pendingPermission: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString + " NagisalandApp"
            webViewClient = object : WebViewClient() {
                // 只在这个页面里看；别的链接交给浏览器
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val u = req.url
                    val base = android.net.Uri.parse(url)
                    if (u.host == base.host) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Throwable) {}
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        if (!wantsMic) { request.deny(); return@runOnUiThread }
                        if (Build.VERSION.SDK_INT < 23 ||
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingPermission = request
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                        }
                    }
                }
            }
        }
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(web) })
        immersive()
        web.loadUrl(url)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val req = pendingPermission ?: return
        pendingPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        else req.deny()
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            try { web.loadUrl("about:blank"); web.destroy() } catch (_: Throwable) {}
        }
        super.onDestroy()
    }
}
