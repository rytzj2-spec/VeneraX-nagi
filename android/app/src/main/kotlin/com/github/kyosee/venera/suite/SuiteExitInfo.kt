package io.github.kyosee.venera.suite

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原生崩溃记录：SuiteCrashLog 只能抓 Java/Kotlin 的未捕获异常，Flutter 引擎 / so 库里的崩溃（SIGSEGV 之类）、ANR、
 * 被系统杀都抓不到。这里在每次启动时用 ActivityManager.getHistoricalProcessExitReasons（Android 11+）读
 * 系统记下的「上次进程是怎么没的」，把原因、说明和 trace 写到 内部存储/Nagisaland/crash/exit-*.txt。
 *
 * - ANR：trace 是文字（各线程堆栈），直接写进去。
 * - 原生崩溃：trace 是 tombstone（protobuf 二进制），原样存成 .pb，另外把里面能读的字符串（中止信息、函数名、so 名）抽出来写进 txt。
 * - 记过的不重复记（按时间戳）。第一次运行会把系统还留着的历史（最多 16 条）都写出来。
 */
object SuiteExitInfo {
    private const val TAG = "SuiteExitInfo"
    private const val KEY_SEEN = "exit_seen_ts"
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val textFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @JvmStatic
    fun check(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = ctx.applicationContext
        // 读文件、写存储放到后台线程，不拖慢启动
        Thread({
            try { run(app) } catch (e: Throwable) { Log.w(TAG, "read exit reasons", e) }
        }, "nagi-exit-info").start()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun run(app: Context) {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val prefs = app.getSharedPreferences("suite_perf", Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_SEEN, 0L)
        val list = am.getHistoricalProcessExitReasons(app.packageName, 0, 16)
        var newest = seen
        for (info in list.sortedBy { it.timestamp }) {
            if (info.timestamp <= seen) continue
            if (info.timestamp > newest) newest = info.timestamp
            if (boring(info)) continue
            try { write(app, info) } catch (e: Throwable) { Log.w(TAG, "write", e) }
        }
        if (newest != seen) prefs.edit().putLong(KEY_SEEN, newest).apply()
    }

    /** 正常退出（用户在设置里强行停止、App 更新、自己正常退出）不记 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun boring(info: ApplicationExitInfo): Boolean = when (info.reason) {
        10, 11, 15, 16 -> true                // USER_REQUESTED / USER_STOPPED / PACKAGE_STATE_CHANGE / PACKAGE_UPDATED
        1 -> info.status == 0                 // EXIT_SELF 且退出码 0
        else -> false
    }

    private fun reasonName(r: Int): String = when (r) {
        0 -> "UNKNOWN 未知"
        1 -> "EXIT_SELF 自己退出（非 0 退出码）"
        2 -> "SIGNALED 被信号结束（多为系统杀 / 从最近任务划掉）"
        3 -> "LOW_MEMORY 内存不足被系统杀"
        4 -> "CRASH Java/Kotlin 崩溃"
        5 -> "CRASH_NATIVE 原生崩溃（Flutter 引擎 / so 库）"
        6 -> "ANR 无响应"
        7 -> "INITIALIZATION_FAILURE 启动失败"
        8 -> "PERMISSION_CHANGE 权限变化"
        9 -> "EXCESSIVE_RESOURCE_USAGE 资源占用过高被杀"
        12 -> "DEPENDENCY_DIED 依赖的进程没了"
        13 -> "OTHER 其他（系统原因）"
        14 -> "FREEZER 后台冻结被杀"
        else -> "reason=$r"
    }

    private fun shortName(r: Int): String = when (r) {
        3 -> "lowmem"; 4 -> "crash"; 5 -> "native"; 6 -> "anr"; 2 -> "signal"; else -> "exit$r"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun write(app: Context, info: ApplicationExitInfo) {
        val trace: ByteArray? = try {
            info.traceInputStream?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < 8 * 1024 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Throwable) { null }

        val sb = StringBuilder()
        sb.appendLine("退出时间: ${textFmt.format(Date(info.timestamp))}")
        sb.appendLine("记录时间: ${textFmt.format(Date())}")
        sb.appendLine("原因: ${reasonName(info.reason)}")
        sb.appendLine("说明: ${info.description ?: "-"}")
        sb.appendLine("退出码 / 信号: ${info.status}")
        sb.appendLine("进程: ${info.processName}  pid=${info.pid}")
        sb.appendLine("前后台: importance=${info.importance}（100=前台，125=前台服务，400 及以上=后台）")
        sb.appendLine("内存: pss=${info.pss}KB rss=${info.rss}KB")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            sb.appendLine("当前版本: ${pi.versionName}（崩溃发生时可能是上一版）")
        } catch (_: Throwable) {}
        sb.appendLine()

        val base = "exit-" + fileFmt.format(Date(info.timestamp)) + "-" + shortName(info.reason)
        var pbName: String? = null
        when {
            trace == null || trace.isEmpty() -> sb.appendLine("（系统没有留 trace）")
            info.reason == 5 -> {
                pbName = "$base.pb"
                sb.appendLine("tombstone 原文存在同目录的 $pbName（protobuf，可用 Android SDK 的工具解析）。")
                sb.appendLine("下面是从里面抽出来的可读字符串：")
                sb.appendLine()
                sb.append(printable(trace))
            }
            else -> sb.append(String(trace, Charsets.UTF_8))
        }

        val dirs = listOf(
            File(Environment.getExternalStorageDirectory(), "Nagisaland/crash"),
            File(app.getExternalFilesDir(null), "crash")
        )
        for (d in dirs) {
            try {
                d.mkdirs()
                File(d, "$base.txt").writeText(sb.toString())
                if (pbName != null && trace != null) File(d, pbName).writeBytes(trace)
                SuitePerf.write("上次退出：${reasonName(info.reason)}（${textFmt.format(Date(info.timestamp))}）→ crash/$base.txt")
                return
            } catch (_: Throwable) {}
        }
    }

    /** 把二进制里连续 4 个以上的可打印字符（ASCII）抽出来，一段一行 */
    private fun printable(b: ByteArray): String {
        val sb = StringBuilder()
        val cur = StringBuilder()
        var lines = 0
        fun flush() {
            if (cur.length >= 4 && lines < 4000) { sb.append(cur).append('\n'); lines++ }
            cur.setLength(0)
        }
        for (x in b) {
            val c = x.toInt() and 0xff
            if (c in 0x20..0x7e || c == '\t'.code) cur.append(c.toChar()) else flush()
        }
        flush()
        return sb.toString()
    }
}
