package com.yenaly.han1meviewer.ui.activity

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.text.parseAsHtml
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.yenaly.han1meviewer.HANIME_ALTER_BASE_URL
import com.yenaly.han1meviewer.HANIME_LOGIN_URL
import com.yenaly.han1meviewer.HANIME_MAIN_BASE_URL
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.databinding.ActivityLoginBinding
import com.yenaly.han1meviewer.logic.NetworkRepo
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.login
import com.yenaly.han1meviewer.util.showAlertDialog
import com.yenaly.han1meviewer.util.createAlertDialog
import com.yenaly.han1meviewer.util.showWithBlurEffect
import com.yenaly.yenaly_libs.base.frame.FrameActivity
import com.yenaly.yenaly_libs.utils.showShortToast
import com.yenaly.yenaly_libs.utils.unsafeLazy
import kotlinx.coroutines.launch

class LoginActivity : FrameActivity() {

    companion object {
        const val HIDE_GOOGLE_JS = "(function(){document.querySelectorAll('a,button,div[role=button]').forEach(function(e){" +
            "var s=((e.href||'')+' '+(e.getAttribute('onclick')||'')+' '+(e.textContent||'')).toLowerCase();" +
            "if(s.indexOf('google')>=0){e.style.display='none';}});})()"
        const val TAG = "LoginActivity"

        private const val HL = """<span style="color: #FF0000;"><b>H</b></span><b>a1</b>ogin"""
        // val hlSpannedTitle = HL.parseAsHtml()
    }

    private lateinit var binding: ActivityLoginBinding

    private val dialog = unsafeLazy { LoginDialog(R.layout.dialog_login) }

