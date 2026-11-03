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
import io.github.kyosee.venera.MainActivity
import io.github.kyosee.venera.kazumi.KazumiActivity
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Nagisaland suite：常驻底栏、分区切换不跳转。
 *
 * 各分区各是一个 Activity（Nagisaland 的 WebView 主界面 / 漫画 VeneraX / 番剧 Kazumi / H Han1meViewer），都在同一个任务里常驻，
 * 切换时用 REORDER_TO_FRONT + 无动画把目标提到最前，状态全保留。Nagisaland 主界面自带网页底栏；
 * 漫画、番剧界面底部由这里加一条外观一样的原生底栏。
 *
 * Nagisaland 主界面（com.nagisaland.mobile.MainActivity）现在就在本工程里（android/app/src/main/kotlin/com/nagisaland/mobile/），
 * 不再由电脑上的 merge_venerax.py 反编译拼接；这里仍用 Class.forName 找它，H 模块通过反射调这里。
 */
object SuiteNav {
    const val NAGI_MAIN = "com.nagisaland.mobile.MainActivity"
    const val EXTRA_TO = "to"
    private val NAGI_TABS = setOf("lib", "site", "extract", "emu")

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

    /** 切到某个分区：comic / anime / h / lib / site / extract / emu */
    @JvmStatic
    fun go(from: Activity, to: String) {
        if (!enabled()) return
        val cls: Class<*> = when (to) {
            "comic" -> MainActivity::class.java
            "anime" -> KazumiActivity::class.java
            "h" -> hClass ?: return
            in NAGI_TABS -> { pendingTab = to; nagiClass!! }
            else -> return
        }
        if (cls == from.javaClass) {           // 已经在这个分区
            if (to in NAGI_TABS) applyPendingTab(from)
            return
        }
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
        go(a, "lib")
        return true
    }

    private fun ensureCallbacks(app: Application) {
        if (callbacksRegistered) return
        callbacksRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { remember(a) }
            override fun onActivityStarted(a: Activity) { remember(a) }
            override fun onActivityResumed(a: Activity) {
                remember(a)
                if (a.javaClass.name == NAGI_MAIN) applyPendingTab(a)
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

    /** 在漫画/番剧界面底部加常驻底栏（在 onPostCreate 里调用） */
    @JvmStatic
    fun attach(a: Activity, current: String) {
        if (!enabled()) return
        ensureCallbacks(a.application)
        val content = a.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(SuiteNavBar.TAG) != null) return
        val child = content.getChildAt(0) ?: return
        val bar = SuiteNavBar(a, current) { go(a, it) }
        bar.tag = SuiteNavBar.TAG
        content.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(content) { _, ins ->
            val nav = ins.getInsets(WindowInsetsCompat.Type.navigationBars())
            val pip = Build.VERSION.SDK_INT >= 24 && a.isInPictureInPictureMode
            val show = ins.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    !ins.isVisible(WindowInsetsCompat.Type.ime()) &&
                    a.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE && !pip
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            if (!show) {
                bar.visibility = View.GONE
                if (lp.bottomMargin != 0) { lp.bottomMargin = 0; child.layoutParams = lp }
                return@setOnApplyWindowInsetsListener ins
            }
            bar.visibility = View.VISIBLE
            if (bar.paddingBottom != nav.bottom) bar.setPadding(0, 0, 0, nav.bottom)
            val m = bar.barPx + nav.bottom
            if (lp.bottomMargin != m) { lp.bottomMargin = m; child.layoutParams = lp }
            // Flutter 界面已经在底栏上面了，不要再给系统导航条留底部空白
            val b = WindowInsetsCompat.Builder(ins)
            for (type in intArrayOf(
                WindowInsetsCompat.Type.systemBars(), WindowInsetsCompat.Type.navigationBars(),
                WindowInsetsCompat.Type.tappableElement(), WindowInsetsCompat.Type.systemGestures(),
                WindowInsetsCompat.Type.mandatorySystemGestures()
            )) {
                val x = ins.getInsets(type)
                b.setInsets(type, Insets.of(x.left, x.top, x.right, 0))
            }
            b.build()
        }
        ViewCompat.requestApplyInsets(content)
    }
}

/** 外观照着 Nagisaland 网页底栏（index.html 的 .nav）画的原生底栏 */
class SuiteNavBar(ctx: Context, private val current: String, private val onPick: (String) -> Unit) : View(ctx) {
    companion object { const val TAG = "nagisuite-navbar" }

