package io.github.kyosee.venera.companion

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CompanionApiException(val status: Int, message: String) : Exception(message)

object CompanionApi {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    fun server(value: String): String {
        val url = value.trim().trimEnd('/').toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "请输入不含账号、参数的 HTTPS 服务地址"
        }
        return url.toString().trimEnd('/')
    }
    @Synchronized fun call(c: Context, path: String, method: String = "GET", body: JSONObject? = null,
             endpoint: String? = null, anonymous: Boolean = false, generation: Long? = null): JSONObject {
        val config = CompanionStore.read(c)
        check(generation == null || generation == config.optLong("generation")) { "共享设置已更改，取消旧请求" }
        val url = server(endpoint ?: config.optString("server")) + "/v1/" + path
        val request = Request.Builder().url(url)
        if (!anonymous) request.header("Authorization", "Bearer " + config.optString("token"))
        if (method == "POST") request.post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        if (method == "DELETE") request.delete()
        client.newCall(request.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) throw CompanionApiException(response.code, result.optString("error", "服务器响应 ${response.code}"))
            return result
        }
    }
    fun event(c: Context, kind: String, text: String) = call(c, "events", "POST", JSONObject()
        .put("id", CompanionStore.id()).put("kind", kind).put("text", text))
}
