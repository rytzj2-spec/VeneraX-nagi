package com.yenaly.han1meviewer.util

import android.app.Activity
import android.util.Log
import androidx.fragment.app.Fragment

// Nagisaland suite：去掉了 Firebase，统计改为只打日志
fun Activity.logScreenViewEvent(fragment: Fragment) {
    Log.d("logScreenViewEvent", "screenName: " + javaClass.simpleName + "-" + fragment.javaClass.simpleName)
}

/** 代替 FirebaseCrashlytics：什么都不上报 */
object NoCrashlytics {
    fun setCustomKey(key: String, value: Any?) {}
    fun log(msg: String) {}
    fun recordException(e: Throwable) {}
}
