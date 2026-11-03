package com.yenaly.han1meviewer

import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.color.DynamicColors
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.ui.viewmodel.AppViewModel
import com.yenaly.yenaly_libs.base.YenalyApplication
import com.yenaly.yenaly_libs.utils.LanguageHelper

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 17:32
 */

/** Nagisaland 统一主题：H 分区不套壁纸动态取色，用 themes.xml 里的 Nagisaland 配色 */
private const val NAGI_UNIFIED_THEME = true

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
        com.yenaly.han1meviewer.suite.SuiteCrashLog.install(this)
        // Nagisaland 统一主题：不再套壁纸动态取色（会盖掉 themes.xml 里的 Nagisaland 配色）。
        // 想恢复就把 NAGI_UNIFIED_THEME 改成 false（只作用于 H 分区自己的界面）
        if (!NAGI_UNIFIED_THEME) DynamicColors.applyToActivitiesIfAvailable(
            this,
            com.google.android.material.color.DynamicColorsOptions.Builder()
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