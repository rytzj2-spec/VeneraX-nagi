package com.yenaly.han1meviewer

import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.yenaly.han1meviewer.suite.SuiteCrashLog
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.yenaly_libs.base.YenalyApplication

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 17:32
 */
class HanimeApplication : YenalyApplication() {

    companion object {
        const val TAG = "HanimeApplication"

        init {
            SmartRefreshLayout.setDefaultRefreshHeaderCreator { context, _ ->
                return@setDefaultRefreshHeaderCreator MaterialHeader(context)
            }
            SmartRefreshLayout.setDefaultRefreshFooterCreator { context, _ ->
                return@setDefaultRefreshFooterCreator ClassicsFooter(context)
            }
        }
    }

    /**
     * 已经在 [HInitializer] 中处理了
     */
    override val isDefaultCrashHandlerEnabled: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // Nagisaland suite：整个进程的 Application 就是这个类（漫画、番剧也在同一进程），
        // 崩溃只记录不接管；动态取色只给 H 自己的界面（com.yenaly.*），不影响其它分区。
        SuiteCrashLog.install(this)
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { activity, _ -> activity.javaClass.name.startsWith("com.yenaly.") }
                .build()
        )
        HProxySelector.rebuildNetwork()

        initNotificationChannel()
    }

    private fun initNotificationChannel() {
        val nm = NotificationManagerCompat.from(this)

        val hanimeDownloadChannel = NotificationChannelCompat.Builder(
            DOWNLOAD_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("Hanime Download").build()
        nm.createNotificationChannel(hanimeDownloadChannel)

        val appUpdateChannel = NotificationChannelCompat.Builder(
            UPDATE_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("App Update").build()
        nm.createNotificationChannel(appUpdateChannel)
    }
}