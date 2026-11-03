package io.github.kyosee.venera.suite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.startup.Initializer
import java.lang.ref.WeakReference

/**
 * Nagisaland suite：多分区之间的切换。
 *
 * 各分区各是一个 Activity，都在同一个任务里常驻，用 `REORDER_TO_FRONT | NO_ANIMATION` 切换，状态保留：
 * - Nagisaland 主界面（WebView，游戏库 / 小站 / 解压 / 模拟器是它网页里的 tab）
 * - 漫画：VeneraX [io.github.kyosee.venera.MainActivity]
 * - 番剧：Kazumi [io.github.kyosee.venera.kazumi.KazumiActivity]
 * - H：Han1meViewer `com.yenaly.han1meviewer.ui.activity.MainActivity`（library 模块，通过反射调用这里）
 *
 * 漫画 / 番剧 / H 三个界面底部挂 [SuiteNavBar]，样式照 Nagisaland 网页底栏。
 * H 模块用反射调用 [attach] / [interceptFinish]，proguard 里保留了这个类。
 */
object SuiteNav {
    private const val TAG = "SuiteNav"

    const val COMIC = "comic"
    const val ANIME = "anime"
    const val H = "h"
    const val GAMES = "games"
    const val SITE = "site"
    const val UNZIP = "unzip"
    const val EMULATOR = "emulator"

    const val COMIC_ACTIVITY = "io.github.kyosee.venera.MainActivity"
    const val ANIME_ACTIVITY = "io.github.kyosee.venera.kazumi.KazumiActivity"
    const val H_ACTIVITY = "com.yenaly.han1meviewer.ui.activity.MainActivity"

    /** 底栏顺序，和 Nagisaland 网页底栏一致。 */
    class Section(val id: String, val label: String, val activity: String?)

    val sections = listOf(
        Section(GAMES, "游戏库", null),
        Section(SITE, "小站", null),
        Section(COMIC, "漫画", COMIC_ACTIVITY),
        Section(ANIME, "番剧", ANIME_ACTIVITY),
        Section(H, "H", H_ACTIVITY),
        Section(UNZIP, "解压", null),
        Section(EMULATOR, "模拟器", null),
    )

    private val ownActivities = setOf(COMIC_ACTIVITY, ANIME_ACTIVITY, H_ACTIVITY)

    private val handler = Handler(Looper.getMainLooper())

    /** 还活着的 Activity（弱引用），用来找 Nagisaland 主界面的 WebView。 */
    private val live = LinkedHashMap<String, WeakReference<Activity>>()

    /** Nagisaland 主界面被系统回收过时，等它重新打开后再点的 tab（类名 to 分区）。 */
    private var pendingTab: Pair<String, Section>? = null

    /** 不是 Nagisaland 主界面的包（VeneraX / Kazumi / H 以及它们带的库）。 */
    private val ownPackages = listOf("io.github.kyosee.venera.", "com.yenaly.", "com.pichillilorenzo.")

    @Volatile
    private var callbacksInstalled = false

    fun section(id: String?): Section? {
        if (id == null) return null
        val key = id.trim().lowercase()
        return sections.firstOrNull { it.id == key || it.label.lowercase() == key }
            ?: when (key) {
                "manga", "venera", "venerax" -> sections.first { it.id == COMIC }
                "bangumi", "kazumi", "video" -> sections.first { it.id == ANIME }
                "hanime", "han", "han1me", "han1meviewer" -> sections.first { it.id == H }
                "library", "game", "home" -> sections.first { it.id == GAMES }
                "extract", "unarchive" -> sections.first { it.id == UNZIP }
                "emu" -> sections.first { it.id == EMULATOR }
                else -> null
            }
    }

    fun sectionOf(activity: Activity): Section? =
        sections.firstOrNull { it.activity == activity.javaClass.name }

    // <editor-fold desc="切换">