    private class Item(val key: String, val label: String, val paths: List<Path>)

    private val dp = resources.displayMetrics.density
    val barPx = (58 * dp).roundToInt()
    private val items: List<Item>

    private val bg = Paint().apply { color = Color.argb(247, 20, 20, 27) }
    private val line = Paint().apply { color = Color.parseColor("#2a2a36") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.MITER
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * dp; textAlign = Paint.Align.CENTER }
    private val muted = Color.parseColor("#6f6f82")
    private val on = Color.parseColor("#f0abfc")
    private var downIdx = -1

    init {
        fun p(vararg d: String) = d.map { PathParser.createPathFromPathData(it) }
        fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = Path().apply { addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW) }
        fun c(x: Float, y: Float, r: Float) = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        items = listOf(
            Item("lib", "游戏库", listOf(rr(3f, 3f, 10f, 10f, 1.5f), rr(14f, 3f, 21f, 10f, 1.5f), rr(3f, 14f, 10f, 21f, 1.5f), rr(14f, 14f, 21f, 21f, 1.5f))),
            Item("site", "小站", p("M3 9l1.5-5h15L21 9", "M4 9v11h16V9", "M3 9h18", "M9 20v-6h6v6")),
            Item("comic", "漫画", p("M2 5.5C4.5 4 8 4 12 6c4-2 7.5-2 10-.5V19c-2.5-1.5-6-1.5-10 .5-4-2-7.5-2-10-.5z", "M12 6v13.5")),
            Item("anime", "番剧", listOf(rr(2f, 6f, 22f, 20f, 2.5f)) + p("M8 2l4 4 4-4", "M10 10.5v5l4.5-2.5z")),
            Item("h", "H", p("M12 20.5s-7.5-4.6-9.3-9.2C1.4 8 3.6 4.5 7 4.5c2.1 0 3.6 1.2 5 3 1.4-1.8 2.9-3 5-3 3.4 0 5.6 3.5 4.3 6.8-1.8 4.6-9.3 9.2-9.3 9.2z")),
            Item("extract", "解压", p("M21 8v13H3V8", "M1 3h22v5H1z", "M10 12h4")),
            Item("emu", "模拟器", listOf(rr(2f, 7f, 22f, 19f, 4f)) + p("M7 11v4M5 13h4") + listOf(c(16f, 12f, 1f), c(18f, 14f, 1f))),
        )
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barPx + paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        canvas.drawRect(0f, 0f, w, height.toFloat(), bg)
        canvas.drawRect(0f, 0f, w, maxOf(1f, dp * 0.75f), line)
        val cell = w / items.size
        val icon = 22 * dp
        val fm = text.fontMetrics
        val lineH = 11.5f * dp * 1.5f
        val total = icon + 2 * dp + lineH
        val top = (barPx - total) / 2
        for ((i, it) in items.withIndex()) {
            val col = if (it.key == current) on else muted
            val cx = cell * i + cell / 2
            canvas.save()
            canvas.translate(cx - icon / 2, top)
            canvas.scale(icon / 24f, icon / 24f)
            stroke.color = col
            for (path in it.paths) canvas.drawPath(path, stroke)
            canvas.restore()
            text.color = col
            val baseline = top + icon + 2 * dp + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
            canvas.drawText(it.label, cx, baseline, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = ((e.x / width) * items.size).toInt().coerceIn(0, items.size - 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downIdx = idx
            MotionEvent.ACTION_UP -> {
                if (idx == downIdx && e.y >= 0 && e.y <= barPx) {
                    performClick()
                    if (items[idx].key != current) onPick(items[idx].key)
                }
                downIdx = -1
            }
            MotionEvent.ACTION_CANCEL -> downIdx = -1
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Nagisaland 网页底栏点「漫画/番剧」时启动的透明中转界面：把目标分区无动画提到最前后自己马上关掉 */
class SuiteSwitchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SuiteNav.go(this, intent?.getStringExtra(SuiteNav.EXTRA_TO) ?: "comic")
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
