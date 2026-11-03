package io.github.kyosee.venera.suite

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 「更多 → 一起看」：屏幕共享、看别人的屏幕、同步播放的说明 */
object SuiteTogetherUi {
    fun showMenu(a: Activity) {
        val dp = a.resources.displayMetrics.density
        val root = SuitePanels.sheet(a)
        root.addView(SuitePanels.title(a, "一起看"))
        lateinit var dialog: Dialog
        fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fun gap() = root.addView(View(a), LinearLayout.LayoutParams(1, (10 * dp).toInt()))

        root.addView(TextView(a).apply {
            text = "同步播放（推荐）：番剧、影视、H 的播放页里点「一起看」，大家填同一个房间名，就会一起暂停 / 拖进度 / 换集。" +
                "画面各自播放，不费流量，用公共的 SyncPlay 服务器，不用自己搭。影视要连同一个 Emby / Jellyfin 服务器。"
            setTextColor(SuitePanels.SUB); textSize = 13f; setLineSpacing(0f, 1.2f)
        })
        gap()
        root.addView(SuitePanels.button(a, if (SuiteScreenShare.running) "正在共享屏幕（看链接 / 停止）" else "共享我的屏幕", primary = true) {
            dialog.dismiss()
            if (SuiteScreenShare.running) SuiteScreenShare.showLinks(a) else SuiteScreenShare.start(a)
        }, full())
        root.addView(TextView(a).apply {
            text = "手机自己当服务器，对方用浏览器打开链接就能看（画面 + 声音）。同一个 Wi-Fi 直接能看；不在一起时两边装 Tailscale 之类的组网工具。" +
                "有版权保护的视频 App 录出来是黑的。"
            setTextColor(SuitePanels.SUB); textSize = 12f
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        gap()
        root.addView(SuitePanels.button(a, "看别人共享的屏幕") { dialog.dismiss(); askWatch(a) }, full())
        dialog = SuitePanels.bottomDialog(a, root)
        dialog.show()
    }

    private fun askWatch(a: Activity) {
        val dp = a.resources.displayMetrics.density
        val root = SuitePanels.sheet(a)
        root.addView(SuitePanels.title(a, "看别人的屏幕", "粘贴对方发来的链接（http://…/口令/），会用浏览器打开"))
        val edit = EditText(a).apply {
            hint = "http://192.168.x.x:8686/xxxxxx/"
            setTextColor(SuitePanels.TEXT); setHintTextColor(SuitePanels.SUB); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        root.addView(edit)
        lateinit var dialog: Dialog
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (14 * dp).toInt(), 0, 0) }
        row.addView(SuitePanels.button(a, "取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams((10 * dp).toInt(), 1))
        row.addView(SuitePanels.button(a, "打开", primary = true) {
            var u = edit.text.toString().trim()
            // 对方可能把说明文字一起发过来了：取出里面的链接
            Regex("https?://\\S+").find(u)?.let { u = it.value }
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
                Toast.makeText(a, "没认出链接", Toast.LENGTH_SHORT).show(); return@button
            }
            dialog.dismiss()
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Throwable) {
                Toast.makeText(a, "打不开浏览器：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = SuitePanels.bottomDialog(a, root)
        dialog.show()
    }
}
