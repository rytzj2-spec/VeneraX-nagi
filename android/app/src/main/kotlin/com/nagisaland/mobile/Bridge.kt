package com.nagisaland.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 网页里的 window.NagisaNative（core.js：`const N = window.NagisaNative`）。
 * 方法名、参数、返回值和原来的 smali 版完全一致，启动器网页不用改。
 */
class Bridge(private val activity: MainActivity) {
    companion object {
        fun errJson(t: Throwable): String = JSONObject().put("status", 0).put("error", t.toString()).toString()

        private fun entry(f: File) = JSONObject()
            .put("n", f.name).put("d", f.isDirectory).put("s", f.length()).put("m", f.lastModified())

        /** 删除整个文件夹只允许 /storage/ 下、至少 5 层深的路径，防止误删 */
        private fun safeTree(p: String?): Boolean =
            p != null && p.startsWith("/storage/") && !p.contains("..") && !p.endsWith("/") && p.split("/").size >= 6
    }

    // ---------- 文件 ----------
    @JavascriptInterface fun storageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    @JavascriptInterface fun exists(path: String?): Boolean = path != null && File(path).exists()

    @JavascriptInterface fun mkdirs(path: String?): Boolean = path != null && File(path).mkdirs()

    @JavascriptInterface
    fun listDir(path: String?): String {
        val files = path?.let { File(it).listFiles() } ?: return "null"
        val arr = JSONArray()
        for (f in files) arr.put(entry(f))
        return arr.toString()
    }

