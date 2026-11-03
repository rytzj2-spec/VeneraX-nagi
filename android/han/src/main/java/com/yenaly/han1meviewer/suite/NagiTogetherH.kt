package com.yenaly.han1meviewer.suite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cn.jzvd.Jzvd
import com.yenaly.han1meviewer.VIDEO_CODE
import com.yenaly.han1meviewer.ui.activity.VideoActivity
import com.yenaly.han1meviewer.ui.view.video.HJzvdStd
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Nagisaland：H 分区的「一起看」（SyncPlay 同步播放）。播放页顶栏的「一起看」按钮打开。
 * 大家填同一个房间名：暂停 / 播放 / 拖进度互相同步；有人换了视频，其他人自动打开同一个视频。
 * 房间一直保持，换视频不用重新进；在对话框里点「退出一起看」才断开。
 */
object NagiTogetherH {
    private const val PREFS = "nagi_together"
    private const val FILE_PREFIX = "nagih|"

    private var client: NagiSyncPlay? = null
    private var playerRef: WeakReference<HJzvdStd>? = null
    private var appCtx: Context? = null
    /** 对方改的状态正在套用：这段时间里播放器的变化不再发回去（否则会来回同步） */
    private var suppressUntil = 0L
    private var lastPos = -1L
    private var lastPosAt = 0L

