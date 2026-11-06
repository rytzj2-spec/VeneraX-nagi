package io.github.kyosee.venera.suite

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 「更多」「一起看」这类独立页面的共用小部件（深色、粉紫，和面板一样） */
internal object PageKit {
    fun Context.dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    /** 页面骨架：标题栏（可带返回）+ 可滚动内容；返回 (根, 内容列) */
    fun page(a: Activity, title: String, back: Boolean): Pair<FrameLayout, LinearLayout> {
        a.window.statusBarColor = SuitePanels.BG
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16f), a.dp(4f), a.dp(16f), a.dp(24f))
        }
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(if (back) a.dp(4f) else a.dp(16f), a.dp(10f), a.dp(16f), a.dp(6f))
        }
        if (back) head.addView(TextView(a).apply {
            text = "‹"; textSize = 30f; setTextColor(SuitePanels.TEXT); setPadding(a.dp(12f), 0, a.dp(12f), a.dp(4f))
            setOnClickListener { a.finish() }
        })
        head.addView(TextView(a).apply { text = title; textSize = 22f; setTextColor(SuitePanels.TEXT); paint.isFakeBoldText = true })
        val outer = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(head)
        outer.addView(ScrollView(a).apply { isFillViewport = true; addView(col) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val root = FrameLayout(a).apply { setBackgroundColor(SuitePanels.BG) }
        root.addView(outer)
        a.setContentView(root)
        return root to col
    }

    fun section(a: Activity, col: LinearLayout, t: String) = col.addView(TextView(a).apply {
        text = t; setTextColor(SuitePanels.PINK); textSize = 13.5f; paint.isFakeBoldText = true
        setPadding(a.dp(4f), a.dp(18f), 0, a.dp(8f))
    })

    fun card(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(16f), a.dp(14f), a.dp(16f), a.dp(14f))
        background = GradientDrawable().apply { setColor(SuitePanels.CARD); cornerRadius = a.dp(14f).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(10f) }
    }

    /** 一行大卡片：标题 + 副标题，点了做事；返回副标题（好刷新） */
    fun rowCard(a: Activity, col: LinearLayout, title: String, sub: String, onClick: () -> Unit): TextView {
        val c = card(a)
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(a).apply { text = title; textSize = 16f; setTextColor(SuitePanels.TEXT) })
        val subView = TextView(a).apply { text = sub; textSize = 12.5f; setTextColor(SuitePanels.SUB); setPadding(0, a.dp(2f), 0, 0) }
        texts.addView(subView)
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(a).apply { text = "›"; textSize = 22f; setTextColor(SuitePanels.SUB) })
        c.addView(line)
        c.setOnClickListener { onClick() }
        col.addView(c)
        return subView
    }

    fun text(a: Activity, t: String, size: Float = 13f, color: Int = SuitePanels.SUB) = TextView(a).apply {
        text = t; textSize = size; setTextColor(color); setLineSpacing(0f, 1.2f)
    }

    fun edit(a: Activity, hint: String, value: String, uri: Boolean = false) = EditText(a).apply {
        this.hint = hint; setText(value); isSingleLine = true; textSize = 14f
        setTextColor(SuitePanels.TEXT); setHintTextColor(SuitePanels.SUB)
        inputType = InputType.TYPE_CLASS_TEXT or (if (uri) InputType.TYPE_TEXT_VARIATION_URI else 0)
    }

    /** 一排选项按钮（选中的是粉紫渐变） */
    fun choices(a: Activity, labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(4f), 0, a.dp(4f)) }
        val buttons = mutableListOf<Button>()
        var cur = selected
        fun paint() = buttons.forEachIndexed { i, b ->
            val on = i == cur
            b.setTextColor(if (on) 0xFF18181B.toInt() else SuitePanels.TEXT)
            b.background = GradientDrawable().apply {
                cornerRadius = a.dp(10f).toFloat()
                if (on) { colors = intArrayOf(0xFFE879A9.toInt(), 0xFFA78BFA.toInt()); orientation = GradientDrawable.Orientation.LEFT_RIGHT }
                else setColor(0xFF27272A.toInt())
            }
        }
        labels.forEachIndexed { i, l ->
            if (i > 0) row.addView(View(a), LinearLayout.LayoutParams(a.dp(6f), 1))
            val b = Button(a).apply {
                text = l; isAllCaps = false; textSize = 13f; minHeight = 0; minimumHeight = 0
                setPadding(a.dp(4f), a.dp(8f), a.dp(4f), a.dp(8f))
                setOnClickListener { cur = i; paint(); onPick(i) }
            }
            buttons += b
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
        return row
    }

    fun copy(a: Activity, text: String) {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Nagisaland", text))
        Toast.makeText(a, "已复制", Toast.LENGTH_SHORT).show()
    }

    fun shareText(a: Activity, text: String) {
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享"))
    }
}

