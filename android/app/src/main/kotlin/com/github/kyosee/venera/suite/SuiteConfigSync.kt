package io.github.kyosee.venera.suite

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 配置链接（自动更新）：导入的配置里带着 updateUrl（或者是从链接导入的），就记下这个链接。
 * 打开 App 时（主界面 onCreate）每 6 小时最多查一次：服务器上的 .nagi 变了（ETag / Last-Modified，再比 manifest 的 revision）
 * → 下载到 filesDir/config_pending.nagi；下次进程启动时 [applyPending]（SuiteStartup，任何分区启动之前）套用，不用重启。
 *
 * 链接放在一直在线的地方（GitHub / Gitee 的 raw 链接、自己的 NAS、网盘直链），手机当服务器不行（对方要更新时你的手机不一定在线）。
 */
object SuiteConfigSync {
    private const val PREFS = "suite_profile"
    private const val K_URL = "sub_url"
    private const val K_ETAG = "sub_etag"
    private const val K_LASTMOD = "sub_lastmod"
    private const val K_REV = "sub_rev"
    private const val K_CHECKED = "sub_checked"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val MAX_BYTES = 64L * 1024 * 1024

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun pendingFile(ctx: Context) = File(ctx.applicationContext.filesDir, "config_pending.nagi")

    fun url(ctx: Context): String? = prefs(ctx).getString(K_URL, null)?.takeIf { it.isNotBlank() }
    fun revision(ctx: Context): Long = prefs(ctx).getLong(K_REV, 0L)
    fun lastChecked(ctx: Context): Long = prefs(ctx).getLong(K_CHECKED, 0L)

    /** 导入成功后调用：记下版本号；带链接的话订阅它（链接变了就清掉旧的 ETag） */
    fun afterImport(ctx: Context, url: String?, revision: Long) {
        val e = prefs(ctx).edit().putLong(K_REV, revision)
        if (!url.isNullOrBlank()) {
            if (url != url(ctx)) e.remove(K_ETAG).remove(K_LASTMOD)
            e.putString(K_URL, url.trim()).putLong(K_CHECKED, System.currentTimeMillis())
        }
        e.commit()
    }

    fun unsubscribe(ctx: Context) {
        prefs(ctx).edit().remove(K_URL).remove(K_ETAG).remove(K_LASTMOD).apply()
        pendingFile(ctx).delete()
    }

    fun hasPending(ctx: Context) = pendingFile(ctx).isFile

    /** 下载一个 .nagi 到 [target]；返回 HTTP 状态码（304 = 没变） */
    fun download(url: String, target: File, etag: String? = null, lastModified: String? = null,
                 onHeaders: ((String?, String?) -> Unit)? = null): Int {
        var u = URL(url)
        var redirects = 0
        while (true) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Nagisaland")
                if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
                if (!lastModified.isNullOrBlank()) setRequestProperty("If-Modified-Since", lastModified)
            }
            try {
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = c.getHeaderField("Location") ?: throw IllegalStateException("跳转没有地址")
                    if (++redirects > 5) throw IllegalStateException("跳转太多次")
                    u = URL(u, loc)       // 允许 http ↔ https 之间跳（网盘直链常见）
                    continue
                }
                if (code == 304) return 304
                if (code != 200) throw IllegalStateException("服务器返回 $code")
                val len = c.contentLengthLong
                if (len > MAX_BYTES) throw IllegalStateException("文件太大（${len / 1024 / 1024} MB）")
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("文件太大")
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                onHeaders?.invoke(c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"))
                return 200
            } finally {
                c.disconnect()
            }
        }
    }

    /** 打开 App 时调用：订阅了链接、离上次检查超过 6 小时（或 [force]）就在后台查一次 */
    @JvmStatic
    fun checkInBackground(ctx: Context, force: Boolean = false, done: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val url = url(app) ?: run { done?.invoke("没有订阅配置链接"); return }
        if (!force && System.currentTimeMillis() - lastChecked(app) < CHECK_EVERY_MS) return
        io.execute {
            val msg = try {
                val p = prefs(app)
                val tmp = File(app.cacheDir, "config_check.nagi")
                var newEtag: String? = null
                var newLastMod: String? = null
                val code = download(url, tmp, p.getString(K_ETAG, null), p.getString(K_LASTMOD, null)) { e, l ->
                    newEtag = e; newLastMod = l
                }
                p.edit().putLong(K_CHECKED, System.currentTimeMillis()).apply()
                if (code == 304) {
                    "配置已经是最新的"
                } else {
                    val m = SuiteConfigPack.readManifest(app, Uri.fromFile(tmp))
                    p.edit().putString(K_ETAG, newEtag).putString(K_LASTMOD, newLastMod).apply()
                    if (m.revision > revision(app)) {
                        val pending = pendingFile(app)
                        if (!tmp.renameTo(pending)) { tmp.copyTo(pending, overwrite = true); tmp.delete() }
                        "配置「${m.name.ifBlank { "未命名" }}」有更新，下次打开 App 时自动生效"
                    } else {
                        tmp.delete()
                        "配置已经是最新的"
                    }
                }
            } catch (e: Throwable) {
                SuitePerf.error(app, "检查配置更新失败（$url）", e)
                "检查配置更新失败：${e.message}"
            }
            main.post {
                if (done != null) done(msg)
                else if (msg.contains("有更新")) Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 进程一启动（SuiteStartup，任何分区启动之前）：有下载好的新配置就套用 */
    @JvmStatic
    fun applyPending(ctx: Context) {
        val app = ctx.applicationContext
        val f = pendingFile(app)
        if (!f.isFile) return
        try {
            val msg = SuiteConfigPack.import(app, Uri.fromFile(f))
            main.post { Toast.makeText(app, "配置已自动更新。$msg", Toast.LENGTH_LONG).show() }
        } catch (e: Throwable) {
            SuitePerf.error(app, "自动套用新配置失败", e)
        } finally {
            f.delete()
        }
    }
}
