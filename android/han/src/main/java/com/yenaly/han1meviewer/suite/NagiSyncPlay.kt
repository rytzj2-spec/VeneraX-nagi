package com.yenaly.han1meviewer.suite

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Nagisaland：SyncPlay 客户端（Kotlin 版，协议照番剧 Kazumi 的 lib/services/player/syncplay_client.dart）。
 * 一行一个 JSON（\r\n 结尾）；官方服务器（syncplay.pl:8995~8999）先 STARTTLS。
 * 回调都在主线程。
 */
class NagiSyncPlay(
    private val host: String,
    private val port: Int,
    private val tls: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onJoined(room: String, username: String) {}
        /** 房间里只有自己（type=init, username 为空）/ 有人加入 / 离开 */
        fun onRoom(type: String, username: String) {}
        /** 服务器推来的播放状态（已经算上网络延迟）；setBy = 谁改的 */
        fun onState(position: Double, paused: Boolean, doSeek: Boolean, setBy: String) {}
        fun onFile(name: String, setBy: String) {}
        fun onChat(username: String, message: String) {}
        fun onError(message: String, fatal: Boolean) {}
    }

    companion object {
        private const val TAG = "NagiSyncPlay"
        val OFFICIAL = setOf("syncplay.pl:8995", "syncplay.pl:8996", "syncplay.pl:8997", "syncplay.pl:8998", "syncplay.pl:8999")
        const val DEFAULT_SERVER = "syncplay.pl:8996"

        /** "host:port" → (host, port, 要不要 TLS) */
        fun parse(endPoint: String): Triple<String, Int, Boolean>? {
            val s = endPoint.trim()
            val i = s.lastIndexOf(':')
            if (i <= 0) return null
            val h = s.substring(0, i).trim().removePrefix("[").removeSuffix("]")
            val p = s.substring(i + 1).trim().toIntOrNull() ?: return null
            if (h.isEmpty() || p !in 1..65535) return null
            return Triple(h, p, "${h.lowercase()}:$p" in OFFICIAL)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closed = false

    @Volatile var room: String? = null; private set
    @Volatile var username: String? = null; private set
    @Volatile var currentFile: String? = null; private set

    /** 本机播放器当前状态（播放器那边随时更新；服务器每次 State 心跳都用它回复） */
    @Volatile var position = 0.0
    @Volatile var paused = true

    private var lastLatencyCalculation: Double? = null
    private var clientIgnoringOnTheFly = 0
    private var serverIgnoringOnTheFly = 0
    private var clientRtt = 0.0
    private var serverRtt = 0.0
    private var avrRtt = 0.0
    private var fd = 0.0

    val connected: Boolean get() = !closed && socket != null && room != null

    /** 连接并进房间（在后台线程里做，结果走 listener） */
    fun connect(room: String, username: String) {
        Thread({
            try {
                val plain = Socket()
                plain.connect(InetSocketAddress(host, port), 10000)
                var s: Socket = plain
                if (tls) {
                    writeRaw(plain.getOutputStream(), JSONObject().put("TLS", JSONObject().put("startTLS", "send")))
                    plain.soTimeout = 10000
                    val line = BufferedReader(InputStreamReader(plain.getInputStream(), Charsets.UTF_8)).readLine()
                        ?: throw IllegalStateException("服务器没回应加密请求")
                    val ok = runCatching { JSONObject(line).getJSONObject("TLS").optString("startTLS") == "true" }.getOrDefault(false)
                    if (!ok) throw IllegalStateException("服务器拒绝加密连接")
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                    ssl.startHandshake()
                    s = ssl
                }
                s.soTimeout = 0
                socket = s
                out = s.getOutputStream()
                send(JSONObject().put("Hello", JSONObject()
                    .put("username", username)
                    .put("room", JSONObject().put("name", room))
                    .put("version", "1.7.0")
                    .put("features", JSONObject()
                        .put("sharedPlaylists", true).put("chat", true).put("featureList", true)
                        .put("readiness", true).put("managedRooms", false))))
                readLoop(s)
            } catch (e: Throwable) {
                if (!closed) {
                    Log.w(TAG, "connection", e)
                    main.post { listener.onError("连接失败：${e.message ?: e.javaClass.simpleName}", true) }
                }
                close()
            }
        }, "nagi-syncplay").start()
    }

    private fun readLoop(s: Socket) {
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        while (!closed) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            try { handle(JSONObject(line)) } catch (e: Throwable) { Log.w(TAG, "bad message: $line", e) }
        }
        if (!closed) main.post { listener.onError("和同步服务器的连接断了", true) }
        close()
    }

    private fun handle(j: JSONObject) {
        j.optJSONObject("Hello")?.let { h ->
            val r = h.optJSONObject("room")?.optString("name")
            val u = h.optString("username")
            if (r != null) {
                room = r; username = u
                // 加入 + 就绪
                send(JSONObject().put("Set", JSONObject().put(r, JSONObject()
                    .put("room", JSONObject().put("name", r)).put("event", JSONObject().put("joined", true)))))
                send(JSONObject().put("Set", JSONObject().put("ready", JSONObject().put("isReady", true).put("manuallyInitiated", false))))
                main.post { listener.onJoined(r, u) }
            }
            return
        }
        j.optJSONObject("State")?.let { st ->
            st.optJSONObject("ping")?.let { ping ->
                lastLatencyCalculation = if (ping.has("latencyCalculation")) ping.optDouble("latencyCalculation") else null
                if (ping.has("serverRtt")) serverRtt = ping.optDouble("serverRtt", 0.0)
                if (ping.has("clientLatencyCalculation")) updateRtt(ping.optDouble("clientLatencyCalculation"), serverRtt)
            }
            st.optJSONObject("ignoringOnTheFly")?.let { ig ->
                if (ig.has("server")) { serverIgnoringOnTheFly = ig.optInt("server"); clientIgnoringOnTheFly = 0 }
                else if (ig.has("client") && ig.optInt("client") == clientIgnoringOnTheFly) clientIgnoringOnTheFly = 0
            }
            if (clientIgnoringOnTheFly == 0) {
                val ps = st.optJSONObject("playstate") ?: JSONObject()
                val p = ps.optDouble("position", 0.0)
                val pa = ps.optBoolean("paused", true)
                val calc = if (pa) p else p + fd
                position = calc
                paused = pa
                val doSeek = ps.optBoolean("doSeek", false)
                val by = ps.optString("setBy", "")
                main.post { listener.onState(calc, pa, doSeek, by) }
            }
            sendState(stateChange = false, doSeek = null)
            return
        }
        j.optJSONObject("Set")?.let { set ->
            set.optJSONObject("playlistIndex")?.let { pi ->
                val u = pi.optString("user", "")
                main.post { listener.onRoom("init", u) }
                return
            }
            set.optJSONObject("user")?.let { users ->
                for (name in users.keys()) {
                    val d = users.optJSONObject(name) ?: continue
                    d.optJSONObject("event")?.keys()?.let { ks -> if (ks.hasNext()) { val ev = ks.next(); main.post { listener.onRoom(ev, name) } } }
                    d.optJSONObject("file")?.optString("name")?.let { f ->
                        currentFile = f
                        main.post { listener.onFile(f, name) }
                    }
                }
            }
            return
        }
        j.optJSONObject("Chat")?.let { c ->
            val u = c.optString("username"); val m = c.optString("message")
            if (m.isNotEmpty()) main.post { listener.onChat(u, m) }
        }
    }

    private fun updateRtt(timestamp: Double, senderRtt: Double) {
        val newRtt = System.currentTimeMillis() / 1000.0 - timestamp
        if (newRtt < 0 || senderRtt < 0) return
        clientRtt = newRtt
        if (avrRtt == 0.0) avrRtt = clientRtt
        avrRtt = avrRtt * 0.85 + clientRtt * 0.15
        fd = if (senderRtt < clientRtt) avrRtt / 2 + (clientRtt - senderRtt) else avrRtt / 2
    }

    /** 自己改了播放状态（播放 / 暂停 / 拖进度）后调用 */
    fun requestSync(doSeek: Boolean? = null) {
        Thread({ sendState(stateChange = true, doSeek = doSeek) }, "nagi-syncplay-send").start()
    }

    /** 告诉房间自己在看什么（换片 / 换集时） */
    fun setFile(name: String, durationSec: Double = 10800.0) {
        val r = room ?: return
        val u = username ?: return
        currentFile = name
        Thread({
            send(JSONObject().put("Set", JSONObject()
                .put("file", JSONObject().put("duration", durationSec).put("name", name).put("size", 220514438))
                .put("user", JSONObject().put(u, JSONObject().put("room", JSONObject().put("name", r))))))
            sendState(stateChange = false, doSeek = null)
        }, "nagi-syncplay-send").start()
    }

    @Synchronized
    private fun sendState(stateChange: Boolean, doSeek: Boolean?) {
        if (stateChange) clientIgnoringOnTheFly++
        val ig = JSONObject()
        if (serverIgnoringOnTheFly > 0) { ig.put("server", serverIgnoringOnTheFly); serverIgnoringOnTheFly = 0 }
        if (clientIgnoringOnTheFly > 0) ig.put("client", clientIgnoringOnTheFly)
        val ping = JSONObject().put("clientRtt", clientRtt).put("clientLatencyCalculation", System.currentTimeMillis() / 1000.0)
        lastLatencyCalculation?.let { ping.put("latencyCalculation", it) }
        val ps = JSONObject().put("position", position).put("paused", paused)
        username?.let { ps.put("setBy", it) }
        ps.put("doSeek", doSeek ?: JSONObject.NULL)
        val state = JSONObject().put("ping", ping).put("playstate", ps)
        if (ig.length() > 0) state.put("ignoringOnTheFly", ig)
        send(JSONObject().put("State", state))
    }

    private fun send(j: JSONObject) {
        val o = out ?: return
        try {
            synchronized(o) { writeRaw(o, j) }
        } catch (e: Throwable) {
            if (!closed) main.post { listener.onError("发送失败：${e.message}", true) }
            close()
        }
    }

    private fun writeRaw(o: OutputStream, j: JSONObject) {
        o.write((j.toString() + "\r\n").toByteArray(Charsets.UTF_8))
        o.flush()
    }

    fun close() {
        if (closed) return
        closed = true
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        out = null
        room = null
    }
}
