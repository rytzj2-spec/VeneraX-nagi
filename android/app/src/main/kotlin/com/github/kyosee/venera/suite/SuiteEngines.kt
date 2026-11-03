package io.github.kyosee.venera.suite

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.startup.Initializer
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.renderer.FlutterUiDisplayListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 漫画 / 番剧 / 影视三个 Flutter 引擎的创建。
 *
 * v0.19.2：v0.19.1 改成从同一个 [FlutterEngineGroup] 建以后真机频繁闪退（原生层，SuiteCrashLog 抓不到；perf.txt 里
 * 引擎在同一次切换后反复「新建」= 进程崩了重启）。引擎组里后建的引擎是从第一个 spawn 出来的，和它共用 UI / 渲染 / IO
 * 线程和 GPU 资源上下文，多个引擎同时活着时最可疑。所以v0.19.2 曾默认回到 v0.19.0 的方式（独立引擎），
 * 后来查明闪退是 SuitePerf 首帧监听的问题（b03dc2b），v0.19.4 起引擎组和后台预热又默认开（「流畅度设置」里可关）。关掉时：每个分区一个独立的 FlutterEngine，
 * 漫画引擎随漫画界面建、随界面销毁。不管开没开，出任何异常都退回普通建引擎，
 * 异常写进 内部存储/Nagisaland/debug/perf.txt。
 *
 * Impeller：以前在 manifest 里写死关闭（Kazumi 的 media_kit 按原项目用 Skia）。现在由这里在进程一启动就决定，
 * 设置里可以打开试试（重启 App 生效）。
 */
object SuiteEngines {
    private const val TAG = "SuiteEngines"
    private const val PREFS = "suite_perf"
    const val COMIC_ID = "nagi_comic_engine"

    // v0.19.2 换了键名（当时默认关）；v0.19.4 起默认开：闪退的真正原因是 SuitePerf 的首帧监听（b03dc2b 已修），不是引擎组
    private const val KEY_GROUP = "engine_group_v2"
    private const val KEY_PREWARM = "prewarm_v2"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun impellerEnabled(ctx: Context) = prefs(ctx).getBoolean("impeller", false)
    fun setImpeller(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("impeller", on).apply()
    fun prewarmEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_PREWARM, true)
    fun setPrewarm(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_PREWARM, on).apply()

    /** 设置里的「共享引擎组」开关（改了要完全退出再开才生效） */
    fun groupSetting(ctx: Context) = prefs(ctx).getBoolean(KEY_GROUP, true)
    fun setGroup(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_GROUP, on).apply()

    /** 本进程实际用不用引擎组：第一次问的时候定下来，进程内不再变（不能一半引擎在组里、一半不在） */
    @Volatile private var groupMode: Boolean? = null
    @JvmStatic
    fun groupEnabled(ctx: Context): Boolean = groupMode ?: synchronized(this) {
        groupMode ?: (try { groupSetting(ctx) } catch (_: Throwable) { false }).also { groupMode = it }
    }

    @Volatile private var loaderReady = false
    private var group: FlutterEngineGroup? = null

    /** 进程里第一次初始化 Flutter（必须在任何 FlutterEngine 之前，Impeller 开关才有效） */
    @JvmStatic
    @Synchronized
    fun initLoader(ctx: Context) {
        if (loaderReady) return
        val app = ctx.applicationContext
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(app)
        val impeller = impellerEnabled(app)
        loader.ensureInitializationComplete(app, arrayOf("--enable-impeller=$impeller"))
        loaderReady = true
        Log.i(TAG, "Flutter loader ready, impeller=$impeller")
    }

    /** 和 [initLoader] 一样，但出错只记日志（之后 Flutter 自己还会再初始化一次） */
    @JvmStatic
    fun initLoaderSafe(ctx: Context) {
        try { initLoader(ctx) } catch (e: Throwable) { SuitePerf.error(ctx, "初始化 Flutter（Impeller 开关）", e) }
    }

    @Synchronized
    private fun group(ctx: Context): FlutterEngineGroup =
        group ?: FlutterEngineGroup(ctx.applicationContext).also { group = it }

