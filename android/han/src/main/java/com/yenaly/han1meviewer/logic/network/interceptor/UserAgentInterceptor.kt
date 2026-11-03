package com.yenaly.han1meviewer.logic.network.interceptor

import com.yenaly.han1meviewer.USER_AGENT
import okhttp3.Interceptor
import okhttp3.Response

object UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().addHeader(
            // Nagisaland：过了 Cloudflare 验证 / 粘贴 Cookie 登录时用的 UA（必须和那个 WebView / 浏览器一致）
            "User-Agent", com.yenaly.han1meviewer.suite.NagiHAuth.userAgent ?: USER_AGENT
        ).build()
        return chain.proceed(request)
    }
}