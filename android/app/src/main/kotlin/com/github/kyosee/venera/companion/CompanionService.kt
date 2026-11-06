package io.github.kyosee.venera.companion

import android.Manifest
import android.app.*
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.*
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompanionAdminReceiver : DeviceAdminReceiver()

class CompanionService : Service(), LocationListener {
    companion object {
        const val STOP = "companion.stop"
        const val SILENCE = "companion.silence"
        const val CHANNEL = "nagi_companion"
        const val ALERT = "nagi_companion_alert"
        @Volatile var running = false
        @Volatile var lastError = ""
        @Volatile var location: Location? = null
        fun intent(c: Context) = Intent(c, CompanionService::class.java)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var queue: CompanionQueue
    private lateinit var locations: LocationManager
    private var registered = false
    private var ringtone: Ringtone? = null
    private var previousNetwork = ""
    private var started = false
    private var trip = ""
    private var tripCandidate = ""
    private var tripSince = 0L
    private val fenceStates = mutableMapOf<String, Boolean>()
    private var lastLocationAt = 0L
    private var lastSampleAt = 0L
    private var ticks = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> event("screen_on", "屏幕亮起")
                Intent.ACTION_SCREEN_OFF -> event("screen_off", "屏幕熄灭")
                Intent.ACTION_USER_PRESENT -> event("unlock", "手机已解锁")
                Intent.ACTION_POWER_CONNECTED -> event("charging", "开始充电")
                Intent.ACTION_POWER_DISCONNECTED -> event("unplugged", "停止充电")
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> if (CompanionStore.read(c).optBoolean("calls")) {
                    val label = when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                        TelephonyManager.EXTRA_STATE_RINGING -> "来电响铃"
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> "通话中（不采集号码或录音）"
                        TelephonyManager.EXTRA_STATE_IDLE -> "通话已结束或空闲"
                        else -> return
                    }
                    event("call", label)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        queue = CompanionQueue(this)
        locations = requireNotNull(getSystemService(LocationManager::class.java))
        val nm = requireNotNull(getSystemService(NotificationManager::class.java))
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "陪伴共享状态", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(ALERT, "陪伴消息与提醒", NotificationManager.IMPORTANCE_HIGH))
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SILENCE) { ringtone?.stop(); return START_NOT_STICKY }
        if (intent?.action == STOP) { pause(); return START_NOT_STICKY }
        if (!CompanionStore.permissions(this).optBoolean("sharing")) { stopSelf(); return START_NOT_STICKY }
        try { startForeground(7410, notification("位置与状态共享中 · 点击管理", true)) }
        catch (e: Exception) { lastError = "无法启动共享：请检查定位权限并从页面重试"; stopSelf(); return START_NOT_STICKY }
        if (started) return START_STICKY
        started = true
        running = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        registered = true
        subscribeLocation()
        executor.scheduleWithFixedDelay({ sync() }, 0, 15, TimeUnit.SECONDS)
        return START_STICKY
    }
    private fun notification(message: String, foreground: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 7410, Intent(this, CompanionActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, if (foreground) CHANNEL else ALERT) else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Nagisaland · 陪伴")
            .setContentText(message).setContentIntent(open).setOngoing(foreground).setAutoCancel(!foreground)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        val action = if (foreground) STOP else SILENCE
        val stop = PendingIntent.getService(this, if (foreground) 7411 else 7412,
            intent(this).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(Notification.Action.Builder(null, if (foreground) "暂停共享" else "停止响铃", stop).build())
        return builder.build()
    }
    private fun pause() {
        CompanionStore.update(this) { it.put("generation", it.optLong("generation") + 1); it.put("consent", CompanionStore.permissions(this).put("sharing", false)); it.put("consent_dirty", true) }
        location = null
        queue.clear()
        executor.execute {
            runCatching {
                CompanionApi.call(this, "consent", "POST", CompanionStore.permissions(this))
                CompanionStore.update(this) { it.put("consent_dirty", false) }
            }.onFailure { lastError = "本机已停止采集，服务器暂停状态待联网同步" }
            handler.post { stopSelf() }
        }
    }
    @Suppress("MissingPermission") private fun subscribeLocation() {
        locations.removeUpdates(this)
        if (!CompanionStore.enabled(this, "location")) return
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val interval = CompanionStore.read(this).optLong("interval", 60).coerceIn(15, 300) * 1000
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (locations.isProviderEnabled(provider)) locations.requestLocationUpdates(provider, interval, 10f, this, Looper.getMainLooper())
            }.onFailure { lastError = "部分定位来源不可用，请检查系统定位开关" }
        }
    }
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) { lastError = "定位来源已关闭：$provider" }
    @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onLocationChanged(loc: Location) {
        if (!CompanionStore.enabled(this, "location") || !loc.hasAccuracy() || loc.accuracy > 200) return
        if (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos > TimeUnit.MINUTES.toNanos(2)) return
        if (location != null && loc.time <= location!!.time) return
        location = Location(loc)
        if (loc.time / 1000 - lastLocationAt < 10) return
        lastLocationAt = loc.time / 1000
        checkFences(loc)
        // Explicitly a speed-based inference, not a claim to know boarding/alighting.
        val candidate = if (loc.hasSpeed() && loc.speed > 4f) "可能乘车" else if (!loc.hasSpeed() || loc.speed < 1.5f) "停留或步行" else trip
        if (candidate != tripCandidate) { tripCandidate = candidate; tripSince = SystemClock.elapsedRealtime() }
        if (candidate.isNotEmpty() && candidate != trip && SystemClock.elapsedRealtime() - tripSince > 90_000) {
            trip = candidate
            event("travel", "$candidate（按速度推测，可能不准确）")
        }
    }
    private fun checkFences(loc: Location) {
        val fences = CompanionStore.read(this).optJSONArray("places") ?: return
        for (index in 0 until fences.length()) {
            val fence = fences.getJSONObject(index)
            val distance = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, fence.getDouble("lat"), fence.getDouble("lon"), distance)
            val radius = fence.optDouble("radius", 200.0)
            val name = fence.getString("name")
            val previous = fenceStates[name]
            val inside = when {
                distance[0] + loc.accuracy < radius -> true
                distance[0] - loc.accuracy > radius + 80 -> false
                else -> continue
            }
            fenceStates[name] = inside
            if (previous != null && previous != inside) event(if (inside) "arrive" else "leave", "${if (inside) "到达" else "离开"} $name")
        }
    }
    private fun event(kind: String, text: String) {
        val category = when (kind) {
            "arrive", "leave", "travel" -> "location"
            "screen_on", "screen_off", "unlock" -> "usage"
            else -> "status"
        }
        if (CompanionStore.enabled(this, category)) queue.add("event", JSONObject().put("kind", kind).put("text", text).put("at", System.currentTimeMillis() / 1000))
    }
    @Suppress("DEPRECATION") private fun network(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "未知网络"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "离线"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val name = runCatching { wifi.connectionInfo.ssid.trim('"') }.getOrDefault("")
            return if (name.isEmpty() || name == "<unknown ssid>") "Wi-Fi（名称不可用）" else "Wi-Fi · $name"
        }
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "移动网络" else "其他网络 / VPN"
    }
    private fun usage(): JSONObject? {
        val ops = getSystemService(AppOpsManager::class.java) ?: return null
        @Suppress("DEPRECATION") val allowed = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        if (!allowed) return null
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val midnight = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = getSystemService(UsageStatsManager::class.java)?.queryEvents(midnight - 86400000, now) ?: return null
        val event = UsageEvents.Event()
        var on: Long? = null
        var total = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> if (on == null) on = maxOf(event.timeStamp, midnight)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    on?.let { total += (event.timeStamp - it).coerceAtLeast(0) }; on = null
                }
            }
        }
        on?.let { total += now - it }
        return JSONObject().put("date", today.toString()).put("screen_seconds", (total / 1000).coerceIn(0, 86400))
    }
    private fun sample(): JSONObject {
        val data = JSONObject()
        if (CompanionStore.enabled(this, "location")) location?.let { loc ->
            if (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos < TimeUnit.MINUTES.toNanos(2)) data.put("location", JSONObject()
                .put("lat", loc.latitude).put("lon", loc.longitude).put("accuracy", loc.accuracy.toDouble())
                .put("speed", loc.speed.toDouble().coerceAtLeast(0.0)).put("at", loc.time / 1000))
        }
        if (CompanionStore.enabled(this, "status")) {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
            val net = network()
            if (previousNetwork.isNotEmpty() && previousNetwork != net) event("network", "网络切换为 $net")
            previousNetwork = net
            data.put("status", JSONObject().put("battery", (level * 100 / scale).coerceIn(0, 100))
                .put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0).put("network", net))
        }
        if (CompanionStore.enabled(this, "usage") && Build.VERSION.SDK_INT >= 28) usage()?.let { data.put("usage", it) }
        return JSONObject().put("at", System.currentTimeMillis() / 1000).put("data", data)
    }
    private fun reminders() {
        if (!CompanionStore.enabled(this, "status")) return
        val config = CompanionStore.read(this)
        if (!config.optBoolean("reminders")) return
        val now = java.time.LocalDateTime.now()
        val day = now.toLocalDate().toString()
        fun once(kind: String, text: String, key: String) {
            if (CompanionStore.read(this).optString(kind + "_sent") == key) return
            CompanionStore.update(this) { it.put(kind + "_sent", key) }
            event(kind, text)
            handler.post { getSystemService(NotificationManager::class.java)?.notify(7420 + kind.hashCode().and(255), notification(text, false)) }
        }
        if (now.hour == config.optInt("morning_hour", 8)) once("morning", "早安，记得吃早餐", day)
        if (now.hour == config.optInt("bedtime_hour", 23)) once("bedtime", "该休息啦，晚安", day)
        if (now.hour in 9..21 && now.hour % 2 == 1) once("water", "喝点水，活动一下", "$day-${now.hour}")
    }
    private fun sync() {
        if (!CompanionStore.permissions(this).optBoolean("sharing")) return
        try {
            val config = CompanionStore.read(this)
            val generation = config.optLong("generation")
            // Collect before contacting the server so temporary network loss does not erase the route.
            val now = System.currentTimeMillis() / 1000
            if (now - lastSampleAt >= config.optLong("interval", 60).coerceIn(15, 300)) {
                queue.add("sample", sample()); lastSampleAt = now
            }
            reminders()
            if (config.optBoolean("consent_dirty")) {
                CompanionApi.call(this, "consent", "POST", CompanionStore.permissions(this), generation = generation)
                CompanionStore.update(this) { if (it.optLong("generation") == generation) it.put("consent_dirty", false) }
            }
            val state = CompanionApi.call(this, "state")
            if (CompanionStore.read(this).optLong("generation") != generation) return
            val myConsent = state.getJSONObject("me").getJSONObject("consent")
            if (!myConsent.optBoolean("sharing")) {
                CompanionStore.update(this) { it.put("consent", myConsent); it.remove("state") }
                queue.clear(); handler.post { stopSelf() }; return
            }
            CompanionStore.update(this) { it.put("state", state); it.put("last_sync", System.currentTimeMillis() / 1000) }
            val batch = queue.pending("sample")
            if (batch.length() > 0) {
                CompanionApi.call(this, "samples", "POST", JSONObject().put("samples", batch), generation = generation)
                for (i in 0 until batch.length()) queue.remove(batch.getJSONObject(i).getString("id"))
            }
            if (state.has("peer")) {
                val pending = queue.pending("event")
                for (i in 0 until pending.length()) {
                    val item = pending.getJSONObject(i)
                    try { CompanionApi.call(this, "events", "POST", item, generation = generation); queue.remove(item.getString("id")) }
                    catch (e: CompanionApiException) {
                        if (e.status == 403 || e.status == 400) queue.remove(item.getString("id")) else throw e
                    }
                }
                val inbox = CompanionApi.call(this, "events?after=" + config.optLong("cursor"))
                val events = inbox.getJSONArray("events")
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    queue.receive(event)
                    // Advance before side effects: a process restart must not replay remote commands.
                    CompanionStore.update(this) { it.put("cursor", event.getLong("id")) }
                    if (event.getString("owner") != config.optString("id")) handler.post { receive(event) }
                }
                CompanionStore.update(this) { it.put("cursor", inbox.getLong("cursor")) }
            }
            lastError = ""
            ticks++
            if (ticks % 20 == 0) handler.post { subscribeLocation() }
        } catch (e: Exception) {
            lastError = e.message ?: "连接失败，将自动重试"
            if (e is CompanionApiException && e.status == 401) handler.post { stopSelf() }
        }
    }
    private fun receive(event: JSONObject) {
        val kind = event.getString("kind")
        val permissions = CompanionStore.permissions(this)
        val expired = event.optLong("expires") > 0 && event.getLong("expires") < System.currentTimeMillis() / 1000
        if (expired || !permissions.optBoolean("sharing")) return
        if (kind == "ring" && permissions.optBoolean("ring")) {
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
            handler.postDelayed({ ringtone?.stop() }, 20_000)
        }
        if (kind == "lock" && permissions.optBoolean("lock")) {
            val manager = getSystemService(DevicePolicyManager::class.java)
            if (manager?.isAdminActive(ComponentName(this, CompanionAdminReceiver::class.java)) == true) runCatching { manager.lockNow() }
        }
        getSystemService(NotificationManager::class.java)?.notify(7500 + (event.getLong("id") % 100).toInt(), notification(event.getString("text"), false))
    }
    override fun onDestroy() {
        running = false; location = null
        locations.removeUpdates(this)
        if (registered) unregisterReceiver(receiver)
        ringtone?.stop(); handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        // The worker can still be unwinding an HTTP request; let its DB close after it exits.
        Thread { executor.awaitTermination(25, TimeUnit.SECONDS); queue.close() }.start()
        super.onDestroy()
    }
}