/**
 * 「更多」页：功能（一起看、一键配置）→ 没放在底栏的分区 → 设置（编辑底栏、流畅度设置）。
 * 底部有分区栏（「更多」亮着）；从这里切到别的分区后这个页面自己关掉，返回 = 回到刚才的分区。
 */
class SuiteMoreActivity : Activity() {
    private lateinit var togetherSub: TextView
    private lateinit var configSub: TextView
    private lateinit var grid: GridLayout
    private lateinit var gridEmpty: TextView
    private val navListener: () -> Unit = { rebuildGrid() }     // 强引用（SuiteNavConfig 只存弱引用）
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        with(PageKit) {
            val (_, col) = page(this@SuiteMoreActivity, "更多", back = false)
            val a = this@SuiteMoreActivity
            section(a, col, "功能")
            togetherSub = rowCard(a, col, "一起看", "") { startActivity(Intent(a, SuiteTogetherActivity::class.java)) }
            configSub = rowCard(a, col, "一键配置", "") { SuiteConfigUi.showMenu(a) }

            section(a, col, "其他分区")
            col.addView(text(a, "点一下打开；长按放到底栏").apply { setPadding(a.dp(4f), 0, 0, a.dp(6f)) })
            grid = GridLayout(a).apply { columnCount = 4 }
            col.addView(grid)
            gridEmpty = text(a, "所有分区都在底栏上了").apply { setPadding(a.dp(4f), a.dp(4f), 0, a.dp(4f)) }
            col.addView(gridEmpty)

            section(a, col, "设置")
            rowCard(a, col, "编辑底栏", "底栏放哪些分区、什么顺序") { SuitePanels.showEditor(a) }
            rowCard(a, col, "流畅度设置 / 手势", "后台预热、引擎组、Impeller、左边缘右滑、分区首页返回") { SuitePanels.showSettings(a) }
        }
        SuiteNavConfig.addListener(navListener)
        rebuildGrid()
        refresh()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // 底部分区栏：「更多」亮着；从这里切走后关掉自己
        SuiteNav.attach(this, "more") { finish() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val rtc = SuiteRtcShare.service
        togetherSub.text = when {
            rtc != null -> "正在共享（经服务器）· 房间 ${rtc.room ?: "…"} · ${rtc.viewers} 人在看"
            SuiteScreenShare.service != null -> "正在共享（局域网）· ${SuiteScreenShare.service?.viewers ?: 0} 人在看"
            else -> "屏幕共享、看别人的屏幕、同步播放"
        }
        configSub.text = SuiteProfile.profileName(this)?.let { "当前用的是「$it」· 导出 / 导入" }
            ?: SuiteConfigSync.url(this)?.let { "自动更新中 · 导出 / 导入" } ?: "把配置打包发给别人，或者导入别人的"
    }

