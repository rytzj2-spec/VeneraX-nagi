package com.nagisaland.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * content://<包名>.files/<绝对路径>：安装内置模拟器、用 ZArchiver 等打开压缩包时临时授权给对方 App（只读）。
 */
class Files : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String {
        val p = uri.path?.lowercase() ?: return "application/octet-stream"
        return when {
            p.endsWith(".apk") -> "application/vnd.android.package-archive"
            p.endsWith(".7z") -> "application/x-7z-compressed"
            p.endsWith(".zip") -> "application/zip"
            p.endsWith(".rar") -> "application/x-rar-compressed"
            else -> "application/octet-stream"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = File(uri.path!!)
        return MatrixCursor(arrayOf("_display_name", "_size")).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** 后台 HTTP 请求；结果按 id 放进 [RESULTS]，网页轮询 httpResult(id) 取走 */
class HttpJob(
    private val id: String?, private val method: String?, private val url: String?,
    private val headers: String?, private val body: String?,
) : Runnable {
    companion object {
        /** id → 结果（HTTP、后台删除、识屏截图共用） */
        @JvmField val RESULTS = ConcurrentHashMap<String, String>()
    }

    override fun run() {
        val r = try { Ops.http(method!!, url!!, headers, body).toString() } catch (e: Throwable) { Bridge.errJson(e) }
        id?.let { RESULTS[it] = r }
    }
}

/** 后台删除整个文件夹；结果是删掉的文件数（失败 -1） */
class DeleteJob(private val id: String?, private val path: String) : Runnable {
    override fun run() {
        val n = try { Ops.deleteTree(File(path)) } catch (e: Throwable) { -1 }
        id?.let { HttpJob.RESULTS[it] = n.toString() }
    }
}
