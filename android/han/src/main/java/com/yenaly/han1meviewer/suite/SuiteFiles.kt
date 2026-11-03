package com.yenaly.han1meviewer.suite

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nagisaland suite：排查用的文件统一放到「内部存储/Nagisaland/<子目录>」，
 * 没有存储权限时退回 App 私有目录（Android/data/<包名>/files/Nagisaland/<子目录>）。
 */
internal object SuiteFiles {
    private const val TAG = "SuiteFiles"

    fun dir(context: Context?, sub: String): File? {
        val candidates = buildList {
            @Suppress("DEPRECATION")
            add(File(Environment.getExternalStorageDirectory(), "Nagisaland/$sub"))
            context?.getExternalFilesDir(null)?.let { add(File(it, "Nagisaland/$sub")) }
            context?.filesDir?.let { add(File(it, "Nagisaland/$sub")) }
        }
        for (dir in candidates) {
            try {
                if ((dir.isDirectory || dir.mkdirs()) && dir.canWrite()) return dir
            } catch (e: Exception) {
                Log.w(TAG, "can't use $dir", e)
            }
        }
        return null
    }

    fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** 同一前缀只留最新的 [keep] 个文件。 */
    fun prune(dir: File, prefix: String, keep: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) } ?: return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }
}
