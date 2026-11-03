package io.github.kyosee.venera.suite

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 一键配置包（.nagi，就是一个 zip）：
 *
 * ```
 * manifest.json                  格式版本、配置名、包含哪些部分、打开哪些分区
 * comic/sources/<文件>           漫画源（App 目录 comic_source/ 下的 .js 和 .data；不带账号时 .data 只留 settings）
 * comic/settings.json            漫画设置（appdata.json 的 settings，去掉密码 / Key / 本机路径这类）
 * comic/db/<库>                  （可选）cookie.db = 登录；local_favorite.db、read_later.db = 收藏；history.db = 历史
 * anime/plugins.json             番剧规则（plugins/v2/plugins.json）
 * video/nagi_video.json          影视服务器和设置（不带账号时只有地址）
 * ```
 *
 * 文件都在 App 私有目录（filesDir，= Flutter 的 getApplicationSupportDirectory），原生这边直接读写；
 * 导入后重启 App，各分区启动时读到新文件（导入页在任何分区启动前就完成，不用重启）。
 */
object SuiteConfigPack {
    const val FORMAT = 1
    const val EXT = ".nagi"

    /** 导出选项（默认：只带配置，不带任何个人数据） */
    data class Options(
        var name: String = "",
        /** 配置链接：填了的话对方导入后会自动从这里更新（把以后导出的新文件上传到同一个地址） */
        var updateUrl: String = "",
        var comic: Boolean = true,
        var comicAccounts: Boolean = false,
        var comicFavorites: Boolean = false,
        var comicHistory: Boolean = false,
        var anime: Boolean = true,
        var video: Boolean = true,
        var videoAccounts: Boolean = false,
        var enabled: MutableSet<String> = SuiteNavConfig.ALL.toMutableSet(),
    )

    /** 读出来的清单（导入前给人看） */
    data class Manifest(
        val name: String,
        val createdAt: String,
        val parts: List<String>,
        val enabled: Set<String>,
        val hasPersonal: Boolean,
        /** 导出时间（毫秒）：自动更新时比大小 */
        val revision: Long,
        val updateUrl: String,
        val raw: JSONObject,
    )

    // 漫画设置里不能给别人的：密码、Key、账号、只对这台手机有效的路径和标记
    private val COMIC_SETTING_SKIP = setOf(
        "appLockCredential", "appLockType", "authorizationRequired",
        "webdav", "webdavBackupRetention", "webdavUseProxy",
        "imageTranslationLlmKey", "imageTranslationProviders", "imageTranslationHfEndpoint",
        "deviceId", "deviceSpecificSettings", "comicCacheDirectory", "proxy",
        "batteryOptimizationPrompted", "disclaimerConsented", "dataVersion",
        "comicSpecificSettings", "chapterOrderOverrides",
        "followUpdatesFolder", "followUpdatesFolders", "followUpdatesFoldersMigrated",
        "comicSourceLibrariesMigrated", "webdavComicLibrariesMigrated",
    )
    private val COMIC_DB_ACCOUNTS = listOf("cookie.db")
    private val COMIC_DB_FAVORITES = listOf("local_favorite.db", "read_later.db")
    private val COMIC_DB_HISTORY = listOf("history.db")

    private fun files(ctx: Context) = ctx.applicationContext.filesDir

    // ---------------------------------------------------------------- 导出