    /**
     * 建一个引擎并跑指定的 dart 入口（null = 默认 main，即漫画）。必须在主线程调用。
     * 默认 = v0.19.0 的独立引擎；打开「共享引擎组」时先试引擎组，失败退回独立引擎。
     */
    @JvmStatic
    fun create(ctx: Context, entrypoint: String?, label: String): FlutterEngine {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            SuitePerf.error(ctx, "$label 引擎不在主线程创建（${Thread.currentThread().name}）", null)
        }
        val t0 = SystemClock.elapsedRealtime()
        initLoaderSafe(ctx)
        if (groupEnabled(ctx)) {
            try {
                val engine = createFromGroup(ctx, entrypoint)
                SuitePerf.log(ctx, "新建 $label 引擎（引擎组）${SystemClock.elapsedRealtime() - t0}ms")
                return engine
            } catch (e: Throwable) {
                SuitePerf.error(ctx, "引擎组建 $label 引擎失败，改用普通方式", e)
            }
        }
        val engine = createStandalone(ctx, entrypoint)
        SuitePerf.log(ctx, "新建 $label 引擎 ${SystemClock.elapsedRealtime() - t0}ms")
        return engine
    }

    /** v0.19.0 的建法：独立的 FlutterEngine */
    private fun createStandalone(ctx: Context, entrypoint: String?): FlutterEngine {
        val app = ctx.applicationContext
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(app)
        loader.ensureInitializationComplete(app, null)
        val engine = FlutterEngine(app)
        engine.navigationChannel.setInitialRoute("/")
        val ep = if (entrypoint == null) DartExecutor.DartEntrypoint.createDefault()
        else DartExecutor.DartEntrypoint(loader.findAppBundlePath(), entrypoint)
        engine.dartExecutor.executeDartEntrypoint(ep)
        return engine
    }

    private fun createFromGroup(ctx: Context, entrypoint: String?): FlutterEngine {
        val loader = FlutterInjector.instance().flutterLoader()
        val g = group(ctx)
        val ep = if (entrypoint == null) DartExecutor.DartEntrypoint.createDefault()
        else DartExecutor.DartEntrypoint(loader.findAppBundlePath(), entrypoint)
        return g.createAndRunEngine(
            FlutterEngineGroup.Options(ctx.applicationContext).setDartEntrypoint(ep).setInitialRoute("/")
        )
    }

    /**
     * 漫画（VeneraX）界面用的引擎：只有开了「共享引擎组」才由这里提供并常驻；
     * 默认返回 null = 和 v0.19.0 一样由漫画界面自己建、自己销毁。
     */
    @JvmStatic
    @Synchronized
    fun comicOrNull(ctx: Context): FlutterEngine? {
        if (!groupEnabled(ctx)) return null
        return try {
            val cache = FlutterEngineCache.getInstance()
            cache.get(COMIC_ID) ?: create(ctx, null, "漫画").also { cache.put(COMIC_ID, it) }
        } catch (e: Throwable) {
            SuitePerf.error(ctx, "漫画引擎（引擎组）失败，交给漫画界面自己建", e)
            null
        }
    }

    private var prewarmed = false

    /** 主界面起来后，空闲时依次预热番剧、影视引擎（每个之间留一点间隔，不卡主界面）。默认关 */
    @JvmStatic
    fun schedulePrewarm(ctx: Context) {
        try {
            if (prewarmed || !prewarmEnabled(ctx)) return
            prewarmed = true
            val app = ctx.applicationContext
            val h = Handler(Looper.getMainLooper())
            val steps = listOf<Pair<String, () -> Unit>>(
                "番剧" to { io.github.kyosee.venera.kazumi.KazumiEngine.get(app) },
                "影视" to { io.github.kyosee.venera.video.VideoActivity.engine(app) },
            )
            fun run(i: Int) {
                if (i >= steps.size) return
                // 一定在主线程的空闲时刻建（Flutter 引擎只能在主线程建）
                Looper.getMainLooper().queue.addIdleHandler {
                    try { steps[i].second() } catch (e: Throwable) { SuitePerf.error(app, "预热${steps[i].first}引擎失败", e) }
                    h.postDelayed({ run(i + 1) }, 1500)
                    false
                }
            }
            h.postDelayed({ run(0) }, 2500)
        } catch (e: Throwable) {
            SuitePerf.error(ctx, "安排预热失败", e)
        }
    }
}

