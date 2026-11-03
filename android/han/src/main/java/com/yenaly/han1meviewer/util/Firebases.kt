package com.yenaly.han1meviewer.util

import android.app.Activity
import android.util.Log
import androidx.fragment.app.Fragment

// Nagisaland suite：去掉了 Firebase，这里只打日志。
fun Activity.logScreenViewEvent(fragment: Fragment) {
    Log.d("logScreenViewEvent", javaClass.simpleName + "-" + fragment.javaClass.simpleName)
}
