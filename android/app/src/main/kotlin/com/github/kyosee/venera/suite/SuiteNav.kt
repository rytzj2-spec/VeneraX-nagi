package io.github.kyosee.venera.suite

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.graphics.PathParser
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel
import io.github.kyosee.venera.MainActivity
import io.github.kyosee.venera.kazumi.KazumiActivity
import io.github.kyosee.venera.kazumi.KazumiEngine
import io.github.kyosee.venera.video.VideoActivity
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Nagisaland：常驻分区栏、分区切换不跳转。
 *
 * 各分区各是一个 Activity（Nagisaland 主界面 WebView / 漫画 VeneraX / 番剧 Kazumi / 影视 / H Han1meViewer），都在同一个任务里常驻，
 * 切换时用 REORDER_TO_FRONT + 无动画把目标提到最前，状态全保留。
 * - 竖屏：底栏（[SuiteNavConfig] 里选的 3~6 个分区 +「更多」）；横屏：左侧竖排导航栏。
 * - 主界面自带网页底栏，读同一份配置（Bridge.navConfig），改了马上通知网页（window.__nagiNavChanged）。
 *
 * Nagisaland 主界面（com.nagisaland.mobile.MainActivity）在 android/app/src/main/kotlin/com/nagisaland/mobile/；
 * 这里用 Class.forName 找它，H 模块通过反射调 attach / interceptFinish。
 */
object SuiteNav {
    const val NAGI_MAIN = "com.nagisaland.mobile.MainActivity"
    const val EXTRA_TO = "to"
    val NAGI_TABS = setOf("lib", "site", "extract", "emu")

    private var nagiClass: Class<*>? = null
    private val hClass: Class<*>? by lazy {
        try { Class.forName("com.yenaly.han1meviewer.ui.activity.MainActivity") } catch (e: Throwable) { null }
    }
    private var checked = false
    private var callbacksRegistered = false
    @Volatile private var pendingTab: String? = null
    private var nagiRef: WeakReference<Activity>? = null

    @JvmStatic
    fun enabled(): Boolean {
        if (!checked) {
            nagiClass = try { Class.forName(NAGI_MAIN) } catch (e: Throwable) { null }
            checked = true
        }
        return nagiClass != null
    }

    /** 界面 → 分区名（perf 日志、底栏高亮用） */
    fun keyOf(a: Activity): String? = when (a.javaClass) {
        MainActivity::class.java -> "comic"
        KazumiActivity::class.java -> "anime"
        VideoActivity::class.java -> "video"
        hClass -> "h"
        SuiteSwitchActivity::class.java -> "nagi"
        else -> if (a.javaClass.name == NAGI_MAIN) "nagi" else null
    }

    private fun engineState(ctx: android.content.Context, to: String): String = when (to) {
        "anime" -> if (FlutterEngineCache.getInstance().get(KazumiEngine.ID).let { it != null && !KazumiEngine.isPlaceholder(it) }) "已预热" else "要新建"
        "video" -> if (FlutterEngineCache.getInstance().get("nagi_video_engine") != null) "已预热" else "要新建"
        "comic" -> when {
            FlutterEngineCache.getInstance().get(SuiteEngines.COMIC_ID) != null -> "常驻"
            SuiteEngines.groupEnabled(ctx) -> "要新建"
            else -> "随漫画界面"
        }
        else -> "-"
    }