    /** 从 [from] 切到 [sectionId]。 */
    @JvmStatic
    fun go(from: Activity, sectionId: String) {
        val section = section(sectionId) ?: run {
            Log.w(TAG, "unknown section $sectionId")
            return
        }
        install(from.application)
        if (section.activity != null) {
            if (from.javaClass.name == section.activity) return
            start(from, Intent().setClassName(from, section.activity))
        } else {
            goNagisaland(from, section)
        }
    }

    private fun start(from: Activity, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            from.startActivity(intent)
            noTransition(from)
        } catch (e: Exception) {
            Log.e(TAG, "start ${intent.component} failed", e)
            Toast.makeText(from, "切换失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun noTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            activity.overridePendingTransition(0, 0)
        }
    }

    private fun goNagisaland(from: Activity, section: Section) {
        val main = nagisalandActivity(from)
        if (main == null) {
            Toast.makeText(from, "「${section.label}」在 Nagisaland 主界面里，这个安装包没有合并 Nagisaland", Toast.LENGTH_SHORT).show()
            return
        }
        val alive = live[main]?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (alive != null) {
            clickTab(alive, section, retries = 0)
        } else {
            pendingTab = main to section
        }
        start(from, Intent().setClassName(from, main).putExtra("nagi_tab", section.id))
    }

    private fun isOwn(className: String) = ownPackages.any { className.startsWith(it) }

    private var nagisalandMain: String? = null

