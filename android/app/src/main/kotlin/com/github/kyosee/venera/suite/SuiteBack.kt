package io.github.kyosee.venera.suite

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi

/** 手势相关的设置（「流畅度设置」里的「手势」） */
object SuiteGesture {
    private const val PREFS = "suite_perf"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 分区首页从左边缘往右划 = 打开这个分区的设置（Android 14+ 才分得清左右边缘），默认开 */
    fun leftEdgeSettings(ctx: Context) = prefs(ctx).getBoolean("left_edge_settings", true)
    fun setLeftEdgeSettings(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("left_edge_settings", on).apply()

    /** 在分区栏上左滑打开设置（v0.19.4 的做法），默认关 */
    fun barSwipeSettings(ctx: Context) = prefs(ctx).getBoolean("bar_swipe_settings", false)
    fun setBarSwipeSettings(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("bar_swipe_settings", on).apply()

    const val ROOT_LIB = "lib"
    const val ROOT_BACKGROUND = "background"
    const val ROOT_NONE = "none"
    val ROOT_LABELS = linkedMapOf(ROOT_BACKGROUND to "退到后台", ROOT_LIB to "回到游戏库", ROOT_NONE to "什么也不做")

    /** 分区首页按返回时：退到后台（默认）/ 回到游戏库 / 什么也不做 */
    fun rootBack(ctx: Context): String =
        prefs(ctx).getString("root_back", ROOT_BACKGROUND)?.takeIf { it in ROOT_LABELS } ?: ROOT_BACKGROUND
    fun setRootBack(ctx: Context, v: String) = prefs(ctx).edit().putString("root_back", v).apply()
}

/**
 * 返回手势从哪边划进来的（Android 14+ 的 BackEvent.swipeEdge）。
 *
 * 各分区首页的界面（漫画 / 番剧 / 影视 / H / 主界面）上挂一个最高优先级的返回回调：记下边缘，然后照常交给界面自己处理返回
 * （Flutter 退上一页、H 的导航、主界面网页）。只有界面自己退到根、要关掉自己时才会走到 [SuiteNav.interceptFinish]，
 * 那里用 [consumeLeftEdge] 判断这次是不是左边缘 → 打开设置；二级页面里的返回不会走到那里，照常退一页。
 *
 * 不用 setSystemGestureExclusionRects 抢系统手势；Android 13 及以下拿不到方向，两边都按「分区首页返回时」处理。
 * 需要界面在 manifest 里 enableOnBackInvokedCallback="true"（否则系统不走回调，也拿不到方向）。
 */
object SuiteBack {
    private const val EDGE_LEFT = 0   // BackEvent.EDGE_LEFT
    @Volatile private var lastEdge = -1
    @Volatile private var lastAt = 0L

    @JvmStatic
    fun install(a: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            if (Build.VERSION.SDK_INT >= 34) Api34.register(a) else Api33.register(a)
        } catch (e: Throwable) {
            SuitePerf.error(a, "注册返回手势回调失败", e)
        }
    }

    /** 刚才（1.5 秒内）那次返回是不是从左边缘划的；问过一次就清掉 */
    fun consumeLeftEdge(): Boolean {
        val ok = lastEdge == EDGE_LEFT && SystemClock.uptimeMillis() - lastAt < 1500
        lastEdge = -1
        return ok
    }

    internal fun onInvoked(a: Activity, edge: Int) {
        lastEdge = edge
        lastAt = SystemClock.uptimeMillis()
        try {
            // 照常的返回：ComponentActivity（漫画、H）走 androidx 的返回分发；其它（番剧、影视的 FlutterActivity、主界面）走 onBackPressed
            if (a is ComponentActivity) {
                a.onBackPressedDispatcher.onBackPressed()
            } else {
                @Suppress("DEPRECATION")
                a.onBackPressed()
            }
        } catch (e: Throwable) {
            SuitePerf.error(a, "返回处理失败", e)
        }
    }

    @RequiresApi(33)
    private object Api33 {
        fun register(a: Activity) {
            a.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                OnBackInvokedCallback { onInvoked(a, -1) },
            )
        }
    }

    @RequiresApi(34)
    private object Api34 {
        fun register(a: Activity) {
            val cb = object : OnBackAnimationCallback {
                private var edge = -1
                override fun onBackStarted(backEvent: BackEvent) { edge = backEvent.swipeEdge }
                override fun onBackCancelled() { edge = -1 }
                override fun onBackInvoked() {
                    val e = edge
                    edge = -1
                    onInvoked(a, e)
                }
            }
            a.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
        }
    }
}
