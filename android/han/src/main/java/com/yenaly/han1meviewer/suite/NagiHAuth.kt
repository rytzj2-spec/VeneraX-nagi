package com.yenaly.han1meviewer.suite

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * Nagisaland：H 分区额外的 Cookie / User-Agent（Cloudflare 验证和「粘贴 Cookie 登录」共用）。
 *
 * - [extraCookie]：「去验证」时从 WebView 取的 Cloudflare Cookie（cf_clearance 等），每个请求都带上。
 * - [userAgent]：Cloudflare 的 cf_clearance 和 UA 绑定，OkHttp 发的 UA 必须和过验证的 WebView（或你登录用的浏览器）一样。
 *   为空就用 H 原来的固定 UA（Constants.USER_AGENT）。
 */
object NagiHAuth {
    private var prefs: SharedPreferences? = null

    /** Cloudflare 相关的 Cookie 名（只有这些从 [extraCookie] 里带，登录 Cookie 走 H 原来的 loginCookie） */
    private fun isCfCookie(name: String) =
        name == "cf_clearance" || name == "__cf_bm" || name.startsWith("cf_chl") || name == "_cfuvid"

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("nagi_h_auth", Context.MODE_PRIVATE)
    }

    var extraCookie: String
        get() = prefs?.getString("extra_cookie", "") ?: ""
        set(v) { prefs?.edit()?.putString("extra_cookie", v)?.apply() }

    var userAgent: String?
        get() = prefs?.getString("user_agent", null)?.takeIf { it.isNotBlank() }
        set(v) { prefs?.edit()?.putString("user_agent", v?.trim().orEmpty())?.apply() }

    /** 保存 WebView 里拿到的 Cookie 字符串（只留 Cloudflare 的） */
    fun saveCfCookies(cookieHeader: String) {
        extraCookie = cookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') && isCfCookie(it.substringBefore('=').trim()) }
            .joinToString("; ")
    }

    fun hasCfClearance(cookieHeader: String?): Boolean =
        cookieHeader?.split(';')?.any { it.trim().startsWith("cf_clearance=") } == true

    /** 在 [base] 后面追加 Cloudflare Cookie（同名的用新的） */
    fun withExtraCookies(url: HttpUrl, base: List<Cookie>): List<Cookie> {
        val extra = extraCookie
        if (extra.isBlank()) return base
        val add = extra.split(';').mapNotNull { kv ->
            val name = kv.substringBefore('=').trim()
            val value = kv.substringAfter('=', "").trim()
            if (name.isEmpty()) null
            else Cookie.Builder().domain(url.host).path("/").name(name).value(value).build()
        }
        val names = add.map { it.name }.toSet()
        return base.filter { it.name !in names } + add
    }
}
