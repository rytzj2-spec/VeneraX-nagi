package com.yenaly.han1meviewer.suite

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.yenaly.han1meviewer.HANIME_BASE_URL

/**
 * Nagisaland：hanime1 的 Cloudflare 人机验证。App 内 WebView 打开网站，过了验证（拿到 cf_clearance、页面不再是
 * 「Just a moment」）后，把 Cloudflare Cookie 和这个 WebView 的 User-Agent 存进 [NagiHAuth]（H 的 OkHttp 都会带上），
 * 然后自动关闭，调用方刷新。
 */
class CfVerifyActivity : Activity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    private val poll = object : Runnable {
        override fun run() {
            if (done || isFinishing) return
            check()
            handler.postDelayed(this, 800)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F12"))
            fitsSystemWindows = true
        }
        val tip = TextView(this).apply {
            text = "正在打开 hanime1 做人机验证…\n出现勾选框就点一下，过了之后会自动返回。"
            setTextColor(Color.parseColor("#FAFAF9"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        web = WebView(this)
        root.addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // 不改 UA：用 WebView 自己的（之后 OkHttp 跟着用这个）
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = check()
        }
        web.loadUrl(HANIME_BASE_URL)
        handler.postDelayed(poll, 1500)
    }

    private fun check() {
        if (done) return
        val cookie = CookieManager.getInstance().getCookie(HANIME_BASE_URL)
        val title = web.title.orEmpty()
        if (!NagiHAuth.hasCfClearance(cookie)) return
        if (title.contains("Just a moment", true) || title.contains("请稍候") || title.contains("請稍候")) return
        done = true
        CookieManager.getInstance().flush()
        NagiHAuth.saveCfCookies(cookie!!)
        NagiHAuth.userAgent = web.settings.userAgentString
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
