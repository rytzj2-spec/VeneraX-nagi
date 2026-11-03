package com.yenaly.han1meviewer.suite

import android.util.Log
import com.yenaly.yenaly_libs.utils.applicationContext
import java.io.File

/**
 * Nagisaland suite：网页解析不出东西时，把原网页存到「内部存储/Nagisaland/debug/h-<类型>-*.html」，
 * 方便对照网站新结构修解析。每种类型只保留最近 5 个。
 */
object SuiteDebug {
    private const val TAG = "SuiteDebug"

    /**
     * @param kind 例如 "watch" / "search" / "home"
     * @param reason 写在文件开头的注释里（异常信息等）
     */
    @JvmStatic
    fun dumpHtml(kind: String, body: String?, reason: String? = null): File? {
        if (body.isNullOrEmpty()) return null
        return try {
            val context = runCatching { applicationContext }.getOrNull()
            val dir = SuiteFiles.dir(context, "debug") ?: return null
            val prefix = "h-$kind-"
            val file = File(dir, "$prefix${SuiteFiles.timestamp()}.html")
            val header = reason?.let { "<!-- Nagisaland suite: ${it.replace("--", "- -")} -->\n" }.orEmpty()
            file.writeText(header + body)
            SuiteFiles.prune(dir, prefix, 5)
            Log.w(TAG, "saved $kind page to $file ($reason)")
            file
        } catch (e: Throwable) {
            Log.w(TAG, "dumpHtml($kind) failed", e)
            null
        }
    }
}
