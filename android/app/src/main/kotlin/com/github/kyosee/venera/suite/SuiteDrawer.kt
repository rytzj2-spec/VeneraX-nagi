package io.github.kyosee.venera.suite

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference

/** One native drawer for every engine and the launcher, using the same profile permissions. */
object SuiteDrawer {
    private var showing: WeakReference<Dialog>? = null
    private var owner: WeakReference<Activity>? = null

    fun dismissFor(a: Activity) {
        if (owner?.get() === a) showing?.get()?.dismiss()
    }

    fun show(a: Activity, current: String? = SuiteNav.keyOf(a)) {
        if (a.isFinishing || a.isDestroyed) return
        if (showing?.get()?.isShowing == true) return
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        val dialog = Dialog(a)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        fun label(value: String, size: Float, color: Int) = TextView(a).apply {
            text = value; textSize = size; setTextColor(color)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val heading = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("Nagisaland", 24f, SuitePanels.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(TextView(a).apply {
            text = "×"; textSize = 28f; setTextColor(SuitePanels.SUB)
            gravity = Gravity.CENTER; contentDescription = "关闭导航"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(heading)
        column.addView(label(SuiteProfile.profileName(a) ?: "我的小站 · 我的收藏与陪伴", 13f, SuitePanels.SUB))
        fun row(title: String, key: String? = null, selected: Boolean = false, action: () -> Unit) {
            val line = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(54); setPadding(dp(12), dp(4), dp(12), dp(4))
                background = GradientDrawable().apply {
                    setColor(if (selected) 0xFF332333.toInt() else SuitePanels.BG)
                    cornerRadius = dp(12).toFloat()
                }
                isFocusable = true
                isSelected = selected
                contentDescription = title + if (selected) "，当前分区" else ""
                setOnClickListener { dialog.dismiss(); action() }
            }
            if (key != null) line.addView(object : View(a) {
                private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (selected) SuitePanels.PINK else SuitePanels.SUB
                    style = Paint.Style.STROKE; strokeWidth = 1.7f
                    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                }
                override fun onDraw(canvas: Canvas) {
                    canvas.save(); canvas.scale(width / 24f, height / 24f)
                    SuiteNavItems.paths(key).forEach { canvas.drawPath(it, pen) }
                    canvas.restore()
                }
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
            line.addView(label(title, 16f, if (selected) SuitePanels.PINK else SuitePanels.TEXT))
            column.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        column.addView(label("分区", 12f, SuitePanels.SUB))
        SuiteNavConfig.order(a).forEach { key ->
            row(SuiteNavItems.label(key), key, key == current) { SuiteNav.go(a, key) }
        }
        column.addView(label("常用功能", 12f, SuitePanels.SUB))
        row("一起看") { a.startActivity(Intent(a, SuiteTogetherActivity::class.java)) }
        row("一键配置") { SuiteConfigUi.showMenu(a) }
        if (current != null && current in SuiteNavConfig.ALL) {
            row("当前分区设置") { SuiteNav.openSettings(a, current) }
        }
        row("编辑底栏") { SuitePanels.showEditor(a) }
        row("流畅度与手势") { SuitePanels.showSettings(a) }
        val scroll = ScrollView(a).apply {
            addView(column); isFillViewport = true
            background = GradientDrawable().apply {
                setColor(SuitePanels.BG)
                val radius = dp(24).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
            clipToOutline = true
        }
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { showing = null; owner = null }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.40f)
            setGravity(Gravity.START or Gravity.TOP)
            setWindowAnimations(io.github.kyosee.venera.R.style.NagiDrawerAnimation)
            setLayout(minOf(dp(360), (a.resources.displayMetrics.widthPixels * .86f).toInt()), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        showing = WeakReference(dialog)
        owner = WeakReference(a)
    }
}
