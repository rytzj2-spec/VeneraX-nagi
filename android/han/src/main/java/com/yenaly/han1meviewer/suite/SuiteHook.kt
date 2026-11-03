package com.yenaly.han1meviewer.suite

import android.app.Activity
import android.util.Log

/**
 * Nagisaland suite：H 模块是 library，拿不到 app 模块的类，所以用反射调用
 * io.github.kyosee.venera.suite.SuiteNav（app 模块，proguard 里保留了）。
 * 单独编译 Han1meViewer 时找不到这个类，就什么都不做。
 */
object SuiteHook {
    private const val TAG = "SuiteHook"
    private const val NAV = "io.github.kyosee.venera.suite.SuiteNav"
    const val SECTION_H = "h"

    private val nav: Class<*>? by lazy {
        try {
            Class.forName(NAV)
        } catch (_: Throwable) {
            null
        }
    }

    /** 在 H 主界面挂上常驻底栏。 */
    @JvmStatic
    fun attach(activity: Activity) {
        invoke("attach", activity, SECTION_H)
    }

    /** 主界面按返回键退出时，改成把整个任务退到后台（保留各分区状态）。返回 true 表示已拦截。 */
    @JvmStatic
    fun interceptFinish(activity: Activity): Boolean =
        invoke("interceptFinish", activity) as? Boolean ?: false

    private fun invoke(name: String, vararg args: Any): Any? {
        val cls = nav ?: return null
        return try {
            val method = cls.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
                ?: return null
            val receiver = try {
                cls.getField("INSTANCE").get(null)
            } catch (_: NoSuchFieldException) {
                null
            }
            method.invoke(receiver, *args)
        } catch (e: Throwable) {
            Log.w(TAG, "SuiteNav.$name failed", e)
            null
        }
    }
}