    /** 切到某个分区：comic / anime / video / h / lib / site / extract / emu */
    @JvmStatic
    fun go(from: Activity, to: String) {
        if (!enabled()) return
        if (!SuiteProfile.isEnabled(from, to)) {          // 一键配置里关掉的分区
            android.widget.Toast.makeText(from, "这个分区没有开启", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cls: Class<*> = when (to) {
            "comic" -> MainActivity::class.java
            "anime" -> KazumiActivity::class.java
            "video" -> VideoActivity::class.java
            "h" -> hClass ?: return
            in NAGI_TABS -> { pendingTab = to; nagiClass!! }
            else -> return
        }
        if (cls == from.javaClass) {           // 已经在这个分区
            if (to in NAGI_TABS) applyPendingTab(from)
            return
        }
        SuitePerf.start(keyOf(from)?.let { if (it == "nagi") "主界面" else it } ?: from.javaClass.simpleName, to, engineState(from, to))
        val i = Intent(from, cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        from.startActivity(i)
        @Suppress("DEPRECATION")
        from.overridePendingTransition(0, 0)
    }

    /**
     * 漫画/番剧在根页面按返回时 Flutter 会 finish() 这个界面；这里改成回到「游戏库」，界面保留不销毁。
     * 返回 true 表示已处理（调用方不要再 super.finish()）。
     */
    @JvmStatic
    fun interceptFinish(a: Activity): Boolean {
        if (!enabled() || a.isChangingConfigurations) return false
        val nagi = nagiRef?.get()
        if (nagi != null && (nagi.isFinishing || nagi.isDestroyed)) return false
        // 分区首页从左边缘往右划（Android 14+）= 打开这个分区的设置
        val key = keyOf(a)
        if (SuiteBack.consumeLeftEdge() && SuiteGesture.leftEdgeSettings(a) && key != null && key != "nagi") {
            openSettings(a, key)
            return true
        }
        when (SuiteGesture.rootBack(a)) {
            SuiteGesture.ROOT_LIB -> {
                // gal（游戏库 / 小站 / 解压 / 模拟器）全关了：没有主界面可回，退到后台
                val home = SuiteProfile.firstGal(a)
                if (home == null) a.moveTaskToBack(true) else go(a, home)
            }
            SuiteGesture.ROOT_NONE -> {}
            else -> a.moveTaskToBack(true)   // 退到后台：下次打开各分区还在原来的位置
        }
        return true
    }

    /** 进程启动时（SuiteStartup）注册界面跟踪 */
    @JvmStatic
    fun install(app: Application) {
        if (callbacksRegistered) return
        callbacksRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                // 一键配置：全新安装还没导入配置 → 任何界面都先去导入页
                if (a !is SuiteOnboardActivity && a !is SuiteRestartActivity && !SuiteProfile.configured(a)) {
                    try {
                        a.startActivity(Intent(a, SuiteOnboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Throwable) {}
                    a.finish()
                    return
                }
                remember(a)
                // 分区首页的返回手势：记下从哪边划的（左边缘 = 打开设置，见 SuiteBack）
                if (a !is SuiteSwitchActivity && keyOf(a) != null) SuiteBack.install(a)
            }
            override fun onActivityStarted(a: Activity) { remember(a) }
            override fun onActivityResumed(a: Activity) {
                remember(a)
                if (a.javaClass.name == NAGI_MAIN) {
                    // gal 全关了：主界面没东西，直接进第一个能用的分区
                    if (SuiteProfile.firstGal(a) == null) {
                        SuiteProfile.firstSection(a)?.let { go(a, it) }
                        return
                    }
                    // 要去的页面被关了 → 换成第一个能用的
                    pendingTab?.let { if (!SuiteProfile.isEnabled(a, it)) pendingTab = SuiteProfile.firstGal(a) }
                }
                if (a.javaClass.name == NAGI_MAIN) applyPendingTab(a)
                SuitePerf.onResumed(a, keyOf(a))
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun remember(a: Activity) {
        if (a.javaClass.name == NAGI_MAIN) nagiRef = WeakReference(a)
    }

    private fun applyPendingTab(a: Activity) {
        val t = pendingTab ?: return
        pendingTab = null
        val wv = findWebView(a.window.decorView) ?: return
        wv.post {
            wv.evaluateJavascript(
                "(function(){var b=document.querySelector('#nav button[data-tab=\"$t\"]');if(b&&!b.classList.contains('on'))b.click();})()",
                null
            )
        }
    }

    private fun findWebView(v: View): WebView? {
        if (v is WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** 配置改了：通知主界面网页重新排底栏 */
    fun onNavConfigChanged() {
        val a = nagiRef?.get() ?: return
        val wv = findWebView(a.window.decorView) ?: return
        wv.post { wv.evaluateJavascript("window.__nagiNavChanged&&window.__nagiNavChanged()", null) }
    }

    // ---------------------------------------------------------------- 左滑分区栏 = 打开当前分区的设置
    private const val SUITE_CHANNEL = "nagisaland/suite"
    private const val H_SETTINGS = "com.yenaly.han1meviewer.ui.activity.SettingsActivity"
    private val engines = HashMap<String, WeakReference<FlutterEngine>>()

    /** 漫画 / 番剧 / 影视界面的 configureFlutterEngine 里调用：记下引擎，左滑时通过它让 Dart 打开设置页 */
    @JvmStatic
    fun registerEngine(key: String, engine: FlutterEngine) {
        engines[key] = WeakReference(engine)
    }

    /** 打开某个分区自己的设置：Flutter 分区发 nagisaland/suite 的 openSettings 给 Dart；H 打开它的设置页；主界面交给网页 */
    @JvmStatic
    fun openSettings(a: Activity, key: String) {
        fun fail(why: String) {
            android.widget.Toast.makeText(a, "打不开设置：$why", android.widget.Toast.LENGTH_SHORT).show()
        }
        try {
            when (key) {
                "comic", "anime", "video" -> {
                    val e = engines[key]?.get() ?: return fail("分区还没准备好")
                    MethodChannel(e.dartExecutor.binaryMessenger, SUITE_CHANNEL)
                        .invokeMethod("openSettings", null, object : MethodChannel.Result {
                            override fun success(result: Any?) {}
                            override fun error(code: String, msg: String?, details: Any?) { fail(msg ?: code) }
                            override fun notImplemented() { fail("这个分区还不支持") }
                        })
                }
                "h" -> a.startActivity(Intent(a, Class.forName(H_SETTINGS)))
                else -> {
                    val wv = findWebView(a.window.decorView) ?: return SuitePanels.showSettings(a)
                    wv.evaluateJavascript(
                        "(function(){if(window.__nagiOpenSettings){window.__nagiOpenSettings();return 1}return 0})()"
                    ) { r -> if (r != "1") SuitePanels.showSettings(a) }
                }
            }
        } catch (e: Throwable) {
            SuitePerf.error(a, "打开 $key 的设置失败", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 在漫画/番剧/影视/H 界面加常驻分区栏（在 onPostCreate 里调用） */
    @JvmStatic
    fun attach(a: Activity, current: String) {
        if (!enabled()) return
        install(a.application)
        val content = a.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(SuiteNavBar.TAG) != null) return
        val child = content.getChildAt(0) ?: return
        val bar = SuiteNavBar(a, current) { go(a, it) }
        bar.tag = SuiteNavBar.TAG
        content.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(content) { _, ins ->
            val nav = ins.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = ins.getInsets(WindowInsetsCompat.Type.displayCutout())
            val pip = Build.VERSION.SDK_INT >= 24 && a.isInPictureInPictureMode
            val landscape = a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val show = ins.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    !ins.isVisible(WindowInsetsCompat.Type.ime()) && !pip
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            if (!show) {
                bar.visibility = View.GONE
                if (lp.bottomMargin != 0 || lp.leftMargin != 0) { lp.bottomMargin = 0; lp.leftMargin = 0; child.layoutParams = lp }
                return@setOnApplyWindowInsetsListener ins
            }
            bar.visibility = View.VISIBLE
            val b = WindowInsetsCompat.Builder(ins)
            if (landscape) {
                // 横屏：左侧竖排导航栏（避开左边的导航条 / 刘海）
                val left = maxOf(nav.left, cut.left)
                bar.setVertical(true)
                bar.setPadding(left, 0, 0, nav.bottom)
                val blp = bar.layoutParams as FrameLayout.LayoutParams
                if (blp.gravity != Gravity.START || blp.width != ViewGroup.LayoutParams.WRAP_CONTENT || blp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    blp.gravity = Gravity.START; blp.width = ViewGroup.LayoutParams.WRAP_CONTENT; blp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    bar.layoutParams = blp
                }
                val m = bar.railPx + left
                if (lp.leftMargin != m || lp.bottomMargin != 0) { lp.leftMargin = m; lp.bottomMargin = 0; child.layoutParams = lp }
                for (type in intArrayOf(WindowInsetsCompat.Type.systemBars(), WindowInsetsCompat.Type.navigationBars(), WindowInsetsCompat.Type.displayCutout())) {
                    val x = ins.getInsets(type)
                    b.setInsets(type, Insets.of(0, x.top, x.right, x.bottom))
                }
            } else {
                bar.setVertical(false)
                if (bar.paddingBottom != nav.bottom || bar.paddingLeft != 0) bar.setPadding(0, 0, 0, nav.bottom)
                val blp = bar.layoutParams as FrameLayout.LayoutParams
                if (blp.gravity != Gravity.BOTTOM || blp.width != ViewGroup.LayoutParams.MATCH_PARENT || blp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    blp.gravity = Gravity.BOTTOM; blp.width = ViewGroup.LayoutParams.MATCH_PARENT; blp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    bar.layoutParams = blp
                }
                val m = bar.barPx + nav.bottom
                if (lp.bottomMargin != m || lp.leftMargin != 0) { lp.bottomMargin = m; lp.leftMargin = 0; child.layoutParams = lp }
                // Flutter 界面已经在底栏上面了，不要再给系统导航条留底部空白
                for (type in intArrayOf(
                    WindowInsetsCompat.Type.systemBars(), WindowInsetsCompat.Type.navigationBars(),
                    WindowInsetsCompat.Type.tappableElement(), WindowInsetsCompat.Type.systemGestures(),
                    WindowInsetsCompat.Type.mandatorySystemGestures()
                )) {
                    val x = ins.getInsets(type)
                    b.setInsets(type, Insets.of(x.left, x.top, x.right, 0))
                }
            }
            b.build()
        }
        ViewCompat.requestApplyInsets(content)
    }
}

/** 分区的图标（24×24 线条，照 Nagisaland 网页底栏的 SVG）和名字 */
object SuiteNavItems {
    private fun p(vararg d: String) = d.map { PathParser.createPathFromPathData(it) }
    private fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = Path().apply { addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW) }
    private fun c(x: Float, y: Float, r: Float) = Path().apply { addCircle(x, y, r, Path.Direction.CW) }

    fun paths(key: String): List<Path> = when (key) {
        "lib" -> listOf(rr(3f, 3f, 10f, 10f, 1.5f), rr(14f, 3f, 21f, 10f, 1.5f), rr(3f, 14f, 10f, 21f, 1.5f), rr(14f, 14f, 21f, 21f, 1.5f))
        "site" -> p("M3 9l1.5-5h15L21 9", "M4 9v11h16V9", "M3 9h18", "M9 20v-6h6v6")
        "comic" -> p("M2 5.5C4.5 4 8 4 12 6c4-2 7.5-2 10-.5V19c-2.5-1.5-6-1.5-10 .5-4-2-7.5-2-10-.5z", "M12 6v13.5")
        "anime" -> listOf(rr(2f, 6f, 22f, 20f, 2.5f)) + p("M8 2l4 4 4-4", "M10 10.5v5l4.5-2.5z")
        "video" -> listOf(rr(2f, 4f, 22f, 20f, 2.5f)) + p("M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5")
        "h" -> p("M12 20.5s-7.5-4.6-9.3-9.2C1.4 8 3.6 4.5 7 4.5c2.1 0 3.6 1.2 5 3 1.4-1.8 2.9-3 5-3 3.4 0 5.6 3.5 4.3 6.8-1.8 4.6-9.3 9.2-9.3 9.2z")
        "extract" -> p("M21 8v13H3V8", "M1 3h22v5H1z", "M10 12h4")
        "emu" -> listOf(rr(2f, 7f, 22f, 19f, 4f)) + p("M7 11v4M5 13h4") + listOf(c(16f, 12f, 1f), c(18f, 14f, 1f))
        "more" -> listOf(c(5f, 12f, 1.6f), c(12f, 12f, 1.6f), c(19f, 12f, 1.6f))
        else -> emptyList()
    }

    fun label(key: String) = SuiteNavConfig.LABELS[key] ?: key

    /** 在 canvas 上 (cx, top) 处画一个分区：图标 + 名字 */
    fun draw(canvas: Canvas, key: String, cx: Float, top: Float, dp: Float, color: Int, stroke: Paint, text: Paint) {
        val icon = 22 * dp
        canvas.save()
        canvas.translate(cx - icon / 2, top)
        canvas.scale(icon / 24f, icon / 24f)
        stroke.color = color
        val fill = key == "more"
        stroke.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        for (path in paths(key)) canvas.drawPath(path, stroke)
        stroke.style = Paint.Style.STROKE
        canvas.restore()
        text.color = color
        val fm = text.fontMetrics
        val lineH = text.textSize * 1.5f
        val baseline = top + icon + 2 * dp + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
        canvas.drawText(label(key), cx, baseline, text)
    }
}

/** 原生分区栏：竖屏在底部横排，横屏在左侧竖排；外观照 Nagisaland 网页底栏 */
class SuiteNavBar(ctx: Context, private val current: String, private val onPick: (String) -> Unit) : View(ctx) {
    companion object { const val TAG = "nagisuite-navbar" }

    private val dp = resources.displayMetrics.density
    val barPx = (58 * dp).roundToInt()
    val railPx = (68 * dp).roundToInt()
    private var vertical = false
    private var keys: List<String> = emptyList()

    private val bg = Paint().apply { color = Color.argb(247, 20, 20, 27) }
    private val line = Paint().apply { color = Color.parseColor("#2a2a36") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.MITER
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * dp; textAlign = Paint.Align.CENTER }
    private val muted = Color.parseColor("#6f6f82")
    private val on = Color.parseColor("#f0abfc")
    private var downIdx = -1
    private var longPressed = false
    private val navListener: () -> Unit = { reload() }   // 强引用（SuiteNavConfig 里只存弱引用）
    private val longPress = Runnable {
        longPressed = true
        if (downIdx >= 0 && keys.getOrNull(downIdx) == "more") (context as? Activity)?.let { SuitePanels.showEditor(it) }
    }

    init {
        isClickable = true
        reload()
        SuiteNavConfig.addListener(navListener)
    }

    fun reload() {
        keys = SuiteNavConfig.bar(context) + "more"
        requestLayout()
        invalidate()
    }

    fun setVertical(v: Boolean) {
        if (v == vertical) return
        vertical = v
        requestLayout()
        invalidate()
    }

    private fun isOn(key: String) =
        key == current || (key == "more" && current !in SuiteNavConfig.bar(context))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (vertical) setMeasuredDimension(railPx + paddingLeft, MeasureSpec.getSize(heightMeasureSpec))
        else setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barPx + paddingBottom)
    }

    private val cellH get() = 62 * dp

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        val itemH = 22 * dp + 2 * dp + text.textSize * 1.5f
        if (vertical) {
            canvas.drawRect(w - maxOf(1f, dp * 0.75f), 0f, w, h, line)
            val cx = paddingLeft + railPx / 2f
            val total = cellH * keys.size
            val start = maxOf(0f, (h - paddingBottom - total) / 2)
            for ((i, k) in keys.withIndex()) {
                val top = start + cellH * i + (cellH - itemH) / 2
                SuiteNavItems.draw(canvas, k, cx, top, dp, if (isOn(k)) on else muted, stroke, text)
            }
        } else {
            canvas.drawRect(0f, 0f, w, maxOf(1f, dp * 0.75f), line)
            val cell = w / keys.size
            val top = (barPx - itemH) / 2
            for ((i, k) in keys.withIndex()) {
                SuiteNavItems.draw(canvas, k, cell * i + cell / 2, top, dp, if (isOn(k)) on else muted, stroke, text)
            }
        }
    }

    private fun indexAt(e: MotionEvent): Int {
        if (keys.isEmpty()) return -1
        return if (vertical) {
            val total = cellH * keys.size
            val start = maxOf(0f, (height - paddingBottom - total) / 2)
            val i = ((e.y - start) / cellH).toInt()
            if (e.y < start || i >= keys.size) -1 else i
        } else ((e.x / width) * keys.size).toInt().coerceIn(0, keys.size - 1)
    }

    // 左滑分区栏 = 打开当前分区的设置（只认分区栏上的手势，不和各分区里本来的左右滑冲突）
    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private val swipePx = 56 * dp

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = indexAt(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downIdx = idx
                longPressed = false
                swiped = false
                downX = e.x; downY = e.y
                postDelayed(longPress, 500)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val dx = e.x - downX
                val dy = e.y - downY
                if (!longPressed && !swiped && SuiteGesture.barSwipeSettings(context) &&
                    -dx > swipePx && -dx > 1.5f * kotlin.math.abs(dy)) {
                    swiped = true
                    downIdx = -1
                    (context as? Activity)?.let { SuiteNav.openSettings(it, current) }
                    return true
                }
                if (!longPressed && !swiped && idx >= 0 && idx == downIdx) {
                    performClick()
                    val k = keys[idx]
                    if (k == "more") (context as? Activity)?.let { SuitePanels.showMore(it, current, onPick) }
                    else if (k != current) onPick(k)
                }
                downIdx = -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (idx != downIdx || kotlin.math.abs(e.x - downX) > 12 * dp) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); downIdx = -1 }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Nagisaland 网页底栏点「漫画/番剧」等时启动的透明中转界面：把目标分区无动画提到最前后自己马上关掉 */
class SuiteSwitchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SuiteNav.go(this, intent?.getStringExtra(SuiteNav.EXTRA_TO) ?: "comic")
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
