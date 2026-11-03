package com.yenaly.han1meviewer.suite

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Nagisaland suite：整个 App 崩溃时把堆栈写到「内部存储/Nagisaland/crash/crash-*.txt」，
 * 然后交给原来的处理器（系统照常结束进程），不弹窗、不重启。
 */
object SuiteCrashLog {
    @Volatile
    private var installed = false

    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (_: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = SuiteFiles.dir(context, "crash") ?: return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        val text = buildString {
            appendLine("time: ${SuiteFiles.timestamp()}")
            appendLine("package: ${context.packageName} $version")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        File(dir, "crash-${SuiteFiles.timestamp()}.txt").writeText(text)
        SuiteFiles.prune(dir, "crash-", 30)
        Log.e("SuiteCrashLog", "crash written to $dir")
    }
}