    /** Nagisaland 主界面：本包里不属于漫画 / 番剧 / H 的那个桌面入口（合并进 Nagisaland 后才有）。 */
    private fun nagisalandActivity(context: Context): String? {
        nagisalandMain?.let { return it }
        return try {
            val pm = context.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(query, PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { info -> info.activityInfo.targetActivity ?: info.activityInfo.name }
                .map { if (it.startsWith(".")) context.packageName + it else it }
                .firstOrNull { !isOwn(it) }
                ?.also { nagisalandMain = it }
        } catch (e: Exception) {
            Log.w(TAG, "query launcher failed", e)
            null
        }
    }

    private fun findWebView(view: View?): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /**
     * 在 Nagisaland 的网页里点底栏按钮（`#nav button[data-tab]`）。按文字匹配，不依赖 data-tab 的具体值。
     */
    private fun clickTab(activity: Activity, section: Section, retries: Int) {
        val webView = findWebView(activity.window?.decorView)
        if (webView == null) {
            if (retries < 10) handler.postDelayed({ clickTab(activity, section, retries + 1) }, 300)
            return
        }
        val label = section.label.replace("'", "")
        val js = """
            (function(label, id) {
              var sels = ['#nav button[data-tab]', '.nav button[data-tab]', '#nav button', '.nav button', 'nav button', '[data-tab]'];
              for (var i = 0; i < sels.length; i++) {
                var list = document.querySelectorAll(sels[i]);
                for (var j = 0; j < list.length; j++) {
                  var b = list[j];
                  var tab = b.getAttribute('data-tab') || '';
                  var text = (b.textContent || '').replace(/\s+/g, '');
                  if (tab === id || (text && text.indexOf(label) >= 0)) { b.click(); return 'ok'; }
                }
              }
              return 'none';
            })('$label', '${section.id}')
        """.trimIndent()
        try {
            webView.evaluateJavascript(js) { result ->
                if (result?.contains("ok") != true && retries < 10) {
                    // 网页还没加载完，稍后再试
                    handler.postDelayed({ clickTab(activity, section, retries + 1) }, 500)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "clickTab failed", e)
        }
    }

    // </editor-fold>

    // <editor-fold desc="底栏">

    /** 在 [activity] 底部挂上常驻底栏，[current] 是当前分区。要在 setContentView 之后调用。 */
    @JvmStatic
    fun attach(activity: Activity, current: String) {
        install(activity.application)
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(SuiteNavBar.TAG) != null) return
        val bar = SuiteNavBar(activity, current)
        content.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(content) { parent, insets ->
            dispatchInsets(activity, parent as ViewGroup, bar, insets)
            WindowInsetsCompat.CONSUMED
        }
        content.requestApplyInsets()
    }

    /**
     * 底栏可见时：底栏自己垫上导航栏的高度，其余内容向上让出底栏的位置，
     * 并且收到的 insets 里不再含底部导航栏（否则 Flutter 会再留一次空白）。
     * 沉浸（隐藏系统栏）、键盘弹出、横屏、画中画时底栏隐藏。
     */
    private fun dispatchInsets(activity: Activity, content: ViewGroup, bar: SuiteNavBar, insets: WindowInsetsCompat) {
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val show = shouldShow(activity, insets)
        bar.visibility = if (show) View.VISIBLE else View.GONE
        val adjusted = if (show) {
            bar.setPadding(nav.left, 0, nav.right, nav.bottom)
            val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(nav.left, nav.top, nav.right, 0))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(tappable.left, tappable.top, tappable.right, 0))
                .build()
        } else insets
        val margin = if (show) bar.barHeight + nav.bottom else 0
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child === bar) continue
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && lp.bottomMargin != margin) {
                lp.bottomMargin = margin
                child.layoutParams = lp
            }
            ViewCompat.dispatchApplyWindowInsets(child, adjusted)
        }
    }

    private fun shouldShow(activity: Activity, insets: WindowInsetsCompat): Boolean {
        if (Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode) return false
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return false
        if (insets.isVisible(WindowInsetsCompat.Type.ime())) return false
        // 沉浸模式（全屏阅读 / 全屏播放）时系统栏是隐藏的。以状态栏为准；导航栏只在它本来有高度、
        // 却被 App 隐藏时才算（有的系统隐藏了手势条，导航栏高度本来就是 0）。
        if (!insets.isVisible(WindowInsetsCompat.Type.statusBars())) return false
        val navHidden = !insets.isVisible(WindowInsetsCompat.Type.navigationBars()) &&
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom > 0
        return !navHidden
    }

    // </editor-fold>

    /**
     * 分区主界面按返回键退出时（Flutter 的 SystemNavigator.pop、H 首页的返回）不销毁界面，
     * 而是把整个任务退到后台，下次回来各分区还是原样。返回 true 表示已经处理。
     */
    @JvmStatic
    fun interceptFinish(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isChangingConfigurations) return false
        if (activity.javaClass.name !in ownActivities) return false
        return try {
            activity.moveTaskToBack(true)
        } catch (e: Exception) {
            Log.w(TAG, "moveTaskToBack failed", e)
            false
        }
    }

    // <editor-fold desc="Activity 跟踪">

    @JvmStatic
    fun install(application: Application) {
        if (callbacksInstalled) return
        synchronized(this) {
            if (callbacksInstalled) return
            callbacksInstalled = true
            application.registerActivityLifecycleCallbacks(Tracker)
        }
    }

    private object Tracker : Application.ActivityLifecycleCallbacks {
        private fun remember(activity: Activity) {
            live[activity.javaClass.name] = WeakReference(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = remember(activity)
        override fun onActivityStarted(activity: Activity) = remember(activity)
        override fun onActivityResumed(activity: Activity) {
            remember(activity)
            val tab = pendingTab
            if (tab != null && activity.javaClass.name == tab.first) {
                pendingTab = null
                handler.postDelayed({ clickTab(activity, tab.second, retries = 0) }, 300)
            }
            // 横竖屏、画中画切换后重新算一次底栏
            activity.findViewById<View>(android.R.id.content)?.requestApplyInsets()
        }

        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            val ref = live[activity.javaClass.name]
            if (ref?.get() === activity) live.remove(activity.javaClass.name)
        }
    }

    // </editor-fold>
}

/**
 * 进程启动时（androidx.startup）就开始跟踪 Activity，这样 Nagisaland 主界面一打开就能记住它。
 */
class SuiteNavInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        (context.applicationContext as? Application)?.let(SuiteNav::install)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * 原生底栏：游戏库｜小站｜漫画｜番剧｜H｜解压｜模拟器，配色照 Nagisaland 网页（深色 / 浅色跟随系统）。
 */