    val active: Boolean get() = client != null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun toast(msg: String) { appCtx?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() } }
    private fun player(): HJzvdStd? = playerRef?.get()
    private fun suppressed() = SystemClock.uptimeMillis() < suppressUntil
    private fun suppress(ms: Long = 1500) { suppressUntil = SystemClock.uptimeMillis() + ms }

    // ---------------------------------------------------------------- 对话框

    fun showDialog(p: HJzvdStd) {
        val ctx = p.context
        val c = client
        if (c != null) {
            AlertDialog.Builder(ctx)
                .setTitle("一起看")
                .setMessage("房间：${c.room ?: "连接中…"}\n昵称：${c.username ?: "-"}\n暂停、拖进度、换视频都会同步给房间里的人。")
                .setPositiveButton("好") { _, _ -> }
                .setNegativeButton("退出一起看") { _, _ -> leave(); toast("已退出一起看") }
                .show()
            return
        }
        val pr = prefs(ctx)
        val dp = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        fun field(hint: String, value: String): EditText = EditText(ctx).apply {
            this.hint = hint; setText(value); isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
        }.also { box.addView(it) }
        val room = field("房间名（大家填一样的）", pr.getString("room", "") ?: "")
        val name = field("你的昵称", pr.getString("name", android.os.Build.MODEL) ?: "")
        val server = field("同步服务器", pr.getString("server", NagiSyncPlay.DEFAULT_SERVER) ?: NagiSyncPlay.DEFAULT_SERVER)
        box.addView(TextView(ctx).apply {
            text = "默认用公共的 SyncPlay 服务器，不用自己搭。只同步播放进度，画面各自播。"
            textSize = 12f; setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        AlertDialog.Builder(ctx)
            .setTitle("一起看")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("进房间") { _, _ ->
                val r = room.text.toString().trim()
                val n = name.text.toString().trim().ifEmpty { "H 用户" }
                val sv = server.text.toString().trim().ifEmpty { NagiSyncPlay.DEFAULT_SERVER }
                if (r.isEmpty()) { Toast.makeText(ctx, "房间名不能为空", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                pr.edit().putString("room", r).putString("name", n).putString("server", sv).apply()
                join(p, r, n, sv)
            }
            .show()
    }

    private fun join(p: HJzvdStd, room: String, name: String, server: String) {
        val ep = NagiSyncPlay.parse(server) ?: run { toast("服务器地址不对：$server"); return }
        appCtx = p.context.applicationContext
        bind(p)
        val c = NagiSyncPlay(ep.first, ep.second, ep.third, object : NagiSyncPlay.Listener {
            override fun onJoined(room: String, username: String) { toast("已进入房间「$room」") }
            override fun onRoom(type: String, username: String) {
                when (type) {
                    "init" -> if (username.isEmpty()) {
                        toast("房间里只有你，别人进来会跟你的进度")
                        announceFile()
                    } else toast("以 $username 的进度为准")
                    "joined" -> toast("$username 进来了")
                    "left" -> toast("$username 走了")
                }
            }
            override fun onState(position: Double, paused: Boolean, doSeek: Boolean, setBy: String) = applyRemote(position, paused, doSeek, setBy)
            override fun onFile(name: String, setBy: String) = onRemoteFile(name, setBy)
            override fun onChat(username: String, message: String) { toast("$username：$message") }
            override fun onError(message: String, fatal: Boolean) {
                toast("一起看：$message")
                if (fatal) leave()
            }
        })
        client = c
        updateLocal(p)
        c.connect(room, name)
    }

    fun leave() {
        client?.close()
        client = null
    }

    // ---------------------------------------------------------------- 播放器 → 房间

    /** 播放器 setUp（打开了一个视频）时调用 */
    fun bind(p: HJzvdStd) {
        playerRef = WeakReference(p)
        lastPos = -1
        if (client?.connected == true) announceFile()
    }

    private fun announceFile() {
        val p = player() ?: return
        val code = p.videoCode ?: return
        val title = p.jzDataSource?.title?.toString().orEmpty()
        client?.setFile("$FILE_PREFIX$code|$title")
    }

    private fun updateLocal(p: HJzvdStd) {
        val c = client ?: return
        c.paused = p.state != Jzvd.STATE_PLAYING
        c.position = (try { p.currentPositionWhenPlaying } catch (_: Throwable) { 0L }) / 1000.0
    }

    /** onStatePlaying / onStatePause */
    fun onLocalPlayState(p: HJzvdStd) {
        if (client == null || p !== player()) return
        updateLocal(p)
        if (!suppressed()) client?.requestSync()
    }

    /** onProgress（每 100ms 左右）：更新位置；进度突然跳了 = 自己拖了进度条 */
    fun onProgress(p: HJzvdStd, position: Long) {
        val c = client ?: return
        if (p !== player()) return
        updateLocal(p)
        val now = SystemClock.uptimeMillis()
        if (lastPos >= 0) {
            val expected = lastPos + if (p.state == Jzvd.STATE_PLAYING) now - lastPosAt else 0
            if (abs(position - expected) > 2500 && !suppressed()) c.requestSync(doSeek = true)
        }
        lastPos = position
        lastPosAt = now
    }

    // ---------------------------------------------------------------- 房间 → 播放器

    private fun applyRemote(position: Double, paused: Boolean, doSeek: Boolean, setBy: String) {
        val p = player() ?: return
        val mi = p.mediaInterface ?: return
        val playing = p.state == Jzvd.STATE_PLAYING
        if (paused == playing && position != 0.0) {
            suppress()
            try {
                if (paused) {
                    mi.pause(); p.onStatePause()
                    if (setBy.isNotEmpty()) toast("$setBy 暂停了")
                } else if (p.state == Jzvd.STATE_PAUSE) {
                    mi.start(); p.onStatePlaying()
                    if (setBy.isNotEmpty()) toast("$setBy 开始播放")
                }
            } catch (_: Throwable) {}
        }
        val cur = try { p.currentPositionWhenPlaying } catch (_: Throwable) { return }
        val target = (position * 1000).toLong()
        val dur = try { p.duration } catch (_: Throwable) { 0L }
        if ((abs(cur - target) > 1500 || doSeek) && dur > 0) {
            suppress()
            try { mi.seekTo(target.coerceIn(0, dur)) } catch (_: Throwable) {}
            lastPos = -1
        }
    }

    private fun onRemoteFile(name: String, setBy: String) {
        if (!name.startsWith(FILE_PREFIX)) {
            toast("$setBy 在看别的分区的内容"); return
        }
        val parts = name.removePrefix(FILE_PREFIX).split('|', limit = 2)
        val code = parts.getOrNull(0).orEmpty()
        val title = parts.getOrNull(1).orEmpty()
        val p = player()
        if (code.isEmpty() || code == p?.videoCode) return
        val ctx = p?.context ?: appCtx ?: return
        toast("$setBy 换到了：$title")
        try {
            ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra(VIDEO_CODE, code).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            (ctx as? Activity)?.let { if (it is VideoActivity) it.finish() }
        } catch (_: Throwable) {}
    }
}