    @JavascriptInterface
    fun readText(path: String?): String? {
        if (path == null) return null
        return try {
            val input = activity.contentResolver.openInputStream(Uri.fromFile(File(path))) ?: return null
            String(GameClient.readAll(input), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    @JavascriptInterface
    fun rename(from: String?, to: String?): Boolean {
        if (from == null || to == null) return false
        val dst = File(to)
        return !dst.exists() && File(from).renameTo(dst)
    }

    @JavascriptInterface
    fun deleteFile(path: String?): Boolean {
        val f = path?.let { File(it) } ?: return false
        return f.isFile && f.delete()
    }

    @JavascriptInterface
    fun deleteTree(path: String?): Int {
        if (!safeTree(path)) return -1
        return try { Ops.deleteTree(File(path!!)) } catch (e: Throwable) { -1 }
    }

    /** 后台删除，结果（删掉的个数，失败 -1）用 httpResult(id) 取 */
    @JavascriptInterface
    fun deleteTreeStart(id: String?, path: String?): Boolean {
        if (!safeTree(path)) return false
        Thread(DeleteJob(id, path!!)).start()
        return true
    }

    @JavascriptInterface fun head(path: String?, n: Int): String? = try { Ops.head(path!!, n) } catch (e: Throwable) { null }

    @JavascriptInterface
    fun readB64(path: String?, offset: Double, n: Int): String? = try { Ops.readB64(path!!, offset, n) } catch (e: Throwable) { null }

    @JavascriptInterface
    fun writeB64(path: String?, b64: String?): Boolean = try { Ops.writeB64(path!!, b64!!); true } catch (e: Throwable) { false }

    @JavascriptInterface
    fun copyRange(src: String?, offset: Double, len: Double, dst: String?): Boolean =
        try { Ops.copyRange(src!!, offset, len, dst!!) } catch (e: Throwable) { false }

    @JavascriptInterface
    fun zipList(path: String?, charset: String?): String? = try { Ops.zipList(path!!, charset!!) } catch (e: Throwable) { null }

    @JavascriptInterface
    fun zipExtract(path: String?, charset: String?, name: String?, dst: String?): Boolean =
        try { Ops.zipExtract(path!!, charset!!, name!!, dst!!) } catch (e: Throwable) { false }

    // ---------- 7-Zip ----------
    @JavascriptInterface fun p7Ok(): Boolean = Ops.p7Path(activity).isNotEmpty()

    @JavascriptInterface
    fun p7Start(argsJson: String?, logPath: String?, stdoutToPipe: Boolean): String =
        try { Ops.p7Start(activity, argsJson!!, logPath!!, stdoutToPipe); "" } catch (e: Throwable) { e.toString() }

    @JavascriptInterface
    fun p7Pipe(path: String?, len: Double, append: Boolean): Double = try { Ops.p7Pipe(path!!, len, append) } catch (e: Throwable) { -1.0 }

    @JavascriptInterface fun p7Exit(): Int = Ops.p7Exit()

    @JavascriptInterface fun p7Kill() = Ops.p7Kill()

    // ---------- 网络 ----------
    @JavascriptInterface
    fun http(method: String?, url: String?, headers: String?, body: String?): String =
        try { Ops.http(method!!, url!!, headers, body).toString() } catch (e: Throwable) { errJson(e) }

    /** 后台请求，结果用 httpResult(id) 取 */
    @JavascriptInterface
    fun httpStart(id: String?, method: String?, url: String?, headers: String?, body: String?): Boolean {
        Thread(HttpJob(id, method, url, headers, body)).start()
        return true
    }

    @JavascriptInterface fun httpResult(id: String?): String? = id?.let { HttpJob.RESULTS.remove(it) }

    @JavascriptInterface
    fun download(url: String?, dst: String?): String = try { Ops.download(url!!, dst!!); "" } catch (e: Throwable) { e.toString() }

    // ---------- 游戏 ----------
    /** 给游戏分配虚拟域名 g<哈希>.nagisaland.game，返回域名 */
    @JavascriptInterface
    fun setGame(dir: String?, engine: String?, key: String?): String {
        val host = "g" + Integer.toHexString((key ?: dir)!!.hashCode()) + ".nagisaland.game"
        if (dir != null) MainActivity.GAMES[host] = dir
        if (engine != null) MainActivity.ENGINES[host] = engine
        return host
    }

    @JavascriptInterface fun gameDir(host: String?): String? = host?.let { MainActivity.GAMES[it] }

    @JavascriptInterface
    fun setOrientation(o: Int) {
        try { activity.requestedOrientation = o } catch (_: Exception) {}
    }

    // ---------- 应用 / 系统 ----------
    @JavascriptInterface fun sdk(): Int = Build.VERSION.SDK_INT

    @JavascriptInterface fun version(): String = "0.9.0"

    @JavascriptInterface
    fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 11+ 打开「所有文件访问权限」设置页（失败退回总设置页），10 及以下弹运行时权限 */
    @JavascriptInterface
    fun requestStorage() {
        if (Build.VERSION.SDK_INT < 30) {
            try {
                activity.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            } catch (_: Exception) {}
            return
        }
        try {
            activity.startActivity(
                Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + activity.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun copyText(text: String?): Boolean = try {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Nagisaland", text))
        true
    } catch (e: Exception) { false }

    @JavascriptInterface
    fun readClip(): String = try {
        val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        if (clip != null && clip.itemCount != 0) clip.getItemAt(0).coerceToText(activity)?.toString() ?: "" else ""
    } catch (e: Throwable) { "" }

    @JavascriptInterface
    fun openUrl(url: String?) {
        if (url == null) return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun openWith(path: String?, mime: String?, pkg: String?): String =
        try { Ops.open(activity, path!!, mime, pkg); "" } catch (e: Throwable) { e.toString() }

    @JavascriptInterface
    fun launchIntent(json: String?): String = try { Ops.launchIntent(activity, json!!); "" } catch (e: Throwable) { e.toString() }

    @JavascriptInterface
    fun launchApp(pkg: String?): Boolean {
        val i = pkg?.let { activity.packageManager.getLaunchIntentForPackage(it) } ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { activity.startActivity(i); true } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun listApps(): String {
        val pm = activity.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val arr = JSONArray()
        for (ri in list) arr.put(JSONObject().put("p", ri.activityInfo.packageName).put("l", ri.loadLabel(pm).toString()))
        return arr.toString()
    }

    @JavascriptInterface fun pkgs(filter: String?): String = try { Ops.pkgs(activity, filter!!) } catch (e: Throwable) { "[]" }

    @JavascriptInterface fun appIcon(pkg: String?): String? = try { Ops.appIcon(activity, pkg!!) } catch (e: Throwable) { null }

    @JavascriptInterface
    fun installBundled(file: String?): String = try { Ops.install(activity, file!!); "" } catch (e: Throwable) { e.toString() }

    @JavascriptInterface
    fun uninstall(pkg: String?): Boolean = try { Ops.uninstall(activity, pkg!!); true } catch (e: Throwable) { false }

    // ---------- 分区栏（和原生 SuiteNavBar 共用 SuiteNavConfig） ----------
    @JavascriptInterface fun navConfig(): String = io.github.kyosee.venera.suite.SuiteNavConfig.toJson(activity)

    @JavascriptInterface
    fun openMore(current: String?) {
        activity.runOnUiThread {
            // 「更多」是独立页面
            activity.startActivity(android.content.Intent(activity, io.github.kyosee.venera.suite.SuiteMoreActivity::class.java))
        }
    }

    /** 「流畅度设置」面板（左滑主界面底栏时，游戏库以外的页面打开它） */
    @JavascriptInterface
    fun suiteSettings() {
        activity.runOnUiThread { io.github.kyosee.venera.suite.SuitePanels.showSettings(activity) }
    }

    @JavascriptInterface
    fun editNav() {
        activity.runOnUiThread { io.github.kyosee.venera.suite.SuitePanels.showEditor(activity) }
    }

    // ---------- 识屏翻译 ----------
    @JavascriptInterface fun ocrStart(): String = OcrOverlay.start(activity)

    @JavascriptInterface fun ocrStop() = OcrOverlay.stop(activity)

    @JavascriptInterface fun ocrRunning(): Boolean = OcrOverlay.isRunning()
}
