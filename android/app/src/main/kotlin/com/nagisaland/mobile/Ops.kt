package com.nagisaland.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** [Bridge] 背后的实际操作（原 Ops.smali） */
object Ops {
    private var proc: Process? = null
    private var procOut: InputStream? = null
    private var zipFile: ZipFile? = null
    private var zipKey: String? = null

    /** 给别的 App 用的临时授权链接（[Files] 提供） */
    fun contentUri(ctx: Context, path: String): Uri =
        Uri.parse("content://" + ctx.packageName + ".files" + Uri.encode(path, "/"))

    fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(65536)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        input.close()
        out.close()
    }

    fun deleteTree(f: File): Int {
        var count = 0
        // 只进入真正的子文件夹（规范路径 = 父目录/名字），不跟着符号链接删到别处
        val children = if (f.isDirectory && f.parentFile != null &&
            f.canonicalPath == f.parentFile!!.canonicalPath + "/" + f.name) f.listFiles() else null
        if (children != null) for (c in children) count += deleteTree(c)
        if (f.delete()) count++
        return count
    }

    fun head(path: String, n: Int): String {
        val buf = ByteArray(n)
        val read = FileInputStream(path).use { it.read(buf) }
        val sb = StringBuilder()
        for (i in 0 until read) sb.append(Integer.toHexString((buf[i].toInt() and 0xff) or 0x100).substring(1))
        return sb.toString()
    }

    /** offset < 0 表示从文件末尾倒数 */
    fun readB64(path: String, offset: Double, n: Int): String {
        RandomAccessFile(path, "r").use { raf ->
            var pos = offset.toLong()
            if (pos < 0) pos = maxOf(0L, pos + raf.length())
            raf.seek(pos)
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = raf.read(buf, got, n - got)
                if (r <= 0) break
                got += r
            }
            return Base64.encodeToString(buf, 0, got, Base64.NO_WRAP)
        }
    }

    fun writeB64(path: String, b64: String) {
        val data = Base64.decode(b64, Base64.DEFAULT)
        val f = File(path)
        f.parentFile?.mkdirs()
        val tmp = File("$path.tmp")
        FileOutputStream(tmp).use { it.write(data) }
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun copyRange(src: String, offset: Double, len: Double, dst: String): Boolean {
        val raf = RandomAccessFile(src, "r")
        raf.seek(offset.toLong())
        val out = File(dst)
        out.parentFile?.mkdirs()
        val fos = FileOutputStream(out)
        val buf = ByteArray(65536)
        var left = len.toLong()
        while (left > 0) {
            val r = raf.read(buf, 0, if (left < 65536) left.toInt() else 65536)
            if (r <= 0) break
            fos.write(buf, 0, r)
            left -= r
        }
        raf.close()
        fos.close()
        return left == 0L
    }

    // ---------- zip（GBK 文件名的 zip 由 App 自己读文件名） ----------
    @Synchronized
    private fun zip(path: String, charset: String): ZipFile {
        val key = "$path|$charset"
        zipKey?.let { k ->
            if (k == key) zipFile?.let { return it }
            else {
                val old = zipFile
                zipKey = null
                zipFile = null
                old?.close()
            }
        }
        val z = ZipFile(File(path), ZipFile.OPEN_READ, Charset.forName(charset))
        zipFile = z
        zipKey = key
        return z
    }

    fun zipList(path: String, charset: String): String {
        val arr = JSONArray()
        for (e in zip(path, charset).entries()) {
            if (!e.isDirectory) arr.put(JSONArray().put(e.name).put(e.size))
        }
        return arr.toString()
    }

    fun zipExtract(path: String, charset: String, name: String, dst: String): Boolean {
        val z = zip(path, charset)
        val e = z.getEntry(name) ?: return false
        val input = z.getInputStream(e)
        val f = File(dst)
        f.parentFile?.mkdirs()
        copy(input, FileOutputStream(f))
        return true
    }

    // ---------- 7-Zip（lib7zz.so 是可执行文件，从 nativeLibraryDir 运行） ----------
    fun p7Path(ctx: Context): String {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "lib7zz.so")
        return if (f.exists()) f.absolutePath else ""
    }

    /** stdoutToPipe：true 时 stdout 留给 [p7Pipe] 读（解压到管道），stderr 写日志；false 时全部写日志 */
    fun p7Start(ctx: Context, argsJson: String, logPath: String, stdoutToPipe: Boolean) {
        p7Kill()
        val exe = p7Path(ctx)
        if (exe.isEmpty()) throw IOException("no 7zz")
        val cmd = arrayListOf(exe)
        val args = JSONArray(argsJson)
        for (i in 0 until args.length()) cmd.add(args.getString(i))
        val pb = ProcessBuilder(cmd)
        pb.environment()["LC_ALL"] = "C.UTF-8"
        val log = File(logPath)
        log.parentFile?.mkdirs()
        if (stdoutToPipe) pb.redirectError(log)
        else pb.redirectErrorStream(true).redirectOutput(log)
        val p = pb.start()
        proc = p
        procOut = p.inputStream
        p.outputStream.close()
    }

    /** 从 7-Zip 的 stdout 读 len 字节写进文件，返回实际写入的字节数 */
    fun p7Pipe(path: String, len: Double, append: Boolean): Double {
        val input = procOut ?: throw IOException("no process")
        val f = File(path)
        f.parentFile?.mkdirs()
        val out = FileOutputStream(f, append)
        val buf = ByteArray(65536)
        val total = len.toLong()
        var done = 0L
        while (done < total) {
            val left = total - done
            val r = input.read(buf, 0, if (left < 65536) left.toInt() else 65536)
            if (r <= 0) break
            out.write(buf, 0, r)
            done += r
        }
        out.close()
        return done.toDouble()
    }

    /** 退出码；-1 还在运行，-2 没有进程 */
    fun p7Exit(): Int {
        val p = proc ?: return -2
        return try { p.exitValue() } catch (e: IllegalThreadStateException) { -1 }
    }

    fun p7Kill() {
        proc?.destroy()
        proc = null
        procOut = null
    }

    // ---------- 网络（小站接口走原生，避开跨域） ----------
    fun http(method: String, url: String, headers: String?, body: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 45000
        c.requestMethod = method
        if (headers != null) {
            val h = JSONObject(headers)
            for (k in h.keys()) c.setRequestProperty(k, h.getString(k))
        }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = if (stream != null) String(GameClient.readAll(stream), Charsets.UTF_8) else ""
        c.disconnect()
        return JSONObject().put("status", code).put("body", text)
    }

    fun download(url: String, dst: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Nagisaland-Mobile")
        val part = "$dst.part"
        copy(c.inputStream, FileOutputStream(part))
        c.disconnect()
        val f = File(dst)
        f.delete()
        File(part).renameTo(f)
    }

    // ---------- 应用 ----------
    fun open(ctx: Context, path: String, mime: String?, pkg: String?) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(ctx, path), mime)
        if (pkg != null) i.setPackage(pkg)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    }

    /** {action, data, content, mime, pkg, cls, extras} */
    fun launchIntent(ctx: Context, json: String) {
        val o = JSONObject(json)
        val i = Intent(o.optString("action", Intent.ACTION_VIEW))
        val data = o.optString("data", "")
        if (data.isNotEmpty()) {
            val uri = if (o.optBoolean("content", false)) contentUri(ctx, data) else Uri.parse(data)
            val mime = o.optString("mime", "")
            if (mime.isNotEmpty()) i.setDataAndType(uri, mime) else i.data = uri
        }
        val pkg = o.optString("pkg", "")
        val cls = o.optString("cls", "")
        if (cls.isNotEmpty()) i.setClassName(pkg, cls) else if (pkg.isNotEmpty()) i.setPackage(pkg)
        o.optJSONObject("extras")?.let { ex -> for (k in ex.keys()) i.putExtra(k, ex.getString(k)) }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    }

    /** 安装内置模拟器：assets/emulators/<file> 先复制到缓存再交给系统安装器 */
    fun install(ctx: Context, file: String) {
        val dir = File(ctx.cacheDir, "emulators")
        dir.mkdirs()
        val f = File(dir, file)
        if (!f.exists()) {
            val part = File(dir, "$file.part")
            copy(ctx.assets.open("emulators/$file"), FileOutputStream(part))
            part.renameTo(f)
        }
        open(ctx, f.absolutePath, "application/vnd.android.package-archive", null)
    }

    fun uninstall(ctx: Context, pkg: String) {
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun pkgs(ctx: Context, filter: String): String {
        val pm = ctx.packageManager
        val arr = JSONArray()
        for (ai in pm.getInstalledApplications(0)) {
            if (ai.packageName.contains(filter)) arr.put(JSONObject().put("p", ai.packageName).put("l", ai.loadLabel(pm).toString()))
        }
        return arr.toString()
    }

    fun appIcon(ctx: Context, pkg: String): String {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 144, 144)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
