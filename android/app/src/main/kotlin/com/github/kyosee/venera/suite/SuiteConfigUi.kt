package io.github.kyosee.venera.suite

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 一键配置的界面：「更多」里的菜单、导出向导、导入确认 */
object SuiteConfigUi {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private fun Context.dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private val SECTION_ORDER = listOf("comic", "anime", "video", "h", "companion")

    fun showMenu(a: Activity) {
        val root = SuitePanels.sheet(a)
        val name = SuiteProfile.profileName(a)
        root.addView(SuitePanels.title(a, "一键配置",
            if (name != null) "当前用的是「$name」" else "把漫画源、番剧规则、影视服务器打包成一个文件发给别人，对方一键导入"))
        lateinit var dialog: Dialog
        root.addView(SuitePanels.button(a, "导出我的配置", primary = true) { dialog.dismiss(); showExport(a) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(a), LinearLayout.LayoutParams(1, a.dp(10f)))
        root.addView(SuitePanels.button(a, "导入配置文件（.nagi）") {
            dialog.dismiss()
            a.startActivity(Intent(a, SuiteOnboardActivity::class.java).putExtra(SuiteOnboardActivity.EXTRA_PICK, true))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(a), LinearLayout.LayoutParams(1, a.dp(10f)))
        root.addView(SuitePanels.button(a, "从配置链接导入") {
            dialog.dismiss()
            askLink(a) { url ->
                a.startActivity(Intent(a, SuiteOnboardActivity::class.java).putExtra(SuiteOnboardActivity.EXTRA_URL, url))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 自动更新（订阅了配置链接时）
        val sub = SuiteConfigSync.url(a)
        if (sub != null) {
            val checked = SuiteConfigSync.lastChecked(a)
            root.addView(TextView(a).apply {
                text = "自动更新：$sub" + if (checked > 0) "\n上次检查 " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(checked)) else ""
                setTextColor(SuitePanels.SUB); textSize = 12.5f
                setPadding(0, a.dp(14f), 0, a.dp(6f))
            })
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(SuitePanels.button(a, "立即检查更新") {
                dialog.dismiss()
                Toast.makeText(a, "正在检查…", Toast.LENGTH_SHORT).show()
                SuiteConfigSync.checkInBackground(a, force = true) { msg ->
                    if (SuiteConfigSync.hasPending(a)) confirmRestart(a, msg)
                    else Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
            row.addView(SuitePanels.button(a, "取消自动更新") {
                dialog.dismiss()
                SuiteConfigSync.unsubscribe(a)
                Toast.makeText(a, "不再自动更新配置", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
        }
        dialog = SuitePanels.bottomDialog(a, root)
        dialog.show()
    }

    /** 输入配置链接 */
    fun askLink(a: Activity, onOk: (String) -> Unit) {
        val root = SuitePanels.sheet(a)
        root.addView(SuitePanels.title(a, "输入配置链接", "别人发给你的 .nagi 文件的网址（http / https）。导入后会自动从这个链接更新"))
        val edit = EditText(a).apply {
            hint = "https://…/xxx.nagi"
            setTextColor(SuitePanels.TEXT); setHintTextColor(SuitePanels.SUB); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        root.addView(edit)
        lateinit var dialog: Dialog
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(14f), 0, 0) }
        row.addView(SuitePanels.button(a, "取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(SuitePanels.button(a, "下一步", primary = true) {
            val u = edit.text.toString().trim()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
                Toast.makeText(a, "链接要以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show(); return@button
            }
            dialog.dismiss()
            onOk(u)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = SuitePanels.bottomDialog(a, root)
        dialog.show()
    }

    private fun confirmRestart(a: Activity, msg: String) {
        val root = SuitePanels.sheet(a)
        root.addView(SuitePanels.title(a, "配置有更新", "$msg。现在重启 App 马上生效，或者等下次打开。"))
        lateinit var dialog: Dialog
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(10f), 0, 0) }
        row.addView(SuitePanels.button(a, "下次再说") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(SuitePanels.button(a, "现在重启", primary = true) { dialog.dismiss(); restartApp(a) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = SuitePanels.bottomDialog(a, root)
        dialog.show()
    }

    // ---------------------------------------------------------------- 导出

    fun showExport(a: Activity) {
        val o = SuiteConfigPack.Options()
        val mine = SuiteNavConfig.available(a).toSet()
        o.enabled = mine.toMutableSet()

        val root = SuitePanels.sheet(a)
        root.addView(SuitePanels.title(a, "导出一键配置", "对方导入后，下面打开的分区才会显示"))

        val nameEdit = EditText(a).apply {
            hint = "配置名（例如：渚的配置）"
            setText(SuiteProfile.profileName(a) ?: "")
            setTextColor(SuitePanels.TEXT); setHintTextColor(SuitePanels.SUB); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        root.addView(nameEdit)
        val urlEdit = EditText(a).apply {
            hint = "配置链接（可选）：https://…/xxx.nagi"
            setText(SuiteConfigSync.url(a).takeIf { SuiteProfile.isOwner(a) } ?: "")
            setTextColor(SuitePanels.TEXT); setHintTextColor(SuitePanels.SUB); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        root.addView(urlEdit)
        root.addView(TextView(a).apply {
            text = "填了以后，对方导入时会记住这个链接，以后自动更新：你重新导出，把新文件上传到同一个地址（覆盖旧文件）就行。" +
                "链接要一直能访问：GitHub / Gitee 仓库里文件的 raw 链接、自己的 NAS、网盘直链都可以。"
            setTextColor(SuitePanels.SUB); textSize = 12f
        })

        fun header(t: String, sub: String? = null) {
            root.addView(TextView(a).apply {
                text = t; setTextColor(SuitePanels.PINK); textSize = 13.5f; paint.isFakeBoldText = true
                setPadding(0, a.dp(14f), 0, a.dp(2f))
            })
            if (sub != null) root.addView(TextView(a).apply { text = sub; setTextColor(SuitePanels.SUB); textSize = 12f })
        }

        val warn = TextView(a).apply {
            text = "⚠ 带上个人数据后，拿到这个文件的人能登录你的账号、看到你的收藏和历史。只发给信得过的人；也不要放到公开的配置链接上。"
            setTextColor(0xFFF87171.toInt()); textSize = 12.5f; visibility = View.GONE
            setPadding(0, a.dp(6f), 0, 0)
        }
        fun refreshWarn() {
            warn.visibility = if (o.comicAccounts || o.comicFavorites || o.comicHistory || o.videoAccounts) View.VISIBLE else View.GONE
        }

        fun sw(t: String, sub: String?, value: Boolean, indent: Boolean = false, onChange: (Boolean) -> Unit): android.widget.Switch {
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(if (indent) a.dp(18f) else 0, a.dp(4f), 0, a.dp(4f))
            }
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(a).apply { text = t; setTextColor(SuitePanels.TEXT); textSize = 15f })
            if (sub != null) texts.addView(TextView(a).apply { text = sub; setTextColor(SuitePanels.SUB); textSize = 12f })
            box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val s = SuitePanels.nagiSwitch(a).apply { isChecked = value; setOnCheckedChangeListener { _, on -> onChange(on) } }
            box.addView(s)
            root.addView(box)
            return s
        }

        header("带上哪些配置")
        sw("漫画源和漫画设置", "漫画源、阅读设置（不含密码、API Key、本机路径）", o.comic) { o.comic = it }
        sw("番剧规则", "番剧的搜索 / 播放规则", o.anime) { o.anime = it }
        sw("影视服务器", "Emby / Jellyfin 服务器地址", o.video) { o.video = it }

        header("个人数据", "默认不带")
        sw("漫画账号和登录", "各漫画源的账号、令牌、登录 Cookie", false) { o.comicAccounts = it; refreshWarn() }
        sw("漫画收藏", "本地收藏、稍后再看", false) { o.comicFavorites = it; refreshWarn() }
        sw("漫画历史", "阅读历史", false) { o.comicHistory = it; refreshWarn() }
        sw("影视登录", "对方不用再登录你的服务器（建议给对方单独建一个账号）", false) { o.videoAccounts = it; refreshWarn() }
        root.addView(warn)

        header("对方能用哪些分区", "关掉的分区对方看不到，只有再导入一份配置才能打开")
        val galKeys = SuiteProfile.GAL.filter { it in mine }
        val galSwitches = mutableListOf<android.widget.Switch>()
        if (galKeys.isNotEmpty()) {
            sw("gal（游戏库 / 小站 / 解压 / 模拟器）", null, true) { on ->
                galSwitches.forEach { it.isChecked = on }
            }
            for (k in galKeys) {
                galSwitches += sw(SuiteNavItems.label(k), null, true, indent = true) { on ->
                    if (on) o.enabled.add(k) else o.enabled.remove(k)
                }
            }
        }
        for (k in SECTION_ORDER.filter { it in mine }) {
            sw(SuiteNavItems.label(k), null, true) { on -> if (on) o.enabled.add(k) else o.enabled.remove(k) }
        }

        lateinit var dialog: Dialog
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(16f), 0, 0) }
        row.addView(SuitePanels.button(a, "取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(SuitePanels.button(a, "导出", primary = true) {
            if (o.enabled.isEmpty()) {
                Toast.makeText(a, "至少打开一个分区", Toast.LENGTH_SHORT).show(); return@button
            }
            o.name = nameEdit.text.toString().trim()
            o.updateUrl = urlEdit.text.toString().trim()
            if (o.updateUrl.isNotEmpty() && !o.updateUrl.startsWith("http://", true) && !o.updateUrl.startsWith("https://", true)) {
                Toast.makeText(a, "配置链接要以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show(); return@button
            }
            dialog.dismiss()
            runExport(a, o)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = SuitePanels.bottomDialog(a, ScrollView(a).apply { addView(root) })
        dialog.show()
    }

    private fun runExport(a: Activity, o: SuiteConfigPack.Options) {
        val toast = Toast.makeText(a, "正在导出…", Toast.LENGTH_SHORT).also { it.show() }
        io.execute {
            val result = runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val base = (o.name.ifBlank { "Nagisaland配置" }).replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                val dirs = listOf(
                    File(Environment.getExternalStorageDirectory(), "Nagisaland/config"),
                    File(a.getExternalFilesDir(null), "config"),
                )
                var lastError: Throwable? = null
                for (d in dirs) {
                    try {
                        d.mkdirs()
                        val f = File(d, "$base-$stamp${SuiteConfigPack.EXT}")
                        f.outputStream().use { SuiteConfigPack.export(a, o, it) }
                        return@runCatching f
                    } catch (e: Throwable) { lastError = e }
                }
                throw lastError ?: IllegalStateException("没地方写文件")
            }
            main.post {
                toast.cancel()
                result.onSuccess { showExported(a, it) }.onFailure {
                    SuitePerf.error(a, "导出配置失败", it)
                    Toast.makeText(a, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showExported(a: Activity, f: File) {
        val root = SuitePanels.sheet(a)
        val shown = f.absolutePath.replace(Environment.getExternalStorageDirectory().absolutePath, "内部存储")
        root.addView(SuitePanels.title(a, "导出好了", "$shown（${f.length() / 1024} KB）\n发给对方，对方第一次打开 App 时选这个文件，或者在微信 / QQ 里点开文件选「用 Nagisaland 打开」。"))
        lateinit var dialog: Dialog
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(10f), 0, 0) }
        row.addView(SuitePanels.button(a, "完成") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(SuitePanels.button(a, "分享", primary = true) {
            try {
                val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                a.startActivity(Intent.createChooser(send, "发送配置"))
            } catch (e: Throwable) {
                Toast.makeText(a, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = SuitePanels.bottomDialog(a, root)
        dialog.show()
    }

    // ---------------------------------------------------------------- 导入

    private val PART_LABELS = mapOf(
        "comic" to "漫画源和设置", "anime" to "番剧规则", "video" to "影视服务器",
        "comicAccounts" to "漫画账号和登录", "comicFavorites" to "漫画收藏", "comicHistory" to "漫画历史",
        "videoAccounts" to "影视登录",
    )

    /** 读文件 → 给人看内容 → 确认后导入；[restart] = 导入后重启 App（App 已经在用的时候） */
    fun confirmImport(a: Activity, uri: Uri, restart: Boolean, sourceUrl: String? = null, onCancel: () -> Unit) {
        io.execute {
            val m = runCatching { SuiteConfigPack.readManifest(a, uri) }
            main.post {
                m.onFailure {
                    Toast.makeText(a, "打不开配置：${it.message}", Toast.LENGTH_LONG).show(); onCancel()
                }.onSuccess { showConfirm(a, uri, it, restart, sourceUrl, onCancel) }
            }
        }
    }

    private fun showConfirm(a: Activity, uri: Uri, m: SuiteConfigPack.Manifest, restart: Boolean, sourceUrl: String?, onCancel: () -> Unit) {
        val root = SuitePanels.sheet(a)
        root.addView(SuitePanels.title(a, "导入「${m.name.ifBlank { "未命名配置" }}」", m.createdAt.takeIf { it.isNotBlank() }?.let { "导出于 $it" }))
        fun line(t: String, c: Int = SuitePanels.TEXT) = root.addView(TextView(a).apply {
            text = t; setTextColor(c); textSize = 14f; setPadding(0, a.dp(4f), 0, a.dp(4f))
        })
        val parts = m.parts.mapNotNull { PART_LABELS[it] }
        line("包含：" + (parts.ifEmpty { listOf("只有分区设置") }).joinToString("、"))
        line("能用的分区：" + SuiteNavConfig.ALL.filter { it in m.enabled }.joinToString("、") { SuiteNavItems.label(it) })
        if (m.hasPersonal) line("这份配置带着别人的账号或个人数据。", 0xFFFBBF24.toInt())
        val link = m.updateUrl.ifBlank { sourceUrl.orEmpty() }
        if (link.isNotBlank()) line("以后会自动从这个链接更新：$link", SuitePanels.SUB)
        if (SuiteProfile.configured(a)) {
            line("导入后这台手机只显示上面这些分区；同名的漫画源、番剧规则会被替换，已有的登录会保留。" +
                if (restart) "导入完 App 会自动重启。" else "", SuitePanels.SUB)
        }
        lateinit var dialog: Dialog
        var started = false
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(14f), 0, 0) }
        row.addView(SuitePanels.button(a, "取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(SuitePanels.button(a, "导入", primary = true) {
            started = true
            dialog.dismiss()
            runImport(a, uri, restart, sourceUrl, onCancel)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = SuitePanels.bottomDialog(a, root)
        dialog.setOnDismissListener { if (!started) onCancel() }
        dialog.show()
    }

    private fun runImport(a: Activity, uri: Uri, restart: Boolean, sourceUrl: String?, onCancel: () -> Unit) {
        val toast = Toast.makeText(a, "正在导入…", Toast.LENGTH_SHORT).also { it.show() }
        io.execute {
            val r = runCatching { SuiteConfigPack.import(a, uri, sourceUrl) }
            main.post {
                toast.cancel()
                r.onFailure {
                    SuitePerf.error(a, "导入配置失败", it)
                    Toast.makeText(a, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
                    onCancel()
                }.onSuccess { msg ->
                    Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
                    if (restart) restartApp(a) else openLauncher(a)
                }
            }
        }
    }

    private fun launchIntent(a: Activity): Intent =
        (a.packageManager.getLaunchIntentForPackage(a.packageName) ?: Intent())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun openLauncher(a: Activity) {
        a.startActivity(launchIntent(a))
        a.finish()
    }

    /** 各分区可能已经把旧配置读进内存了：杀掉进程重开（SuiteRestartActivity 在单独的进程里做） */
    fun restartApp(a: Activity) {
        a.startActivity(Intent(a, SuiteRestartActivity::class.java)
            .putExtra(SuiteRestartActivity.EXTRA_PID, Process.myPid())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        a.finish()
    }
}

/**
 * 导入配置的页面：
 * - 全新安装第一次打开（还没导入过配置）：只能在这里导入，导入完才进 App；
 * - 「更多 → 一键配置 → 导入」：直接弹出选文件；
 * - 在微信 / QQ / 文件管理里点开 .nagi 选「用 Nagisaland 打开」：直接进确认。
 */
class SuiteOnboardActivity : Activity() {
    companion object {
        const val EXTRA_PICK = "pick"
        const val EXTRA_URL = "url"
        private const val REQ_PICK = 0x4e41
    }

    private val configured by lazy { SuiteProfile.configured(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(0xFF0F0F12.toInt())
        if (!configured) buildWelcome()
        val data = intent?.data
        when {
            data != null && savedInstanceState == null -> importUri(data)
            intent?.getBooleanExtra(EXTRA_PICK, false) == true && savedInstanceState == null -> pick()
            intent?.getStringExtra(EXTRA_URL) != null && savedInstanceState == null -> importLink(intent.getStringExtra(EXTRA_URL)!!)
        }
    }

    private fun buildWelcome() {
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((28 * dp).toInt(), 0, (28 * dp).toInt(), 0)
        }
        col.addView(TextView(this).apply {
            text = "Nagisaland"; setTextColor(SuitePanels.PINK); textSize = 30f; paint.isFakeBoldText = true; gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "第一次使用，先导入别人发给你的配置文件（.nagi）。\n导入后漫画源、番剧规则、影视服务器都会自动配好。"
            setTextColor(SuitePanels.SUB); textSize = 14.5f; gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, (26 * dp).toInt())
            setLineSpacing(0f, 1.25f)
        })
        col.addView(SuitePanels.button(this, "选择配置文件", primary = true) { pick() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * dp).toInt()))
        col.addView(View(this), LinearLayout.LayoutParams(1, (12 * dp).toInt()))
        col.addView(SuitePanels.button(this, "输入配置链接") { SuiteConfigUi.askLink(this) { importLink(it) } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * dp).toInt()))
        col.addView(TextView(this).apply {
            text = "在微信 / QQ 里收到文件后，也可以直接点开文件，选「用 Nagisaland 打开」。"
            setTextColor(0xFF71717A.toInt()); textSize = 12.5f; gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, 0)
        })
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF0F0F12.toInt())
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })
    }

    @Suppress("DEPRECATION")
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Throwable) {
            Toast.makeText(this, "打不开文件选择：${e.message}", Toast.LENGTH_LONG).show()
            done()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { done(); return }
        importUri(uri)
    }

    /** 从配置链接导入：先下载到缓存，再走和文件一样的确认 */
    private fun importLink(url: String) {
        Toast.makeText(this, "正在下载配置…", Toast.LENGTH_SHORT).show()
        val target = File(cacheDir, "link_import.nagi")
        Thread {
            val r = runCatching { SuiteConfigSync.download(url, target) }
            runOnUiThread {
                r.onSuccess { SuiteConfigUi.confirmImport(this, Uri.fromFile(target), restart = configured, sourceUrl = url) { done() } }
                    .onFailure {
                        Toast.makeText(this, "下载配置失败：${it.message}", Toast.LENGTH_LONG).show()
                        done()
                    }
            }
        }.start()
    }

    private fun importUri(uri: Uri) {
        // 已经在用的 App 导入 = 各分区可能已经读过旧配置，导入完重启
        SuiteConfigUi.confirmImport(this, uri, restart = configured, sourceUrl = null) { done() }
    }

    /** 取消：还没导入过就留在欢迎页，导入过的直接关掉这个页面 */
    private fun done() {
        if (configured) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (configured) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            moveTaskToBack(true)
        }
    }
}

/** 在单独的进程（:nagi_restart）里：杀掉主进程，再把 App 重新打开 */
class SuiteRestartActivity : Activity() {
    companion object { const val EXTRA_PID = "pid" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        val pid = intent?.getIntExtra(EXTRA_PID, -1) ?: -1
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                startActivity((packageManager.getLaunchIntentForPackage(packageName) ?: Intent())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (_: Throwable) {}
            finish()
            Process.killProcess(Process.myPid())
        }, 400)
    }
}
