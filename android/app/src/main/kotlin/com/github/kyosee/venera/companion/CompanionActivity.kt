package io.github.kyosee.venera.companion

import android.Manifest
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import io.github.kyosee.venera.suite.PageKit
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class CompanionActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var col: LinearLayout
    private var map: MapView? = null
    private var busy = false
    private var tab = 0
    private var state = JSONObject()
    private var beginAfterPermission = false
    private var queue: CompanionQueue? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!busy && tab == 0 && currentFocus !is EditText && CompanionStore.read(this@CompanionActivity).optString("token").isNotEmpty()) refresh()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = CompanionQueue(this)
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/companion"
            osmdroidBasePath = File(noBackupFilesDir, "companion_map")
            osmdroidTileCache = File(noBackupFilesDir, "companion_map/tiles")
        }
        state = CompanionStore.read(this).optJSONObject("state") ?: JSONObject()
        render()
        if (CompanionStore.read(this).optString("token").isNotEmpty()) refresh()
    }
    private fun task(work: () -> Unit, done: () -> Unit = { render() }) {
        if (busy) { toast("正在处理，请稍候"); return }
        busy = true
        worker.execute {
            val error = runCatching(work).exceptionOrNull()
            runOnUiThread {
                busy = false
                if (!isDestroyed && !isFinishing) {
                    if (error != null) AlertDialog.Builder(this).setTitle("未完成").setMessage(error.message ?: "操作失败").setPositiveButton("知道了", null).show()
                    else done()
                }
            }
        }
    }
    private fun refresh() = task({
        val generation = CompanionStore.read(this).optLong("generation")
        if (CompanionStore.read(this).optBoolean("consent_dirty")) {
            CompanionApi.call(this, "consent", "POST", CompanionStore.permissions(this), generation = generation)
            CompanionStore.update(this) { if (it.optLong("generation") == generation) it.put("consent_dirty", false) }
        }
        state = CompanionApi.call(this, "state")
        val permissions = state.getJSONObject("me").getJSONObject("consent")
        CompanionStore.update(this) { if (it.optLong("generation") == generation) { it.put("state", state); it.put("consent", permissions) } }
        if (!state.has("peer")) queue?.clear()
    })
    private fun render() {
        map?.onDetach(); map = null
        val (_, content) = PageKit.page(this, "陪伴", true)
        col = content
        val config = CompanionStore.read(this)
        if (config.optString("token").isEmpty()) { registration(); return }
        col.addView(PageKit.choices(this, listOf("彼此", "足迹", "消息", "设置"), tab) { tab = it; render() })
        when (tab) { 0 -> overview(); 1 -> history(); 2 -> messages(); else -> settings() }
    }
    private fun text(value: String, size: Float = 14f) { col.addView(PageKit.text(this, value, size).apply { setPadding(0, dp(8), 0, dp(8)) }) }
    private fun section(value: String) = PageKit.section(this, col, value)
    private fun button(label: String, action: () -> Unit) {
        col.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
    }
    private fun edit(hint: String, value: String = "", secret: Boolean = false): EditText {
        val field = PageKit.edit(this, hint, value)
        if (secret) field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        col.addView(field)
        return field
    }
    private fun toggle(label: String, checked: Boolean): Switch {
        val switch = Switch(this).apply { text = label; isChecked = checked; setTextColor(Color.WHITE); setPadding(0, dp(10), 0, dp(10)) }
        col.addView(switch)
        return switch
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton("确认") { _, _ -> action() }.show()
    }
    private fun registration() {
        text("给彼此留一盏灯", 24f)
        text("双方各自安装、注册并确认绑定。位置、手机状态和用机时长可分别选择，也可以随时暂停或解除绑定。")
        val server = edit("陪伴服务地址（HTTPS）")
        val key = edit("服务器注册码", secret = true)
        val name = edit("你的昵称")
        val agree = toggle("我了解并同意上述共享方式", false)
        button("注册这台设备") {
            if (!agree.isChecked) { toast("请先阅读并确认共享方式"); return@button }
            val url = server.text.toString(); val code = key.text.toString(); val nick = name.text.toString()
            task({
                val endpoint = CompanionApi.server(url)
                val result = CompanionApi.call(this, "register", "POST", JSONObject().put("setup_key", code).put("name", nick), endpoint, true)
                CompanionStore.update(this) { it.put("server", endpoint); it.put("token", result.getString("token")); it.put("id", result.getString("id")); it.put("name", nick) }
            })
        }
        text("注册码只用于初次注册，不会保存。服务需要先部署；地址由你填写。")
    }
    private fun overview() {
        val permissions = CompanionStore.permissions(this)
        section(if (permissions.optBoolean("sharing")) "我的共享" else "我的共享已暂停")
        text(if (CompanionService.running) "后台共享正在运行 · 通知栏可随时停止" else "后台共享未运行")
        if (CompanionService.lastError.isNotEmpty()) text(CompanionService.lastError)
        if (CompanionStore.read(this).optBoolean("consent_dirty")) text("本机已停止采集；服务器状态尚未同步，请联网后刷新。")
        button("刷新状态") { refresh() }
        val pending = state.optJSONObject("pending")
        if (pending != null) button("确认与 ${pending.optString("name")} 绑定") {
            confirm("确认绑定", "请确认这是你认识的设备。绑定后仍需各自在设置中选择要共享的内容。") {
                task({ CompanionApi.call(this, "confirm", "POST", JSONObject().put("guest", pending.getString("id"))); state = CompanionApi.call(this, "state") })
            }
        }
        val peer = state.optJSONObject("peer")
        if (peer == null) {
            text("尚未绑定。生成邀请码交给对方，或输入对方的邀请码；最后由邀请方确认。")
            button("生成十分钟有效的邀请码") {
                var code = ""
                task({ code = CompanionApi.call(this, "invite", "POST").getString("code") }, {
                    AlertDialog.Builder(this).setTitle("邀请码：$code").setMessage("请只发给你要绑定的人；对方加入后，刷新本页并确认。")
                        .setPositiveButton("分享") { _, _ -> PageKit.shareText(this, code) }.setNegativeButton("关闭", null).show()
                })
            }
            val code = edit("输入对方的邀请码")
            button("申请绑定") { val value = code.text.toString(); task({ CompanionApi.call(this, "join", "POST", JSONObject().put("code", value)) }, { toast("已申请，请对方刷新并确认") }) }
            return
        }
        section(peer.optString("name", "对方"))
        val peerPermissions = peer.optJSONObject("consent") ?: JSONObject()
        if (!peerPermissions.optBoolean("sharing")) text("对方已暂停共享")
        val seen = peer.optLong("seen")
        text("最近连接：${time(seen)}（不代表定位时间）")
        val sample = peer.optJSONObject("sample")
        val data = sample?.optJSONObject("data")
        if (sample != null) text("数据采集于 ${time(sample.optLong("at"))}${if (System.currentTimeMillis() / 1000 - sample.optLong("at") > 180) " · 已过期，等待更新" else ""}")
        data?.optJSONObject("status")?.let { text("电量 ${it.optInt("battery")}% · ${if (it.optBoolean("charging")) "充电中" else "未充电"}\n${it.optString("network")}") }
        data?.optJSONObject("usage")?.let { text("${it.optString("date")} 屏幕使用约 ${it.optLong("screen_seconds") / 60} 分钟（系统记录）") }
        data?.optJSONObject("location")?.let { loc ->
            text("定位时间 ${time(loc.optLong("at"))} · 精度约 ${loc.optDouble("accuracy").toInt()} 米")
            CompanionService.location?.let { mine ->
                val result = FloatArray(1)
                android.location.Location.distanceBetween(mine.latitude, mine.longitude, loc.getDouble("lat"), loc.getDouble("lon"), result)
                text("相距约 ${"%.2f".format(result[0] / 1000)} 公里（按最近位置计算）")
            }
            val points = mutableListOf(loc)
            CompanionService.location?.let { points.add(JSONObject().put("lat", it.latitude).put("lon", it.longitude).put("at", it.time / 1000).put("label", "我")) }
            showMap(points, false)
        } ?: text("暂无可共享的位置。双方需允许定位，并在设置中开启位置共享。")
        button("开始 / 调整我的共享") { tab = 3; render() }
        button("暂停我的共享") { pause() }
        section("联系对方")
        button("发送紧急求助") { send("sos", "我需要帮助，请尽快联系我，并查看我最近共享的位置。") }
        button("呼叫对方响铃") { send("ring", "对方请求你回应 · 点击打开陪伴") }
        button("请求锁定对方屏幕") {
            confirm("请求锁屏", "只有对方主动开启允许锁屏并授予系统权限后才会执行。") { send("lock", "对方请求锁屏") }
        }
        text("消息送达依赖对方后台服务和网络；紧急情况请直接拨打电话。响铃遵循对方闹钟音量和勿扰设置。")
    }
    private fun send(kind: String, value: String) = task({ CompanionApi.event(this, kind, value) }, { toast("已交给服务器，等待对方接收") })
    private fun history() {
        section("最近七天的足迹")
        text("时间按本机时区显示。断网、定位关闭或暂停期间不会补画路线；长时间间隔会断开连线。")
        val date = edit("日期 YYYY-MM-DD", LocalDate.now().toString())
        val self = toggle("查看我的轨迹（关闭则查看对方）", false)
        button("查看这一天") {
            val value = date.text.toString(); val mine = self.isChecked
            var points = emptyList<JSONObject>()
            task({
                val day = LocalDate.parse(value)
                val start = maxOf(day.atStartOfDay(ZoneId.systemDefault()).toEpochSecond(), System.currentTimeMillis() / 1000 - 7 * 86400 + 5)
                val end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
                val rows = CompanionApi.call(this, "history?start=$start&end=$end&who=${if (mine) "me" else "peer"}").getJSONArray("samples")
                val found = mutableListOf<JSONObject>()
                for (i in 0 until rows.length()) rows.getJSONObject(i).getJSONObject("data").optJSONObject("location")?.let { found.add(it) }
                points = found.distinctBy { it.optLong("at") }
            }, {
                render()
                if (points.isEmpty()) text("$value 暂无可见轨迹") else { text("$value · ${points.size} 个定位点"); showMap(points, true) }
            })
        }
    }
    private fun showMap(points: List<JSONObject>, route: Boolean) {
        val view = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(points.last().getDouble("lat"), points.last().getDouble("lon")))
        }
        var segment = mutableListOf<GeoPoint>()
        var previous = 0L
        fun line() { if (segment.size > 1) view.overlays.add(Polyline().apply { setPoints(segment); outlinePaint.color = 0xffe879a9.toInt(); outlinePaint.strokeWidth = 7f }); segment = mutableListOf() }
        points.forEachIndexed { index, point ->
            val at = point.optLong("at")
            val p = GeoPoint(point.getDouble("lat"), point.getDouble("lon"))
            if (route) {
                if (previous > 0 && at - previous > 300) line()
                segment.add(p); previous = at
            }
            if (!route || index == 0 || index == points.lastIndex) view.overlays.add(Marker(view).apply {
                position = p; title = point.optString("label", if (route) (if (index == 0) "起点" else "终点") else "对方")
                snippet = time(at); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        line()
        col.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
        map = view
        view.onResume()
        text("地图 © OpenStreetMap contributors · WGS84 坐标\n地图瓦片需要联网；无法加载底图时仍可查看定位信息。")
        button("地图署名与使用说明") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
    }
    private fun messages() {
        section("二人空间 · 消息和自动报备")
        val message = edit("想对 Ta 说的话（最多 500 字）")
        button("发送") { send("chat", message.text.toString()) }
        button("刷新消息") {
            task({
                val visible = JSONArray()
                var cursor = 0L
                for (page in 0 until 10) {
                    val response = CompanionApi.call(this, "events?after=$cursor")
                    val events = response.getJSONArray("events")
                    for (i in 0 until events.length()) visible.put(events.getJSONObject(i))
                    val next = response.getLong("cursor")
                    if (next == cursor) break
                    cursor = next
                }
                // Refresh access filtering: paused/removed categories must disappear from the local list too.
                queue?.writableDatabase?.execSQL("DELETE FROM inbox")
                for (i in 0 until visible.length()) queue?.receive(visible.getJSONObject(i))
            })
        }
        val events = queue?.events() ?: JSONArray()
        if (events.length() == 0) text("还没有消息。双方绑定后，可在这里聊天并查看已授权的状态变化。")
        val me = CompanionStore.read(this).optString("id")
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val card = PageKit.card(this)
            card.addView(PageKit.text(this, "${if (event.optString("owner") == me) "我" else "Ta"} · ${time(event.optLong("at"))}", 12f))
            card.addView(PageKit.text(this, event.optString("text"), 15f, Color.WHITE))
            col.addView(card)
        }
    }
    private fun settings() {
        val config = CompanionStore.read(this)
        val permissions = CompanionStore.permissions(this)
        section("共享内容（分别由本机决定）")
        text("${config.optString("name")} · ${config.optString("server")}")
        val location = toggle("位置、历史轨迹、到达离开提醒", permissions.optBoolean("location"))
        val status = toggle("电量、充电、网络及已开启的报备", permissions.optBoolean("status"))
        val usage = toggle("亮灭屏、解锁及当日屏幕时长", permissions.optBoolean("usage"))
        val calls = toggle("通话状态（不采集号码、联系人或录音）", config.optBoolean("calls"))
        val ring = toggle("允许对方请求响铃（最长 20 秒）", permissions.optBoolean("ring"))
        val lock = toggle("允许对方锁屏（需系统设备管理权限）", permissions.optBoolean("lock"))
        val reminders = toggle("早安、晚安及白天每两小时喝水提醒", config.optBoolean("reminders"))
        val morning = edit("早安时间：0–23 点", config.optInt("morning_hour", 8).toString())
        val bedtime = edit("晚安时间：0–23 点", config.optInt("bedtime_hour", 23).toString())
        var interval = config.optLong("interval", 60)
        text("定位与状态采样频率：更快会增加耗电，系统可能延后更新。")
        col.addView(PageKit.choices(this, listOf("15 秒", "1 分钟", "5 分钟"), listOf(15L, 60L, 300L).indexOf(interval).coerceAtLeast(0)) { interval = listOf(15L, 60L, 300L)[it] })
        button("保存选项并开始共享") {
            val hour1 = morning.text.toString().toIntOrNull(); val hour2 = bedtime.text.toString().toIntOrNull()
            if (hour1 == null || hour1 !in 0..23 || hour2 == null || hour2 !in 0..23) { toast("请输入 0–23 点"); return@button }
            if (!location.isChecked && !status.isChecked && !usage.isChecked) { toast("至少选择一项共享内容"); return@button }
            if (lock.isChecked && (getSystemService(DevicePolicyManager::class.java)?.isAdminActive(ComponentName(this, CompanionAdminReceiver::class.java)) != true)) {
                toast("请先点击下方“授权锁屏”，再保存"); return@button
            }
            val options = JSONObject().put("sharing", true).put("location", location.isChecked).put("status", status.isChecked)
                .put("usage", usage.isChecked).put("ring", ring.isChecked).put("lock", lock.isChecked)
            confirm("开始共享", "你选择的数据会上传到自己的陪伴服务器，并提供给已绑定的对方查看。通知栏会持续显示共享状态；可随时暂停。") {
                stopService(CompanionService.intent(this))
                queue?.clear()
                CompanionStore.update(this) {
                    it.put("generation", it.optLong("generation") + 1)
                    it.put("consent", options); it.put("consent_dirty", true); it.put("calls", calls.isChecked)
                    it.put("reminders", reminders.isChecked); it.put("morning_hour", hour1); it.put("bedtime_hour", hour2); it.put("interval", interval)
                }
                beginAfterPermission = true
                ensurePermissions()
            }
        }
        button("暂停共享") { pause() }
        section("权限与后台运行")
        text("定位服务从本页启动。仅授予近似位置时精度会降低；后台、通知和用量权限由你在系统界面决定。强行停止 App 后需手动重新开始。")
        button("允许查看系统用机时长") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        button("授予通话状态权限") { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 82) }
        button("授权锁屏（系统确认）") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, CompanionAdminReceiver::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "仅用于你主动允许的伴侣锁屏，不请求擦除数据权限。"))
        }
        button("撤销锁屏权限") {
            getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(ComponentName(this, CompanionAdminReceiver::class.java))
            CompanionStore.update(this) { it.put("generation", it.optLong("generation") + 1); it.put("consent", CompanionStore.permissions(this).put("lock", false)); it.put("consent_dirty", true) }
            render()
        }
        button("打开本应用系统设置") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        button("打开电池优化设置") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        section("地点提醒")
        text("在想设置的地点打开定位，添加当前位置。进入／离开有精度缓冲；后台停止时不会触发。")
        val place = edit("地点名称，例如：家")
        button("将我的当前位置设为提醒地点（200 米）") {
            val loc = CompanionService.location
            val name = place.text.toString().trim()
            if (loc == null || SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos > 120_000_000_000L || name.isEmpty()) {
                toast("请填写名称，并先开启位置共享获取新定位"); return@button
            }
            CompanionStore.update(this) {
                val items = it.optJSONArray("places") ?: JSONArray()
                if (items.length() >= 20) { toast("最多保存 20 个地点"); return@update }
                items.put(JSONObject().put("name", name.take(32)).put("lat", loc.latitude).put("lon", loc.longitude).put("radius", 200))
                it.put("places", items)
            }
            render()
        }
        val places = config.optJSONArray("places") ?: JSONArray()
        for (i in 0 until places.length()) button("删除地点：${places.getJSONObject(i).optString("name")}") {
            CompanionStore.update(this) { val rows = it.optJSONArray("places") ?: JSONArray(); rows.remove(i); it.put("places", rows) }; render()
        }
        section("数据与绑定")
        text("服务器保留最近七天的数据。暂停后服务器不再向对方提供你的共享类别；对方已经看到的信息无法撤回。")
        button("删除我的服务器历史记录") { confirm("删除记录", "删除我的定位、状态和已发送消息，同时清理本机缓存。") {
            task({ CompanionApi.call(this, "history", "DELETE"); queue?.clear() })
        } }
        button("解除绑定并停止共享") { confirm("解除绑定", "双方共享会停止，服务器上的双方轨迹和消息会删除。") {
            stopService(CompanionService.intent(this))
            task({ CompanionApi.call(this, "unpair", "POST"); resetLocal(false) })
        } }
        button("删除本设备账号") { confirm("删除设备", "本设备凭据将失效，并解除绑定、删除双方共享记录；以后需重新注册。") {
            stopService(CompanionService.intent(this))
            task({ CompanionApi.call(this, "device", "DELETE"); resetLocal(true) })
        } }
    }
    private fun resetLocal(all: Boolean) {
        queue?.clear(); state = JSONObject()
        CompanionStore.update(this) {
            it.put("generation", it.optLong("generation") + 1)
            if (all) { val keys = it.keys().asSequence().toList(); keys.forEach { key -> it.remove(key) } }
            else { it.put("consent", JSONObject()); it.put("cursor", 0); it.remove("state"); it.put("consent_dirty", false) }
        }
    }
    private fun ensurePermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.ACCESS_COARSE_LOCATION; missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 81) else startSharing()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 81 && beginAfterPermission) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) startSharing()
            else { beginAfterPermission = false; toast("定位前台服务需要定位权限，未启动共享"); CompanionStore.update(this) { it.put("consent", CompanionStore.permissions(this).put("sharing", false)) } }
        }
    }
    private fun startSharing() {
        beginAfterPermission = false
        task({ CompanionApi.call(this, "consent", "POST", CompanionStore.permissions(this)); CompanionStore.update(this) { it.put("consent_dirty", false) } }, {
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(CompanionService.intent(this)) else startService(CompanionService.intent(this))
                tab = 0; render(); toast("已请求启动共享，请确认通知栏状态")
            } catch (e: Exception) { toast("启动失败，请重新打开页面后重试") }
        })
    }
    private fun pause() {
        stopService(CompanionService.intent(this))
        CompanionService.location = null
        queue?.clear()
        CompanionStore.update(this) { it.put("generation", it.optLong("generation") + 1); it.put("consent", CompanionStore.permissions(this).put("sharing", false)); it.put("consent_dirty", true) }
        task({ CompanionApi.call(this, "consent", "POST", CompanionStore.permissions(this)); CompanionStore.update(this) { it.put("consent_dirty", false) } }, { render(); toast("已暂停共享") })
    }
    private fun time(seconds: Long): String = if (seconds <= 0) "尚未连接" else Instant.ofEpochSecond(seconds)
        .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
    override fun onResume() { super.onResume(); map?.onResume(); handler.postDelayed(ticker, 30_000) }
    override fun onPause() { handler.removeCallbacks(ticker); map?.onPause(); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); map?.onDetach(); worker.shutdown()
        Thread { worker.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); queue?.close() }.start()
        super.onDestroy()
    }
}
