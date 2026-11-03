package io.github.kyosee.venera.suite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 「更多」面板、编辑底栏、分区设置（原生，主界面网页和各分区共用） */
object SuitePanels {
    private const val BG = 0xFF16161B.toInt()
    private const val CARD = 0xFF1C1C22.toInt()
    private const val TEXT = 0xFFFAFAF9.toInt()
    private const val SUB = 0xFFA1A1AA.toInt()
    private const val PINK = 0xFFE879A9.toInt()

    private fun Context.dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun bottomDialog(a: Activity, content: View): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.5f)
        }
        return d
    }

    private fun sheet(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(BG)
            val r = a.dp(18f).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(a.dp(16f), a.dp(14f), a.dp(16f), a.dp(20f))
    }

    private fun title(a: Activity, t: String, sub: String? = null) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(a).apply { text = t; setTextColor(TEXT); textSize = 17f; paint.isFakeBoldText = true })
        if (sub != null) addView(TextView(a).apply { text = sub; setTextColor(SUB); textSize = 12.5f; setPadding(0, a.dp(2f), 0, 0) })
        setPadding(0, 0, 0, a.dp(10f))
    }

    private fun button(a: Activity, t: String, primary: Boolean = false, onClick: () -> Unit) = Button(a).apply {
        text = t
        isAllCaps = false
        setTextColor(if (primary) 0xFF18181B.toInt() else TEXT)
        background = GradientDrawable().apply {
            cornerRadius = a.dp(10f).toFloat()
            if (primary) { colors = intArrayOf(0xFFE879A9.toInt(), 0xFFA78BFA.toInt()); orientation = GradientDrawable.Orientation.LEFT_RIGHT }
            else setColor(CARD)
        }
        setOnClickListener { onClick() }
    }

    /** 一格：图标 + 名字 */
    private class Cell(ctx: Context, val key: String, val highlighted: Boolean) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * d; textAlign = Paint.Align.CENTER }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (76 * d).toInt())

        override fun onDraw(c: Canvas) {
            val r = 12 * d
            c.drawRoundRect(3 * d, 3 * d, width - 3 * d, height - 3 * d, r, r, bg)
            val itemH = 22 * d + 2 * d + text.textSize * 1.5f
            SuiteNavItems.draw(c, key, width / 2f, (height - itemH) / 2, d, if (highlighted) 0xFFF0ABFC.toInt() else TEXT, stroke, text)
        }
    }

    // ---------------------------------------------------------------- 更多
    fun showMore(a: Activity, current: String, onPick: (String) -> Unit) {
        val root = sheet(a)
        root.addView(title(a, "更多分区", "点一下打开；长按放到底栏"))
        val keys = SuiteNavConfig.more(a)
        val grid = GridLayout(a).apply { columnCount = 4 }
        lateinit var dialog: Dialog
        for (k in keys) {
            val cell = Cell(a, k, k == current)
            cell.setOnClickListener {
                dialog.dismiss()
                if (k != current) onPick(k)
            }
            cell.setOnLongClickListener {
                val err = SuiteNavConfig.moveToBar(a, k)
                Toast.makeText(a, err ?: "「${SuiteNavItems.label(k)}」已放到底栏", Toast.LENGTH_SHORT).show()
                if (err == null) dialog.dismiss()
                true
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        if (keys.isEmpty()) {
            root.addView(TextView(a).apply { text = "所有分区都在底栏上了"; setTextColor(SUB); setPadding(0, a.dp(8f), 0, a.dp(8f)) })
        }
        root.addView(grid)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(14f), 0, 0) }
        row.addView(button(a, "编辑底栏") { dialog.dismiss(); showEditor(a) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(button(a, "流畅度设置") { dialog.dismiss(); showSettings(a) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = bottomDialog(a, root)
        dialog.show()
    }

    // ---------------------------------------------------------------- 编辑底栏
    private class Row(val key: String, var inBar: Boolean)

    @SuppressLint("ClickableViewAccessibility", "NotifyDataSetChanged")
    fun showEditor(a: Activity) {
        val rows = SuiteNavConfig.order(a).let { order ->
            val bar = SuiteNavConfig.inBar(a)
            order.map { Row(it, it in bar) }.toMutableList()
        }
        val root = sheet(a)
        val header = TextView(a).apply { setTextColor(SUB); textSize = 12.5f }
        fun refreshHeader() {
            val n = rows.count { it.inBar }
            header.text = "底栏现在 $n 个（${SuiteNavConfig.MIN_BAR}~${SuiteNavConfig.MAX_BAR} 个），「更多」固定在最后。按住左边 ≡ 拖动排序。"
            header.setTextColor(if (n in SuiteNavConfig.MIN_BAR..SuiteNavConfig.MAX_BAR) SUB else 0xFFF87171.toInt())
        }
        root.addView(title(a, "编辑底栏"))
        root.addView(header)

        lateinit var helper: ItemTouchHelper
        val list = RecyclerView(a)
        list.layoutManager = LinearLayoutManager(a)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val line = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(a.dp(4f), a.dp(4f), a.dp(4f), a.dp(4f))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                line.addView(TextView(a).apply { text = "≡"; textSize = 22f; setTextColor(SUB); setPadding(a.dp(8f), 0, a.dp(14f), 0); tag = "handle" })
                line.addView(Cell(a, "lib", false).apply { tag = "icon" }, LinearLayout.LayoutParams(a.dp(64f), a.dp(64f)))
                line.addView(TextView(a).apply { setTextColor(TEXT); textSize = 15f; setPadding(a.dp(12f), 0, 0, 0); tag = "label" },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                @Suppress("DEPRECATION")
                line.addView(Switch(a).apply { tag = "switch"; text = "底栏 "; setTextColor(SUB) })
                return object : RecyclerView.ViewHolder(line) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val r = rows[position]
                val v = h.itemView as LinearLayout
                val icon = v.findViewWithTag<View>("icon")
                val idx = v.indexOfChild(icon)
                v.removeViewAt(idx)
                v.addView(Cell(a, r.key, r.inBar).apply { tag = "icon" }, idx, LinearLayout.LayoutParams(a.dp(64f), a.dp(64f)))
                v.findViewWithTag<TextView>("label").text = SuiteNavItems.label(r.key) + if (r.inBar) "" else "（更多）"
                @Suppress("DEPRECATION")
                val sw = v.findViewWithTag<Switch>("switch")
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = r.inBar
                sw.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                    val n = rows.count { it.inBar }
                    if (on && n >= SuiteNavConfig.MAX_BAR) {
                        Toast.makeText(a, "底栏最多 ${SuiteNavConfig.MAX_BAR} 个", Toast.LENGTH_SHORT).show()
                        sw.isChecked = false
                        return@setOnCheckedChangeListener
                    }
                    if (!on && n <= SuiteNavConfig.MIN_BAR) {
                        Toast.makeText(a, "底栏至少 ${SuiteNavConfig.MIN_BAR} 个", Toast.LENGTH_SHORT).show()
                        sw.isChecked = true
                        return@setOnCheckedChangeListener
                    }
                    r.inBar = on
                    refreshHeader()
                    list.post { notifyItemChanged(h.bindingAdapterPosition) }
                }
                v.findViewWithTag<View>("handle").setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) helper.startDrag(h)
                    false
                }
            }
        }
        helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val f = from.bindingAdapterPosition
                val t = to.bindingAdapterPosition
                rows.add(t, rows.removeAt(f))
                rv.adapter?.notifyItemMoved(f, t)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = true
        })
        helper.attachToRecyclerView(list)
        refreshHeader()
        val h = (a.resources.displayMetrics.heightPixels * 0.55f).toInt()
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))

        lateinit var dialog: Dialog
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dp(12f), 0, 0) }
        row.addView(button(a, "取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(a), LinearLayout.LayoutParams(a.dp(10f), 1))
        row.addView(button(a, "保存", primary = true) {
            val err = SuiteNavConfig.save(a, rows.map { it.key }, rows.filter { it.inBar }.map { it.key }.toSet())
            if (err != null) Toast.makeText(a, err, Toast.LENGTH_SHORT).show()
            else { Toast.makeText(a, "已保存", Toast.LENGTH_SHORT).show(); dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        dialog = bottomDialog(a, root)
        dialog.show()
    }

    // ---------------------------------------------------------------- 流畅度设置
    fun showSettings(a: Activity) {
        val root = sheet(a)
        root.addView(title(a, "流畅度设置"))
        fun sw(t: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
            val box = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(8f), 0, a.dp(8f)) }
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(a).apply { text = t; setTextColor(TEXT); textSize = 15f })
            texts.addView(TextView(a).apply { text = sub; setTextColor(SUB); textSize = 12f })
            box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            @Suppress("DEPRECATION")
            box.addView(Switch(a).apply { isChecked = value; setOnCheckedChangeListener { _, on -> onChange(on) } })
            root.addView(box)
        }
        sw("Impeller 渲染（实验）", "漫画 / 番剧 / 影视的 Flutter 用 Impeller 代替 Skia。可能更流畅，番剧视频可能黑屏。完全退出 App 再打开才生效",
            SuiteEngines.impellerEnabled(a)) { SuiteEngines.setImpeller(a, it) }
        sw("后台预热", "打开 App 后空闲时先把番剧、影视准备好，切过去不卡（多占一些内存）",
            SuiteEngines.prewarmEnabled(a)) { SuiteEngines.setPrewarm(a, it) }
        sw("记录切换耗时", "每次切分区从点击到画面出来的毫秒数，写到 内部存储/Nagisaland/debug/perf.txt",
            SuitePerf.enabled(a)) { SuitePerf.setEnabled(a, it) }
        lateinit var dialog: Dialog
        root.addView(FrameLayout(a).apply { setPadding(0, a.dp(10f), 0, 0); addView(button(a, "完成", primary = true) { dialog.dismiss() }) })
        dialog = bottomDialog(a, ScrollView(a).apply { addView(root) })
        dialog.show()
    }
}
