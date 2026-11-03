package com.yenaly.han1meviewer.suite

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nagisaland suite：整个 App 的崩溃记录。崩溃时把堆栈写到 内部存储/Nagisaland/crash/（写不了就写到 App 私有目录），
 * 然后交还给系统原来的处理（照常闪退）。只记录，不弹窗。
 */
object SuiteCrashLog {
    @JvmStatic
    fun install(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(app, t, e) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun write(app: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
            pw.println("thread: " + t.name)
            pw.println()
            e.printStackTrace(pw)
        }
        val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val dirs = listOf(
            File(Environment.getExternalStorageDirectory(), "Nagisaland/crash"),
            File(app.getExternalFilesDir(null), "crash")
        )
        for (d in dirs) {
            try {
                d.mkdirs()
                File(d, name).writeText(sw.toString())
                return
            } catch (_: Throwable) {}
        }
    }
}
