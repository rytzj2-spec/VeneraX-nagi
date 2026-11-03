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
 * - [order]：全部 8 个分区的顺序（用户拖动排序）
 * - [inBar]：放在底栏的分区（3~6 个），其余放进「更多」；「更多」固定在最后
 */
object SuiteNavConfig {
    val ALL = listOf("lib", "site", "comic", "anime", "video", "h", "extract", "emu")
    val LABELS = mapOf(
        "lib" to "游戏库", "site" to "小站", "comic" to "漫画", "anime" to "番剧",
        "video" to "影视", "h" to "H", "extract" to "解压", "emu" to "模拟器", "more" to "更多",
    )
    const val MIN_BAR = 3
    const val MAX_BAR = 6
    private val DEFAULT_BAR = setOf("lib", "site", "comic", "anime", "h")
    private const val PREFS = "suite_nav"

    private val listeners = mutableListOf<WeakReference<() -> Unit>>()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun order(ctx: Context): List<String> {
        val saved = prefs(ctx).getString("order", null)?.split(',')?.filter { it in ALL }?.distinct().orEmpty()
        return saved + ALL.filter { it !in saved }
    }

    fun inBar(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString("bar", null) ?: return DEFAULT_BAR
        val s = raw.split(',').filter { it in ALL }.toSet()
        return if (s.size in MIN_BAR..MAX_BAR) s else DEFAULT_BAR
    }

    /** 底栏上的分区（按顺序，不含「更多」） */
    fun bar(ctx: Context): List<String> = inBar(ctx).let { b -> order(ctx).filter { it in b } }

    /** 「更多」里的分区 */
    fun more(ctx: Context): List<String> = inBar(ctx).let { b -> order(ctx).filter { it !in b } }

    /** 保存；返回错误信息（null = 成功） */
    fun save(ctx: Context, order: List<String>, bar: Set<String>): String? {
        if (bar.size < MIN_BAR) return "底栏至少放 $MIN_BAR 个"
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
        o.put("labels", JSONObject(LABELS as Map<*, *>))
        return o.toString()
    }

    fun addListener(l: () -> Unit) {
        synchronized(listeners) { listeners.add(WeakReference(l)) }
    }

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
