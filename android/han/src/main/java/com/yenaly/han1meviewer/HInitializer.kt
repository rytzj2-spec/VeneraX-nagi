package com.yenaly.han1meviewer

import android.content.Context
import com.yenaly.yenaly_libs.base.YenalyInitializer

class HInitializer : YenalyInitializer() {
    override fun create(context: Context) {
        super.create(context)
        // Nagisaland suite：不接管崩溃（原版会重启整个进程），崩溃记录见 SuiteCrashLog
    }
}