/** 进程一启动就初始化 Flutter（Impeller 开关）并开始跟踪各分区界面（androidx.startup） */
class SuiteStartup : Initializer<Unit> {
    override fun create(context: Context) {
        SuiteEngines.initLoaderSafe(context)
        // 上次进程是怎么退出的（原生崩溃 / ANR / 被系统杀）→ 内部存储/Nagisaland/crash/
        try { SuiteExitInfo.check(context) } catch (e: Throwable) { Log.w("SuiteStartup", "exit info", e) }
        SuiteNav.install(context.applicationContext as Application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * 分区切换耗时：从点底栏 / 主界面按钮到画面出来的毫秒数，写到 内部存储/Nagisaland/debug/perf.txt。
 * 「画面出来」：Flutter 分区等 FlutterView 渲染出第一帧，其它（主界面网页、H）等下一帧画完。
 */
object SuitePerf {
    private val io = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val PREFS = "suite_perf"

    fun enabled(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("log", true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("log", on).apply()

    fun log(ctx: Context, line: String) {
        if (!enabled(ctx)) return
        write(line)
    }

    /** 出错：不管「记录切换耗时」开没开都写进 perf.txt（带异常堆栈） */
    fun error(ctx: Context?, what: String, e: Throwable?) {
        Log.w("SuitePerf", what, e)
        val sb = StringBuilder("出错：").append(what)
        if (e != null) {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            sb.append('\n').append(sw.toString().lines().take(30).joinToString("\n") { "    $it" })
        }
        write(sb.toString())
    }

    fun write(line: String) {
        val text = "${fmt.format(Date())}  $line\n"
        Log.i("SuitePerf", line)
        io.execute {
            try {
                val dir = File(Environment.getExternalStorageDirectory(), "Nagisaland/debug")
                dir.mkdirs()
                val f = File(dir, "perf.txt")
                if (f.length() > 512 * 1024) f.renameTo(File(dir, "perf.old.txt"))
                f.appendText(text)
            } catch (_: Throwable) {}
        }
    }

    private class Pending(val from: String, val to: String, val t0: Long, val warm: String)
    @Volatile private var pending: Pending? = null

    /** 开始一次切换（SuiteNav.go 调用） */
    fun start(from: String, to: String, warm: String) {
        pending = Pending(from, to, SystemClock.elapsedRealtime(), warm)
    }

    /** 目标界面 onResume：开始等画面 */
    fun onResumed(a: Activity, key: String?) {
        val p = pending ?: return
        if (key == null || key != p.to && !(p.to in setOf("lib", "site", "extract", "emu") && key == "nagi")) return
        pending = null
        val resumed = SystemClock.elapsedRealtime() - p.t0
        val fv = findFlutterView(a.window.decorView)
        fun done(how: String) {
            try {
                val shown = SystemClock.elapsedRealtime() - p.t0
                log(a, "${p.from} → ${p.to}  画面 ${shown}ms（onResume ${resumed}ms，$how）  引擎：${p.warm}")
            } catch (_: Throwable) {}
        }
        if (fv != null) {
            if (fv.hasRenderedFirstFrame()) {
                // 表面没重建：下一帧就是画面
                Choreographer.getInstance().postFrameCallback { done("Flutter 表面复用") }
                return
            }
            val h = Handler(Looper.getMainLooper())
            var fired = false
            val listener = object : FlutterUiDisplayListener {
                override fun onFlutterUiDisplayed() {
                    // 这个回调由引擎经 JNI 调进来，Flutter 正在遍历监听器集合：
                    // 在这里直接 remove 会抛 ConcurrentModificationException → 引擎 CheckException 失败 → 整个 App 闪退。
                    // 所以移除和记日志都放到下一轮消息里做，并且不让任何异常漏回 JNI。
                    if (fired) return
                    fired = true
                    val self = this
                    h.post {
                        try { fv.removeOnFirstFrameRenderedListener(self) } catch (_: Throwable) {}
                        try { done("Flutter 首帧，表面重建") } catch (_: Throwable) {}
                    }
                }
                override fun onFlutterUiNoLongerDisplayed() {}
            }
            fv.addOnFirstFrameRenderedListener(listener)
            h.postDelayed({
                if (!fired) {
                    fired = true
                    fv.removeOnFirstFrameRenderedListener(listener)
                    done("5 秒内没等到 Flutter 首帧")
                }
            }, 5000)
        } else {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { done("原生界面") }
            }
        }
    }

    private fun findFlutterView(v: View): FlutterView? {
        if (v is FlutterView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findFlutterView(v.getChildAt(i))?.let { return it }
        return null
    }
}