    override fun setUiStyle() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_login)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        supportActionBar?.let {
            it.title = HL.parseAsHtml()
            it.setDisplayHomeAsUpEnabled(true)
            it.setHomeActionContentDescription(R.string.back)
        }
        initWebView()
        // Nagisaland：粘贴 Cookie 登录（Google 账号在 WebView 里登录不了）
        binding.toolbar.menu.add("Cookie 登录").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { showCookieLoginDialog(); true }
        }
        binding.srlLogin.setOnRefreshListener {
            binding.wvLogin.loadUrl(HANIME_LOGIN_URL)
        }
        binding.srlLogin.autoRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (dialog.isInitialized()) {
            dialog.value.dismiss()
        }
        binding.wvLogin.removeAllViews()
        binding.wvLogin.destroy()
        binding.unbind()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.wvLogin.canGoBack()) {
            binding.wvLogin.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        binding.wvLogin.apply {
            // #issue-17: 谷歌登录需要开启JavaScript，但是谷歌拒絕這種登錄方式，遂放棄
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = com.yenaly.han1meviewer.suite.NagiHAuth.userAgent ?: USER_AGENT

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    binding.srlLogin.finishRefresh()
                    // Nagisaland：Google 禁止在 WebView 里登录（「此浏览器或应用可能不安全」），把 Google 登录按钮藏起来
                    view.evaluateJavascript(HIDE_GOOGLE_JS, null)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (request.url.host?.contains("accounts.google.") == true) {
                        showGoogleHint()
                        return true
                    }
                    val isSameUrl = request.url.toString() == HANIME_MAIN_BASE_URL ||
                            request.url.toString() == HANIME_ALTER_BASE_URL
                    if (request.isRedirect && isSameUrl) {
                        val url = request.url
                        val cookieManager = CookieManager.getInstance().getCookie(url.host)
                        Log.d("login_cookie", cookieManager.toString())
                        login(cookieManager)
                        setResult(RESULT_OK)
                        finish()
                        return true
                    }
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    // #issue-146
                    // #issue-160: 修复字段销毁后调用引发的错误
                    if (!isDestroyed && !isFinishing) {
                        binding.srlLogin.finishRefresh()
                        dialog.value.show()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Nagisaland

    private fun showGoogleHint() {
        showAlertDialog {
            setTitle("不能用 Google 登录")
            setMessage("Google 禁止在 App 内的网页里登录（会提示「此浏览器或应用可能不安全」），这个绕不过。\n\n" +
                "请用邮箱 + 密码登录；用 Google 注册的账号，先在手机浏览器里打开 hanime1，用「忘记密码」给账号设一个密码。\n\n" +
                "或者：在浏览器里登录 hanime1 后，用右上角「Cookie 登录」把浏览器的 Cookie 粘贴进来。")
            setPositiveButton("知道了", null)
        }
    }

    /** 粘贴浏览器里 hanime1 的 Cookie（和浏览器的 User-Agent），写进 H 的登录 Cookie；和 Cloudflare 验证共用同一套 UA 设置 */
    private fun showCookieLoginDialog() {
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val etCookie = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Cookie（整段粘贴，例：XSRF-TOKEN=…; hanime1_session=…）"
            minLines = 3
        }
        val etUa = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "浏览器的 User-Agent（必填，要和登录用的浏览器一样）"
            setText(com.yenaly.han1meviewer.suite.NagiHAuth.userAgent.orEmpty())
            minLines = 2
        }
        val tip = android.widget.TextView(this).apply {
            text = "怎么拿：电脑浏览器登录 hanime1 → F12 → 网络 → 随便点一个 hanime1 的请求 → 请求标头里的 Cookie 和 User-Agent 整段复制。" +
                "手机上可以用 Kiwi 等支持开发者工具的浏览器。Cloudflare 的 cf_clearance 也和 UA 绑定，所以 UA 必须一致。"
            textSize = 12f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        box.addView(etCookie)
        box.addView(etUa)
        box.addView(tip)
        showAlertDialog {
            setTitle("粘贴 Cookie 登录")
            setView(box)
            setPositiveButton(R.string.login) { _, _ ->
                val cookie = etCookie.text?.toString().orEmpty().trim().removePrefix("Cookie:").trim()
                val ua = etUa.text?.toString().orEmpty().trim().removePrefix("User-Agent:").trim()
                if (!cookie.contains('=')) {
                    showShortToast("Cookie 格式不对")
                    return@setPositiveButton
                }
                if (ua.isEmpty()) {
                    showShortToast("请填浏览器的 User-Agent")
                    return@setPositiveButton
                }
                com.yenaly.han1meviewer.suite.NagiHAuth.userAgent = ua
                if (com.yenaly.han1meviewer.suite.NagiHAuth.hasCfClearance(cookie)) {
                    com.yenaly.han1meviewer.suite.NagiHAuth.saveCfCookies(cookie)
                }
                login(cookie)
                setResult(RESULT_OK)
                finish()
            }
            setNeutralButton("恢复默认 UA") { _, _ ->
                com.yenaly.han1meviewer.suite.NagiHAuth.userAgent = null
                com.yenaly.han1meviewer.suite.NagiHAuth.extraCookie = ""
                showShortToast("已恢复 H 默认的 User-Agent，并清掉验证 Cookie")
            }
            setNegativeButton(R.string.cancel, null)
        }
    }

    inner class LoginDialog(@LayoutRes layoutRes: Int) {
        private val etUsername: TextInputEditText
        private val etPassword: TextInputEditText

        private val dialog: AlertDialog

        private val username get() = etUsername.text?.toString().orEmpty()
        private val password get() = etPassword.text?.toString().orEmpty()

        init {
            val view = View.inflate(this@LoginActivity, layoutRes, null)
            etUsername = view.findViewById(R.id.et_username)
            etPassword = view.findViewById(R.id.et_password)
            dialog = createAlertDialog {
                setView(view)
                setCancelable(false)
                setTitle(R.string.try_login_here)
                setPositiveButton(R.string.login, null)
                setNegativeButton(R.string.cancel, null)
            }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    handleLogin()
                }
            }
        }

        private fun handleLogin() {
            lifecycleScope.launch {
                NetworkRepo.login(username, password).collect { state ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                        state !is WebsiteState.Loading
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled =
                        state !is WebsiteState.Loading
                    when (state) {
                        WebsiteState.Loading -> Unit

                        is WebsiteState.Error -> {
                            state.throwable.printStackTrace()
                            if (state.throwable is IllegalStateException) {
                                showShortToast(R.string.account_or_password_wrong)
                            } else {
                                showShortToast(R.string.login_failed)
                            }
                        }

                        is WebsiteState.Success -> {
                            login(state.info)
                            setResult(RESULT_OK)
                            dialog.dismiss()
                            showShortToast(R.string.login_success)
                            finish()
                        }
                    }
                }
            }
        }

        fun show() {
            dialog.showWithBlurEffect()
        }

        fun dismiss() {
            dialog.dismiss()
        }
    }
}