@SuppressLint("ViewConstructor")
class SuiteNavBar(private val activity: Activity, private val current: String) : LinearLayout(activity) {
    companion object {
        const val TAG = "nagi_suite_nav_bar"
    }

    val barHeight: Int = dp(56)
    private var dividerColor: Int = 0
    private val dividerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    init {
        tag = TAG
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) Color.parseColor("#0f0f12") else Color.parseColor("#EFE9E3")
        val text = if (night) Color.parseColor("#a1a1aa") else Color.parseColor("#57534e")
        val textActive = if (night) Color.parseColor("#fafaf9") else Color.parseColor("#1c1917")
        background = GradientDrawable().apply {
            setColor(bg)
        }
        // 顶部细分隔线
        setWillNotDraw(false)
        dividerColor = if (night) Color.argb(15, 255, 255, 255) else Color.argb(15, 0, 0, 0)
        isClickable = true // 不让点击漏到下面的内容
        elevation = dp(8).toFloat()

        for (section in SuiteNav.sections) {
            val active = section.id == current
            val item = TextView(activity).apply {
                this.text = section.label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (active) textActive else text)
                if (active) {
                    setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.argb(40, 232, 121, 169), Color.argb(40, 167, 139, 250))
                    ).apply { cornerRadius = dp(10).toFloat() }
                } else {
                    val ripple = TypedValue()
                    if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                        setBackgroundResource(ripple.resourceId)
                    }
                }
                contentDescription = section.label
                setOnClickListener { SuiteNav.go(activity, section.id) }
            }
            addView(item, LayoutParams(0, barHeight - dp(12), 1f).apply {
                setMargins(dp(2), dp(6), dp(2), dp(6))
            })
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        dividerPaint.color = dividerColor
        canvas.drawRect(0f, 0f, width.toFloat(), dp(1).toFloat().coerceAtLeast(1f), dividerPaint)
        // 当前分区下方的渐变指示条
        val index = SuiteNav.sections.indexOfFirst { it.id == current }
        if (index >= 0 && childCount > index) {
            val child = getChildAt(index)
            val w = child.width * 0.4f
            val left = child.left + (child.width - w) / 2
            val top = child.bottom - dp(3).toFloat()
            dividerPaint.shader = android.graphics.LinearGradient(
                left, 0f, left + w, 0f,
                Color.parseColor("#e879a9"), Color.parseColor("#a78bfa"),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(left, top, left + w, top + dp(3), dp(2).toFloat(), dp(2).toFloat(), dividerPaint)
            dividerPaint.shader = null
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}

/**
 * 中转界面（Theme.NoDisplay）：给 Nagisaland 主界面用的切换入口，Nagisaland 的 smali 不用改。
 * 认这几种写法（任选其一）：
 * - 组件名：`io.github.kyosee.venera.suite.GoComic` / `GoAnime` / `GoH`（manifest 里的 activity-alias）
 * - extra：`section` / `target` / `tab` = comic | anime | h（也认 漫画 / 番剧 / H）
 * - 链接：`nagisuite://comic`、`nagisuite://anime`、`nagisuite://h`
 */
class SuiteSwitchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = resolveTarget(intent)
        if (target != null) {
            SuiteNav.go(this, target)
        } else {
            Log.w("SuiteNav", "SuiteSwitchActivity: no target in $intent")
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun resolveTarget(intent: Intent?): String? {
        intent ?: return null
        for (key in arrayOf("section", "target", "tab", "nagi_tab")) {
            intent.getStringExtra(key)?.let { if (SuiteNav.section(it) != null) return it }
        }
        intent.data?.let { uri ->
            listOfNotNull(uri.host, uri.lastPathSegment, uri.getQueryParameter("section"))
                .firstOrNull { SuiteNav.section(it) != null }?.let { return it }
        }
        val name = intent.component?.className.orEmpty().substringAfterLast('.')
        return when (name) {
            "GoComic" -> SuiteNav.COMIC
            "GoAnime" -> SuiteNav.ANIME
            "GoH" -> SuiteNav.H
            else -> null
        }
    }
}
