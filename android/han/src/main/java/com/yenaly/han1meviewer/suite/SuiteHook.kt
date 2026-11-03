package com.yenaly.han1meviewer.suite

import android.app.Activity

/**
 * Nagisaland suite：H 分区接入常驻底栏。SuiteNav 在 app 模块里（这个库依赖不到），用反射调用；
 * 单独编译 Han 时找不到 SuiteNav，什么都不做。
 */
object SuiteHook {
    private val nav: Class<*>? by lazy {
        try { Class.forName("io.github.kyosee.venera.suite.SuiteNav") } catch (e: Throwable) { null }
    }

    @JvmStatic
    fun attach(a: Activity) {
        try { nav?.getMethod("attach", Activity::class.java, String::class.java)?.invoke(null, a, "h") } catch (_: Throwable) {}
    }

    @JvmStatic
    fun interceptFinish(a: Activity): Boolean = try {
        nav?.getMethod("interceptFinish", Activity::class.java)?.invoke(null, a) as? Boolean ?: false
    } catch (_: Throwable) { false }
}
