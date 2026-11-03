package com.nagisaland.mobile

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 主界面 WebView 的请求映射：
 * - app.nagisaland.game → assets/launcher/（启动器网页）
 * - g<哈希>.nagisaland.game → 游戏文件夹（[MainActivity.GAMES]），index.html 里插入 bridge-<引擎>.js
 * - 任意 *.nagisaland.game/__nagisaland/xxx → assets/xxx
 * - galport:// → 退出 / 回启动器；其它外部链接交给系统打开
 */
class GameClient(private val activity: MainActivity) : WebViewClient() {
    companion object {
        private val CORS = hashMapOf("Access-Control-Allow-Origin" to "*")

        fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", CORS, null)

        @JvmStatic
        fun readAll(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            input.close()
            return out.toByteArray()
        }
    }

    private fun asset(path: String): WebResourceResponse {
        val input = try { activity.assets.open(path) } catch (e: Exception) { return notFound() }
        return WebResourceResponse(MainActivity.mimeFor(path), "UTF-8", input).apply { responseHeaders = CORS }
    }

    private fun openFile(f: File): InputStream? =
        try { activity.contentResolver.openInputStream(Uri.fromFile(f)) } catch (e: Exception) { null }

    private fun index(f: File, host: String): WebResourceResponse {
        val input = openFile(f) ?: return notFound()
        val html = try { String(readAll(input), Charsets.UTF_8) } catch (e: Exception) { return notFound() }
        val engine = MainActivity.ENGINES[host] ?: "generic"
        val tag = "<script src=\"/__nagisaland/bridge-$engine.js\"></script>\n"
        val at = html.indexOf("<script").let { if (it < 0) 0 else it }
        val out = html.substring(0, at) + tag + html.substring(at)
        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(out.toByteArray(Charsets.UTF_8))).apply {
            responseHeaders = CORS
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host
        if (host == null || !host.endsWith(".nagisaland.game")) return null
        var path = url.path
        if (path == null || path == "/") path = "/index.html"
        if (host == "app.nagisaland.game") return asset("launcher$path")
        if (path.startsWith("/__nagisaland/")) return asset(path.substring(14))
        val dir = MainActivity.GAMES[host] ?: return notFound()
        val f = File(dir, path.substring(1))
        if (path == "/index.html") return index(f, host)
        if (!f.isFile) return notFound()
        val input = openFile(f) ?: return notFound()
        return WebResourceResponse(MainActivity.mimeFor(path), "UTF-8", input).apply { responseHeaders = CORS }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme == "galport") {
            val cur = view.url
            if (cur != null && cur.contains("//app.nagisaland.game")) activity.exitToBackground()
            else activity.goHome()
            return true
        }
        val host = url.host
        if (host != null && host.endsWith(".nagisaland.game")) return false
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        return true
    }
}
