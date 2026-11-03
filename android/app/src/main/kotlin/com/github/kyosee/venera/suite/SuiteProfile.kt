package io.github.kyosee.venera.suite

import android.content.Context
import java.io.File

/**
 * 一键配置的「档案」：这台手机能用哪些分区、有没有导入过配置。
 *
 * - 全新安装：必须先导入别人给的 .nagi 配置（SuiteOnboardActivity）才能用，导入时决定哪些分区显示。
 * - 升级上来的（App 目录里已经有漫画 / 番剧 / 影视的数据）：当作「自己用」，直接放行，全部分区都能用。
 *   这个判断只在第一次运行这个版本时做一次（[SuiteStartup] 一开始就调用，早于任何分区写文件）。
 * - 关掉的分区：底栏、「更多」、主界面网页都不显示，也切不过去；只有再导入一份配置才能改。
 */
object SuiteProfile {
    private const val PREFS = "suite_profile"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_OWNER = "owner"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NAME = "name"

    /** gal = 游戏库 / 小站 / 解压 / 模拟器（都在主界面网页里） */
    val GAL = listOf("lib", "site", "extract", "emu")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cachedEnabled: Set<String>? = null

    /** 能不能用 App（导入过配置，或者是升级上来的老用户） */
    @JvmStatic
    fun configured(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.contains(KEY_CONFIGURED)) return p.getBoolean(KEY_CONFIGURED, false)
        val existing = hasExistingData(ctx)
        p.edit().putBoolean(KEY_CONFIGURED, existing).putBoolean(KEY_OWNER, existing).commit()
        return existing
    }

    /** 升级上来的 / 自己用的（没有被配置限制过） */
    fun isOwner(ctx: Context) = prefs(ctx).getBoolean(KEY_OWNER, false)

    /** 导入的配置名（导出时填的，例如「渚的配置」）；没有返回 null */
    fun profileName(ctx: Context): String? = prefs(ctx).getString(KEY_NAME, null)

    private fun hasExistingData(ctx: Context): Boolean {
        val f = ctx.applicationContext.filesDir
        return File(f, "appdata.json").exists() ||
            File(f, "comic_source").isDirectory ||
            File(f, "hive").isDirectory ||
            File(f, "plugins").isDirectory ||
            File(f, "nagi_video.json").exists() ||
            ctx.applicationContext.getSharedPreferences("suite_nav", Context.MODE_PRIVATE).contains("order")
    }

    /** 打开了的分区（没限制过 = 全部） */
    fun enabled(ctx: Context): Set<String> {
        cachedEnabled?.let { return it }
        val raw = prefs(ctx).getString(KEY_ENABLED, null)
        val s = raw?.split(',')?.filter { it in SuiteNavConfig.ALL }?.toSet()?.takeIf { it.isNotEmpty() }
            ?: SuiteNavConfig.ALL.toSet()
        cachedEnabled = s
        return s
    }

    @JvmStatic
    fun isEnabled(ctx: Context, key: String) = key !in SuiteNavConfig.ALL || key in enabled(ctx)

    /** 主界面网页里第一个能用的页面（gal 全关了返回 null） */
    fun firstGal(ctx: Context): String? = SuiteNavConfig.order(ctx).firstOrNull { it in GAL }

    /** 第一个能用的分区（gal 全关时 App 一打开就进这个） */
    fun firstSection(ctx: Context): String? = SuiteNavConfig.order(ctx).firstOrNull()

    /** 导入配置后写入 */
    fun applyImported(ctx: Context, enabled: Set<String>, name: String?) {
        val e = enabled.filter { it in SuiteNavConfig.ALL }.toSet().ifEmpty { SuiteNavConfig.ALL.toSet() }
        prefs(ctx).edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putString(KEY_ENABLED, SuiteNavConfig.ALL.filter { it in e }.joinToString(","))
            .apply { if (name.isNullOrBlank()) remove(KEY_NAME) else putString(KEY_NAME, name) }
            .commit()
        cachedEnabled = null
        SuiteNavConfig.notifyProfileChanged()
    }
}
