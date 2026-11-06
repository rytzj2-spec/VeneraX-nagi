package io.github.kyosee.venera.suite

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * 底栏配置（主界面网页底栏、原生 SuiteNavBar、横屏竖排导航栏三处共用，改了马上生效）。
 *
 * - [order]：全部分区的顺序（用户拖动排序）
 * - [inBar]：放在底栏的分区（3~6 个），其余放进「更多」；「更多」固定在最后
 */
object SuiteNavConfig {
    val ALL = listOf("lib", "site", "comic", "anime", "video", "h", "extract", "emu", "companion")
    val LABELS = mapOf(
        "lib" to "游戏库", "site" to "小站", "comic" to "漫画", "anime" to "番剧",
        "companion" to "陪伴", "video" to "影视", "h" to "H", "extract" to "解压", "emu" to "模拟器", "more" to "更多",
    )
    const val MIN_BAR = 3
    const val MAX_BAR = 6
    private val DEFAULT_BAR = setOf("lib", "site", "comic", "anime", "h")
    private const val PREFS = "suite_nav"

    private val listeners = mutableListOf<WeakReference<() -> Unit>>()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 一键配置里打开了的分区（SuiteProfile；没导入过配置的自己用 = 全部） */
    fun available(ctx: Context): List<String> = ALL.filter { SuiteProfile.isEnabled(ctx, it) }

    /** 底栏最少几个：一般 3 个；能用的分区不到 3 个时就是全部 */
    fun minBar(ctx: Context): Int = minOf(MIN_BAR, available(ctx).size)

    fun order(ctx: Context): List<String> {
        val avail = available(ctx)
        val saved = prefs(ctx).getString("order", null)?.split(',')?.filter { it in avail }?.distinct().orEmpty()
        return saved + avail.filter { it !in saved }
    }

    fun inBar(ctx: Context): Set<String> {
        val avail = available(ctx).toSet()
        val min = minBar(ctx)
        val raw = prefs(ctx).getString("bar", null)
        val s = raw?.split(',')?.filter { it in avail }?.toSet()
        if (s != null && s.size in min..MAX_BAR) return s
        // 默认：原来的 5 个里能用的；不够就按顺序补
        val d = DEFAULT_BAR.filter { it in avail }.toMutableSet()
        for (k in order(ctx)) {
            if (d.size >= minOf(5, avail.size)) break
            d.add(k)
        }
        return d
    }

    /** 底栏上的分区（按顺序，不含「更多」） */
    fun bar(ctx: Context): List<String> = inBar(ctx).let { b -> order(ctx).filter { it in b } }

    /** 「更多」里的分区 */
    fun more(ctx: Context): List<String> = inBar(ctx).let { b -> order(ctx).filter { it !in b } }

    /** 保存；返回错误信息（null = 成功） */
    fun save(ctx: Context, order: List<String>, bar: Set<String>): String? {
        val min = minBar(ctx)
        if (bar.size < min) return "底栏至少放 $min 个"
        if (bar.size > MAX_BAR) return "底栏最多放 $MAX_BAR 个"
        prefs(ctx).edit()
            .putString("order", order.filter { it in ALL }.distinct().joinToString(","))
            .putString("bar", bar.filter { it in ALL }.joinToString(","))
            .apply()
        notifyChanged()
        return null
    }

    /** 「更多」里长按：放到底栏（排在底栏最后一个后面） */
    fun moveToBar(ctx: Context, key: String): String? {
        val bar = inBar(ctx)
        if (key in bar) return null
        if (bar.size >= MAX_BAR) return "底栏最多 $MAX_BAR 个，先在「编辑底栏」里拿掉一个"
        val order = order(ctx).toMutableList()
        order.remove(key)
        val lastBar = order.indexOfLast { it in bar }
        order.add(lastBar + 1, key)
        return save(ctx, order, bar + key)
    }

    fun toJson(ctx: Context): String {
        val o = JSONObject()
        o.put("bar", JSONArray(bar(ctx)))
        o.put("more", JSONArray(more(ctx)))
        o.put("order", JSONArray(order(ctx)))
        o.put("enabled", JSONArray(available(ctx)))
        o.put("labels", JSONObject(LABELS as Map<*, *>))
        return o.toString()
    }

    fun addListener(l: () -> Unit) {
        synchronized(listeners) { listeners.add(WeakReference(l)) }
    }

    /** 一键配置导入后：分区开关变了，各处底栏刷新 */
    fun notifyProfileChanged() = notifyChanged()

    private fun notifyChanged() {
        main.post {
            val ls = synchronized(listeners) {
                listeners.removeAll { it.get() == null }
                listeners.mapNotNull { it.get() }
            }
            ls.forEach { runCatching { it() } }
            SuiteNav.onNavConfigChanged()
        }
    }
}