    fun export(ctx: Context, o: Options, out: OutputStream) {
        val f = files(ctx)
        val parts = JSONArray()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun putFile(name: String, file: File) {
                if (!file.isFile) return
                zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }

            if (o.comic) {
                parts.put("comic")
                File(f, "comic_source").listFiles()?.sortedBy { it.name }?.forEach { src ->
                    when {
                        src.name.endsWith(".js") -> putFile("comic/sources/${src.name}", src)
                        src.name.endsWith(".data") -> {
                            val bytes = if (o.comicAccounts) src.readBytes() else sanitizeSourceData(src.readText())
                            if (bytes != null) put("comic/sources/${src.name}", bytes)
                        }
                    }
                }
                val appdata = File(f, "appdata.json")
                if (appdata.isFile) {
                    try {
                        val settings = JSONObject(appdata.readText()).optJSONObject("settings") ?: JSONObject()
                        val keep = JSONObject()
                        for (k in settings.keys()) if (k !in COMIC_SETTING_SKIP) keep.put(k, settings.get(k))
                        put("comic/settings.json", keep.toString().toByteArray())
                    } catch (_: Throwable) {}
                }
                val dbs = mutableListOf<String>()
                if (o.comicAccounts) { dbs += COMIC_DB_ACCOUNTS; parts.put("comicAccounts") }
                if (o.comicFavorites) { dbs += COMIC_DB_FAVORITES; parts.put("comicFavorites") }
                if (o.comicHistory) { dbs += COMIC_DB_HISTORY; parts.put("comicHistory") }
                for (db in dbs) {
                    for (suffix in listOf("", "-wal")) putFile("comic/db/$db$suffix", File(f, "$db$suffix"))
                }
            }

            if (o.anime) {
                val plugins = File(f, "plugins/v2/plugins.json")
                if (plugins.isFile) { parts.put("anime"); putFile("anime/plugins.json", plugins) }
            }

            if (o.video) {
                val v = File(f, "nagi_video.json")
                if (v.isFile) {
                    parts.put("video")
                    if (o.videoAccounts) parts.put("videoAccounts")
                    try {
                        val j = JSONObject(v.readText())
                        if (!o.videoAccounts) {
                            val servers = j.optJSONArray("servers") ?: JSONArray()
                            for (i in 0 until servers.length()) {
                                val s = servers.optJSONObject(i) ?: continue
                                for (k in listOf("username", "userId", "token", "deviceId")) s.put(k, "")
                            }
                        }
                        put("video/nagi_video.json", j.toString().toByteArray())
                    } catch (_: Throwable) {}
                }
            }

            val m = JSONObject()
            m.put("app", "Nagisaland")
            m.put("format", FORMAT)
            m.put("name", o.name)
            m.put("revision", System.currentTimeMillis())
            if (o.updateUrl.isNotBlank()) m.put("updateUrl", o.updateUrl.trim())
            m.put("createdAt", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            m.put("fromVersion", try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Throwable) { "" })
            m.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            m.put("parts", parts)
            m.put("enabled", JSONArray(SuiteNavConfig.ALL.filter { it in o.enabled }))
            // 屏幕共享「经服务器」的服务器地址（朋友导入后不用自己填）
            SuiteRtcShare.server(ctx).takeIf { it.isNotBlank() }?.let { m.put("shareServer", it) }
            put("manifest.json", m.toString(2).toByteArray())
        }
    }

    /** 漫画源的 .data：只留 settings（account / token / cookies / _device… 都是个人的） */
    private fun sanitizeSourceData(text: String): ByteArray? = try {
        val j = JSONObject(text)
        val keep = JSONObject()
        j.optJSONObject("settings")?.let { keep.put("settings", it) }
        keep.toString().toByteArray()
    } catch (_: Throwable) { null }

    // ---------------------------------------------------------------- 导入

    fun readManifest(ctx: Context, uri: Uri): Manifest {
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "打不开这个文件" }
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "manifest.json") return parseManifest(JSONObject(String(zip.readBytes())))
                }
            }
        }
        throw IllegalArgumentException("这不是 Nagisaland 的配置文件（里面没有 manifest.json）")
    }

    private fun parseManifest(j: JSONObject): Manifest {
        require(j.optString("app") == "Nagisaland") { "这不是 Nagisaland 的配置文件" }
        require(j.optInt("format", 0) in 1..FORMAT) { "配置文件版本太新，先把 App 更新到最新版" }
        val parts = j.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val enabled = j.optJSONArray("enabled")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            ?: SuiteNavConfig.ALL.toSet()
        return Manifest(
            name = j.optString("name"),
            createdAt = j.optString("createdAt"),
            parts = parts,
            enabled = enabled,
            hasPersonal = parts.any { it in setOf("comicAccounts", "comicFavorites", "comicHistory", "videoAccounts") },
            revision = j.optLong("revision", 0L),
            updateUrl = j.optString("updateUrl"),
            raw = j,
        )
    }

    /** 导入：写文件 + 分区开关 + 订阅配置链接。[sourceUrl] = 从哪个链接下载的（配置里没写 updateUrl 时就订阅它）。返回导入了什么 */
    fun import(ctx: Context, uri: Uri, sourceUrl: String? = null): String {
        val manifest = readManifest(ctx, uri)
        val f = files(ctx)
        var sources = 0
        var dbs = 0
        var plugins = 0
        var servers = 0
        var comicSettings: JSONObject? = null

        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "打不开这个文件" }
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    // 只认这几个目录，文件名不许带路径（防止 ../ 写到别处）
                    when {
                        name.startsWith("comic/sources/") -> {
                            val fn = safeName(name.removePrefix("comic/sources/")) ?: continue
                            val dir = File(f, "comic_source").apply { mkdirs() }
                            val bytes = zip.readBytes()
                            val target = File(dir, fn)
                            if (fn.endsWith(".data") && target.isFile) {
                                writeAtomic(target, mergeSourceData(target.readText(), String(bytes)).toByteArray())
                            } else {
                                writeAtomic(target, bytes)
                            }
                            if (fn.endsWith(".js")) sources++
                        }
                        name == "comic/settings.json" -> comicSettings = JSONObject(String(zip.readBytes()))
                        name.startsWith("comic/db/") -> {
                            val fn = safeName(name.removePrefix("comic/db/")) ?: continue
                            val base = fn.removeSuffix("-wal")
                            if (base !in COMIC_DB_ACCOUNTS + COMIC_DB_FAVORITES + COMIC_DB_HISTORY) continue
                            if (!fn.endsWith("-wal")) {
                                // 换库之前先删掉旧库留下的 -wal / -shm，否则 SQLite 会把旧日志套到新库上
                                for (s in listOf("-wal", "-shm", "-journal")) File(f, "$base$s").delete()
                                dbs++
                            }
                            writeAtomic(File(f, fn), zip.readBytes())
                        }
                        name == "anime/plugins.json" -> {
                            val incoming = JSONArray(String(zip.readBytes()))
                            plugins = incoming.length()
                            val target = File(f, "plugins/v2/plugins.json").apply { parentFile?.mkdirs() }
                            writeAtomic(target, mergeByKey(target, incoming, "name").toString().toByteArray())
                        }
                        name == "video/nagi_video.json" -> {
                            val incoming = JSONObject(String(zip.readBytes()))
                            servers = incoming.optJSONArray("servers")?.length() ?: 0
                            val target = File(f, "nagi_video.json")
                            writeAtomic(target, mergeVideo(target, incoming).toString().toByteArray())
                        }
                    }
                }
            }
        }

        comicSettings?.let { incoming ->
            val target = File(f, "appdata.json")
            val j = try { if (target.isFile) JSONObject(target.readText()) else JSONObject() } catch (_: Throwable) { JSONObject() }
            val settings = j.optJSONObject("settings") ?: JSONObject()
            for (k in incoming.keys()) if (k !in COMIC_SETTING_SKIP) settings.put(k, incoming.get(k))
            j.put("settings", settings)
            // 漫画读 appdata.json 时 searchHistory 必须有，缺了会把整个文件当坏的删掉
            if (j.optJSONArray("searchHistory") == null) j.put("searchHistory", JSONArray())
            writeAtomic(target, j.toString().toByteArray())
        }

        SuiteProfile.applyImported(ctx, manifest.enabled, manifest.name)
        manifest.raw.optString("shareServer").takeIf { it.isNotBlank() }?.let { srv ->
            if (SuiteRtcShare.server(ctx).isBlank()) SuiteRtcShare.save(ctx, srv, SuiteRtcShare.password(ctx), SuiteRtcShare.quality(ctx))
        }
        SuiteConfigSync.afterImport(ctx, manifest.updateUrl.ifBlank { sourceUrl.orEmpty() }, manifest.revision)

        val done = mutableListOf<String>()
        if (sources > 0) done += "漫画源 $sources 个"
        if (dbs > 0) done += "漫画个人数据 $dbs 项"
        if (plugins > 0) done += "番剧规则 $plugins 条"
        if (servers > 0) done += "影视服务器 $servers 个"
        return if (done.isEmpty()) "已导入分区设置" else "已导入：" + done.joinToString("、")
    }

    private fun safeName(n: String): String? =
        n.takeIf { it.isNotEmpty() && !it.contains('/') && !it.contains('\\') && it != "." && it != ".." }

    /** 漫画源 .data：用导入的 settings，本机已有的账号等保留（自己导入自己的配置时不丢登录） */
    private fun mergeSourceData(existing: String, incoming: String): String = try {
        val old = JSONObject(existing)
        val new = JSONObject(incoming)
        for (k in new.keys()) old.put(k, new.get(k))
        old.toString()
    } catch (_: Throwable) { incoming }

    /** JSON 数组按某个字段合并：同名的用导入的，本机多出来的保留 */
    private fun mergeByKey(target: File, incoming: JSONArray, key: String): JSONArray {
        val old = try { if (target.isFile) JSONArray(target.readText()) else JSONArray() } catch (_: Throwable) { JSONArray() }
        val names = (0 until incoming.length()).mapNotNull { incoming.optJSONObject(it)?.optString(key) }.toSet()
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString(key) !in names) out.put(o)
        }
        for (i in 0 until incoming.length()) out.put(incoming.get(i))
        return out
    }

    /** 影视：服务器按地址合并；导入的没带账号、本机这个地址已经登录过的，保留本机的登录 */
    private fun mergeVideo(target: File, incoming: JSONObject): JSONObject {
        val old = try { if (target.isFile) JSONObject(target.readText()) else JSONObject() } catch (_: Throwable) { JSONObject() }
        val oldServers = old.optJSONArray("servers") ?: JSONArray()
        val newServers = incoming.optJSONArray("servers") ?: JSONArray()
        val byUrl = LinkedHashMap<String, JSONObject>()
        for (i in 0 until oldServers.length()) oldServers.optJSONObject(i)?.let { byUrl[it.optString("url")] = it }
        for (i in 0 until newServers.length()) {
            val s = newServers.optJSONObject(i) ?: continue
            val url = s.optString("url")
            val prev = byUrl[url]
            if (prev != null && s.optString("token").isEmpty() && prev.optString("token").isNotEmpty()) {
                s.put("username", prev.optString("username")); s.put("userId", prev.optString("userId"))
                s.put("token", prev.optString("token")); s.put("deviceId", prev.optString("deviceId"))
                s.put("id", prev.optString("id"))
            }
            byUrl[url] = s
        }
        val out = JSONObject()
        out.put("servers", JSONArray(byUrl.values.toList()))
        out.put("settings", incoming.optJSONObject("settings") ?: old.optJSONObject("settings") ?: JSONObject())
        return out
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".nagi-tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) { target.writeBytes(bytes); tmp.delete() }
        }
    }
}