    private fun rebuildGrid() {
        val a = this
        grid.removeAllViews()
        val keys = SuiteNavConfig.more(this)
        gridEmpty.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
        for (k in keys) {
            val cell = SuitePanels.Cell(a, k, false)
            cell.setOnClickListener { SuiteNav.go(a, k); finish() }
            cell.setOnLongClickListener {
                val err = SuiteNavConfig.moveToBar(a, k)
                Toast.makeText(a, err ?: "「${SuiteNavItems.label(k)}」已放到底栏", Toast.LENGTH_SHORT).show()
                true
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}

/**
 * 「一起看」页：共享我的屏幕（经服务器 / 局域网直连；共享中是状态卡片，每秒刷新）、看别人的屏幕、同步播放说明。
 */
class SuiteTogetherActivity : Activity() {
    private lateinit var shareBox: LinearLayout
    private var shownState = ""            // none / rtc / lan：变了才重建共享这一块
    private val statusViews = HashMap<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = this
        with(PageKit) {
            val (_, col) = page(a, "一起看", back = true)
            section(a, col, "共享我的屏幕")
            shareBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(shareBox)

            section(a, col, "看别人的屏幕")
            val c = card(a)
            c.addView(text(a, "粘贴对方发来的口令（例如 v9ru6w，有密码就是「v9ru6w 密码」）或者链接，在 App 里看"))
            val link = edit(a, "口令或链接", "", uri = true)
            c.addView(link)
            fun target(): String? {
                val raw = link.text.toString().trim()
                Regex("https?://\\S+").find(raw)?.let { return it.value }
                return SuiteRtcShare.linkFromCode(a, raw)
            }
            fun bad() = Toast.makeText(a, if (SuiteRtcShare.server(a).isBlank()) "还没有默认服务器：先在上面填一次服务器地址，或者粘贴完整链接" else "没认出口令或链接", Toast.LENGTH_LONG).show()
            c.addView(SuitePanels.button(a, "在 App 里看", primary = true) {
                val u = target() ?: run { bad(); return@button }
                SuiteWatchActivity.open(a, u)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8f) })
            c.addView(SuitePanels.button(a, "用浏览器打开") {
                val u = target() ?: run { bad(); return@button }
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Throwable) { Toast.makeText(a, "打不开浏览器：${e.message}", Toast.LENGTH_LONG).show() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8f) })
            col.addView(c)

            section(a, col, "同步播放")
            val c2 = card(a)
            c2.addView(text(a, "番剧、影视、H 的播放页里点「一起看」，大家填同一个房间名，就会一起暂停 / 拖进度 / 换集。" +
                "画面各自播放，不费流量，用公共的 SyncPlay 服务器，不用自己搭。影视要连同一个 Emby / Jellyfin / 飞牛服务器。", 13.5f))
            col.addView(c2)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun refresh() {
        val state = when {
            SuiteRtcShare.service != null -> "rtc"
            SuiteScreenShare.service != null -> "lan"
            else -> "none"
        }
        if (state != shownState) {
            shownState = state
            shareBox.removeAllViews()
            statusViews.clear()
            when (state) {
                "rtc" -> buildRtcStatus()
                "lan" -> buildLanStatus()
                else -> buildStartForm()
            }
        }
        if (state == "rtc") updateRtcStatus()
        if (state == "lan") updateLanStatus()
    }

    // ---------------------------------------------------------------- 没在共享：设置 + 开始

    private fun buildStartForm() = with(PageKit) {
        val a = this@SuiteTogetherActivity
        val c = card(a)
        SuiteRtcShare.lastEnd?.let { c.addView(text(a, "上次共享结束：$it", 12.5f, 0xFFFBBF24.toInt()).apply { setPadding(0, 0, 0, a.dp(8f)) }) }
        c.addView(text(a, "经服务器：对方在任何网络都能看（能直连就直连，连不上由中转服务器转发），浏览器打开链接，还能发弹幕。"))
        val server = edit(a, "服务器地址，例如 https://你的域名/share/", SuiteRtcShare.server(a), uri = true)
        val pw = edit(a, "房间密码（可不填；会带在分享链接里）", SuiteRtcShare.password(a))
        c.addView(server)
        c.addView(pw)
        val q = SuiteRtcShare.quality(a)
        addQualityRows(c)
        c.addView(text(a, "网不好时", 12.5f).apply { setPadding(0, a.dp(4f), 0, 0) })
        c.addView(choices(a, SuiteRtcShare.PREFER_LABELS, SuiteRtcShare.prefer(a)) { SuiteRtcShare.savePrefer(a, it) })
        c.addView(text(a, "中转（直连不上时）", 12.5f).apply { setPadding(0, a.dp(4f), 0, 0) })
        val turnKeys = SuiteRtcShare.TURN_LABELS.keys.toList()
        c.addView(choices(a, SuiteRtcShare.TURN_LABELS.values.toList(), turnKeys.indexOf(SuiteRtcShare.turn(a)).coerceAtLeast(0)) {
            SuiteRtcShare.saveTurn(a, turnKeys[it])
        })
        fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(10f) }
        c.addView(SuitePanels.button(a, "经服务器共享", primary = true) {
            var s = server.text.toString().trim()
            if (s.isEmpty()) {
                Toast.makeText(a, "先填服务器地址（问给你配置的人要，或者导入他的一键配置）", Toast.LENGTH_LONG).show(); return@button
            }
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
            SuiteRtcShare.save(a, s, pw.text.toString().trim(), q)
            SuiteScreenShare.start(a, "server")
        }, full())
        c.addView(SuitePanels.button(a, "局域网直连（同一个 Wi-Fi / Tailscale）") { SuiteScreenShare.start(a, "lan") }, full())
        c.addView(text(a, "声音（Android 10+）录的是手机正在播放的声音。经服务器时系统会显示麦克风在用，但麦克风的声音不会发出去。" +
            "有版权保护的视频 App 录出来是黑的。", 12f).apply { setPadding(0, a.dp(10f), 0, 0) })
        shareBox.addView(c)
    }

    // ---------------------------------------------------------------- 共享中（经服务器）

    private fun buildRtcStatus() = with(PageKit) {
        val a = this@SuiteTogetherActivity
        val c = card(a)
        c.addView(text(a, "正在共享（经服务器）", 16f, SuitePanels.TEXT))
        for ((k, label) in listOf("room" to "房间", "viewers" to "在看", "path" to "连接", "rate" to "码率", "video" to "画面", "codec" to "编码", "sig" to "服务器")) {
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(4f), 0, 0) }
            line.addView(text(a, label, 13f), LinearLayout.LayoutParams(a.dp(48f), ViewGroup.LayoutParams.WRAP_CONTENT))
            val v = text(a, "…", 13.5f, SuitePanels.TEXT)
            line.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            statusViews[k] = v
            c.addView(line)
        }
        val link = text(a, "", 14f, 0xFFA78BFA.toInt()).apply {
            setPadding(0, a.dp(10f), 0, a.dp(2f))
            setOnClickListener { SuiteRtcShare.inviteLink()?.let { PageKit.copy(a, it) } }
        }
        statusViews["link"] = link
        c.addView(link)
        c.addView(text(a, "点链接复制", 11.5f))
        val code = text(a, "", 18f, SuitePanels.TEXT).apply {
            setPadding(0, a.dp(10f), 0, a.dp(2f))
            setOnClickListener { SuiteRtcShare.inviteCode()?.let { PageKit.copy(a, it) } }
        }
        statusViews["code"] = code
        c.addView(code)
        c.addView(text(a, "点口令复制：对方在 Nagisaland「一起看 → 看别人的屏幕」里粘贴口令就能看", 11.5f))
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(12f), 0, 0) }
        row.addView(SuitePanels.button(a, "停止共享") { SuiteRtcShare.stop(a) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(SuitePanels.button(a, "分享链接", primary = true) {
            SuiteRtcShare.inviteLink()?.let { shareText(a, "来看我的手机屏幕\n口令：${SuiteRtcShare.inviteCode()}（Nagisaland → 更多 → 一起看 → 看别人的屏幕）\n或者浏览器打开：$it") }
                ?: Toast.makeText(a, "还在连接服务器…", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row)
        c.addView(text(a, "我的麦克风（和播放的声音一起发出去；戴耳机效果最好）", 12.5f).apply { setPadding(0, a.dp(14f), 0, 0) })
        c.addView(choices(a, listOf("关", "开"), if (SuiteRtcShare.service?.micOn == true) 1 else 0) { SuiteRtcShare.setMic(it == 1) })
        c.addView(text(a, "画质（改了马上生效）", 12.5f).apply { setPadding(0, a.dp(10f), 0, 0) })
        addQualityRows(c)
        shareBox.addView(c)
    }

    /** 分辨率 / 帧率 / 码率上限三排按钮：没在共享时是下次的设置，共享中改了马上生效 */
    private fun addQualityRows(c: LinearLayout) = with(PageKit) {
        val a = this@SuiteTogetherActivity
        c.addView(text(a, "分辨率", 12.5f).apply { setPadding(0, a.dp(8f), 0, 0) })
        c.addView(choices(a, SuiteRtcShare.RES_LABELS, SuiteRtcShare.res(a)) { SuiteRtcShare.setLive(a, res = it) })
        c.addView(text(a, "帧率（片源本身 24 帧的番剧选 60 也只有 24）", 12.5f).apply { setPadding(0, a.dp(4f), 0, 0) })
        c.addView(choices(a, SuiteRtcShare.FPS_LABELS, SuiteRtcShare.fpsIdx(a)) { SuiteRtcShare.setLive(a, fps = it) })
        c.addView(text(a, "码率上限（自动 = 按网速，最高 10M）", 12.5f).apply { setPadding(0, a.dp(4f), 0, 0) })
        c.addView(choices(a, SuiteRtcShare.RATE_LABELS, SuiteRtcShare.rate(a)) { SuiteRtcShare.setLive(a, rate = it) })
    }

    private fun updateRtcStatus() {
        val s = SuiteRtcShare.service ?: return
        val st = s.stats
        statusViews["room"]?.text = s.room ?: "连接服务器…"
        statusViews["viewers"]?.text = "${s.viewers} 人"
        statusViews["path"]?.text = st.path.ifEmpty { if (s.viewers == 0) "还没人在看" else "连接中…" }
        statusViews["rate"]?.text = if (s.viewers == 0) "-" else "${st.bitrateKbps} kbps" + if (st.limitedBy.isNotEmpty()) "（受限：${limitLabel(st.limitedBy)}）" else ""
        statusViews["video"]?.text = if (st.width > 0) "${st.width}×${st.height} · ${"%.0f".format(st.fps)} 帧" else "-"
        statusViews["codec"]?.text = listOf(st.codec, st.encoder).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "-" }
        statusViews["sig"]?.text = if (s.signalingUp) "已连接" else "断开，自动重连中（画面不受影响）"
        statusViews["link"]?.text = SuiteRtcShare.inviteLink() ?: "等服务器分房间号…"
        statusViews["code"]?.text = SuiteRtcShare.inviteCode()?.let { "口令：$it" } ?: ""
    }

    private fun limitLabel(r: String) = when (r) {
        "bandwidth" -> "网速"
        "cpu" -> "手机性能"
        else -> r
    }

    // ---------------------------------------------------------------- 共享中（局域网）

    private fun buildLanStatus() = with(PageKit) {
        val a = this@SuiteTogetherActivity
        val c = card(a)
        c.addView(text(a, "正在共享（局域网直连）", 16f, SuitePanels.TEXT))
        val viewers = text(a, "", 13.5f, SuitePanels.TEXT).apply { setPadding(0, a.dp(4f), 0, 0) }
        statusViews["viewers"] = viewers
        c.addView(viewers)
        c.addView(text(a, "对方用浏览器打开下面的链接（点一下复制）；不在同一个 Wi-Fi 要装 Tailscale 之类的组网工具", 12.5f).apply { setPadding(0, a.dp(6f), 0, 0) })
        for ((label, url) in SuiteScreenShare.links()) {
            c.addView(text(a, "$label\n$url", 14f, 0xFFA78BFA.toInt()).apply {
                setPadding(0, a.dp(8f), 0, 0)
                setOnClickListener { PageKit.copy(a, url) }
            })
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(12f), 0, 0) }
        row.addView(SuitePanels.button(a, "停止共享") { SuiteScreenShare.stop(a) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(SuitePanels.button(a, "分享链接", primary = true) {
            shareText(a, "来看我的手机屏幕（浏览器打开）：\n" + SuiteScreenShare.links().joinToString("\n") { "${it.first}：${it.second}" })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row)
        shareBox.addView(c)
    }

    private fun updateLanStatus() {
        val s = SuiteScreenShare.service ?: return
        statusViews["viewers"]?.text = "${s.viewers} 人在看 · 声音：" + if (s.audioOn) "开" else "没开"
    }
}
