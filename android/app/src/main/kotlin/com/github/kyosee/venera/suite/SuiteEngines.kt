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
 * 漫画 / 番剧 / 影视三个 Flutter 引擎都从同一个 [FlutterEngineGroup] 建（共享 GPU 上下文、字体、已加载的代码，新建更快、更省内存）。
 * 番剧、影视在主界面空闲时后台预热，之后常驻；漫画在第一次打开时建（它启动时要初始化很多东西，不在后台抢）。
 *
 * Impeller：以前在 manifest 里写死关闭（Kazumi 的 media_kit 按原项目用 Skia）。现在由这里在进程一启动就决定，
 * 设置里可以打开试试（重启 App 生效）。
 */
object SuiteEngines {
    private const val TAG = "SuiteEngines"
    private const val PREFS = "suite_perf"
    const val COMIC_ID = "nagi_comic_engine"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun impellerEnabled(ctx: Context) = prefs(ctx).getBoolean("impeller", false)
    fun setImpeller(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("impeller", on).apply()
    fun prewarmEnabled(ctx: Context) = prefs(ctx).getBoolean("prewarm", true)
    fun setPrewarm(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("prewarm", on).apply()

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

    @Synchronized
    private fun group(ctx: Context): FlutterEngineGroup {
        initLoader(ctx)
        return group ?: FlutterEngineGroup(ctx.applicationContext).also { group = it }
    }

    /** 从引擎组建一个引擎并跑指定的 dart 入口（null = 默认 main，即漫画） */
    @JvmStatic
    fun create(ctx: Context, entrypoint: String?, label: String): FlutterEngine {
        val t0 = SystemClock.elapsedRealtime()
        val loader = FlutterInjector.instance().flutterLoader()
        val g = group(ctx)
        val ep = if (entrypoint == null) DartExecutor.DartEntrypoint.createDefault()
        else DartExecutor.DartEntrypoint(loader.findAppBundlePath(), entrypoint)
        val engine = g.createAndRunEngine(
            FlutterEngineGroup.Options(ctx.applicationContext).setDartEntrypoint(ep).setInitialRoute("/")
        )
        SuitePerf.log(ctx, "新建 $label 引擎 ${SystemClock.elapsedRealtime() - t0}ms")
        return engine
    }

    /** 漫画（VeneraX）的引擎：常驻缓存 */
    @JvmStatic
    @Synchronized
    fun comic(ctx: Context): FlutterEngine {
        val cache = FlutterEngineCache.getInstance()
        cache.get(COMIC_ID)?.let { return it }
        return create(ctx, null, "漫画").also { cache.put(COMIC_ID, it) }
    }

    private var prewarmed = false

    /** 主界面起来后，空闲时依次预热番剧、影视引擎（每个之间留一点间隔，不卡主界面） */
    @JvmStatic
    fun schedulePrewarm(ctx: Context) {
        if (prewarmed || !prewarmEnabled(ctx)) return
        prewarmed = true
        val app = ctx.applicationContext
        val h = Handler(Looper.getMainLooper())
        val steps = listOf<() -> Unit>(
            { io.github.kyosee.venera.kazumi.KazumiEngine.get(app) },
            { io.github.kyosee.venera.video.VideoActivity.engine(app) },
        )
        fun run(i: Int) {
            if (i >= steps.size) return
            Looper.myQueue().addIdleHandler {
                try { steps[i]() } catch (e: Throwable) { Log.w(TAG, "prewarm failed", e) }
                h.postDelayed({ run(i + 1) }, 1500)
                false
            }
        }
        h.postDelayed({ run(0) }, 2500)
    }
}

/** 进程一启动就初始化 Flutter（Impeller 开关）并开始跟踪各分区界面（androidx.startup） */
class SuiteStartup : Initializer<Unit> {
    override fun create(context: Context) {
        try { SuiteEngines.initLoader(context) } catch (e: Throwable) { Log.w("SuiteStartup", "initLoader", e) }
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
            val shown = SystemClock.elapsedRealtime() - p.t0
            log(a, "${p.from} → ${p.to}  画面 ${shown}ms（onResume ${resumed}ms，$how）  引擎：${p.warm}")
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
                    if (fired) return
                    fired = true
                    fv.removeOnFirstFrameRenderedListener(this)
                    done("Flutter 首帧，表面重建")
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
