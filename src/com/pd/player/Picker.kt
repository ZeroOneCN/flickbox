package com.pd.player

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/**
 * 底部滑出的选片面板：搜索 + 排序 + 缩略图列表，当前正在播的那条高亮。
 * 只用框架的 Dialog/ListView，不引入 AndroidX，也不新增任何资源文件。
 *
 * 列表里存的是全量数据，搜索只是过滤出 [visible] 一组下标，
 * 所以序号和「跳到第几条」始终按原始列表算，不会因为过滤而错位。
 */
class PickerSheet(
    context: Context,
    items: List<VideoItem>,
    current: Int,
    sortMode: Int,
    keyword: String,
    private val onPick: (Int) -> Unit,
    private val onSort: (Int, String) -> Unit
) : Dialog(context, android.R.style.Theme_DeviceDefault_Dialog) {

    private val root = LinearLayout(context)
    private val list = ListView(context)
    private val tvSub = TextView(context)
    private val etSearch = EditText(context)
    private val visible = ArrayList<Int>(64)
    private val chips = arrayOfNulls<TextView>(VideoRepo.SORT_NAMES.size)

    private var items = items
    private var current = current
    private var sortMode = sortMode
    private var keyword = keyword

    private val thumbW = dp(46)
    private val thumbH = dp(66)

    /**
     * 排序 tab 点一下就地生效：列表数据和序号跟着换，面板不动、不重开。
     * 换序后整列表重排，滚动位置定位回正在播的那条。
     */
    fun updateSort(mode: Int, newList: List<VideoItem>, currentIndex: Int) {
        items = newList
        current = currentIndex
        sortMode = mode
        bindChips(mode)
        applyFilter(keyword)
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        root.orientation = LinearLayout.VERTICAL
        root.background = sheetBg()
        root.setPadding(dp(16), dp(14), dp(16), dp(10))

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val titleBox = LinearLayout(context)
        titleBox.orientation = LinearLayout.VERTICAL
        val tvTitle = text(16f, Color.WHITE, true)
        tvTitle.text = "选择视频"
        style(tvSub, 11f, SUB)
        titleBox.addView(tvTitle, matchWrap())
        titleBox.addView(tvSub, matchWrap())
        head.addView(titleBox, weight(1f))

        val close = text(17f, SUB)
        close.text = "✕"
        close.setPadding(dp(10), dp(4), dp(4), dp(4))
        close.isClickable = true
        close.setOnClickListener { dismiss() }
        head.addView(close, wrap(0, 0, 0, 0))
        root.addView(head, matchWrap())

        // 搜索框：按片名或目录过滤，边打边筛
        etSearch.setText(keyword)
        etSearch.hint = "搜索片名或目录"
        etSearch.setTextColor(Color.WHITE)
        etSearch.setHintTextColor(SUB)
        etSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        etSearch.setSingleLine()
        etSearch.imeOptions = EditorInfo.IME_ACTION_SEARCH
        etSearch.background = fieldBg()
        etSearch.setPadding(dp(12), dp(9), dp(12), dp(9))
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
        })
        val searchLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        searchLp.topMargin = dp(10)
        root.addView(etSearch, searchLp)

        // 排序：点了就地重排列表，这里只负责把当前档位点亮
        val sorts = LinearLayout(context)
        sorts.orientation = LinearLayout.HORIZONTAL
        for (i in VideoRepo.SORT_NAMES.indices) {
            val tv = text(12f, Color.WHITE)
            tv.text = VideoRepo.SORT_NAMES[i]
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(12), dp(5), dp(12), dp(5))
            tv.maxLines = 1
            tv.isClickable = true
            tv.setOnClickListener { onSort(i, etSearch.text.toString()) }
            chips[i] = tv
            sorts.addView(tv, wrap(dp(0), dp(10), dp(8), dp(0)))
        }
        bindChips(sortMode)
        root.addView(sorts, matchWrap())

        list.adapter = Rows()
        list.divider = null
        list.setSelector(android.R.color.transparent)
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.setOnItemClickListener { _, _, position, _ ->
            val which = visible.getOrNull(position) ?: return@setOnItemClickListener
            onPick(which)
            dismiss()
        }
        val listLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        listLp.topMargin = dp(10)
        root.addView(list, listLp)

        // 等 adapter 挂上再过滤，否则 notifyDataSetChanged / setSelection 会打在空列表上
        applyFilter(keyword)

        val box = FrameLayout(context)
        box.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(box)
    }

    /** 按关键字过滤出可见行；空关键字就是全量。 */
    private fun applyFilter(word: String) {
        keyword = word
        val q = word.trim()
        visible.clear()
        for (i in items.indices) {
            if (q.isEmpty() ||
                items[i].title.contains(q, true) ||
                VideoRepo.folderOf(items[i]).contains(q, true)
            ) visible.add(i)
        }
        tvSub.text = if (q.isEmpty()) {
            "共 ${items.size} 条 · 点一条直接播放"
        } else {
            "命中 ${visible.size}/${items.size} 条 · 点一条直接播放"
        }
        (list.adapter as? Rows)?.notifyDataSetChanged()
        val at = visible.indexOf(current)
        if (at >= 0) list.setSelection(at)
    }

    override fun onStart() {
        super.onStart()
        val w = window ?: return
        // 去掉窗口自带的浅色底板，只留我们画的圆角卡片
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // 不主动弹键盘：面板一打开就挡一半列表，搜索是少数动作
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        val screenH = context.resources.displayMetrics.heightPixels
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.72f).toInt())
        w.setGravity(Gravity.BOTTOM)
        w.setDimAmount(0.6f)
        // 面板从底部推上来，接近系统面板的手感
        root.translationY = dp(90).toFloat()
        root.alpha = 0f
        root.animate().translationY(0f).alpha(1f).setDuration(200L).start()
        val at = visible.indexOf(current).coerceAtLeast(0)
        list.setSelection(at)
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Any = items[visible[position]]
        override fun getItemId(position: Int): Long = items[visible[position]].id

        override fun getView(position: Int, cv: View?, parent: ViewGroup?): View {
            val holder = (cv?.tag as? Holder) ?: build()
            val global = visible[position]
            holder.bind(global, items[global], global == current)
            return holder.row
        }

        private fun build(): Holder {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(6), dp(6), dp(6), dp(6))

            val thumb = ImageView(context)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.background = placeholder()
            thumb.clipToOutline = true
            thumb.outlineProvider = rounded(dp(8))
            val thumbLp = wrap(0, 0, dp(10), 0)
            thumbLp.width = thumbW
            thumbLp.height = thumbH
            row.addView(thumb, thumbLp)

            val col = LinearLayout(context)
            col.orientation = LinearLayout.VERTICAL
            val tvName = text(13f, Color.WHITE)
            tvName.maxLines = 1
            tvName.ellipsize = TextUtils.TruncateAt.MIDDLE
            val tvMeta = text(11f, SUB)
            tvMeta.maxLines = 1
            tvMeta.ellipsize = TextUtils.TruncateAt.END
            col.addView(tvName, matchWrap())
            col.addView(tvMeta, matchWrap())
            row.addView(col, weight(1f))

            val flag = text(11f, Color.WHITE)
            flag.text = "播放中"
            flag.background = chipBg(true)
            flag.setPadding(dp(8), dp(3), dp(8), dp(3))
            row.addView(flag, wrap(0, 0, 0, 0))

            val h = Holder(row, thumb, tvName, tvMeta, flag)
            row.tag = h
            return h
        }
    }

    private inner class Holder(
        val row: LinearLayout,
        val thumb: ImageView,
        val tvName: TextView,
        val tvMeta: TextView,
        val flag: TextView
    ) {
        /** global 是在完整列表里的下标：过滤之后序号仍要和翻页层对得上。 */
        fun bind(global: Int, item: VideoItem, active: Boolean) {
            tvName.text = item.title
            tvMeta.text = "#${global + 1} · " + fmt(item.durationMs) +
                " · " + VideoRepo.folderOf(item)
            flag.visibility = if (active) View.VISIBLE else View.INVISIBLE
            row.background = if (active) activeBg() else null

            val id = item.id
            thumb.tag = id
            val cached = VideoRepo.peekSmall(id)
            if (cached != null) {
                thumb.setImageBitmap(cached)
            } else {
                thumb.setImageDrawable(null)
                VideoRepo.loadSmall(context, item, thumbW, thumbH) { bmp ->
                    if (bmp == null || thumb.tag as? Long != id) return@loadSmall
                    thumb.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun bindChips(mode: Int) {
        for (i in chips.indices) {
            val tv = chips[i] ?: continue
            tv.background = chipBg(i == mode)
            tv.setTypeface(null, if (i == mode) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun sheetBg(): GradientDrawable {        val d = GradientDrawable()
        d.setColor(SHEET)
        val r = dp(20).toFloat()
        d.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        return d
    }

    private fun activeBg(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(ACTIVE)
        d.cornerRadius = dp(12).toFloat()
        return d
    }

    private fun chipBg(active: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(if (active) CHIP else FIELD)
        d.cornerRadius = dp(14).toFloat()
        return d
    }

    private fun fieldBg(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(FIELD)
        d.cornerRadius = dp(14).toFloat()
        return d
    }

    private fun placeholder(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(PLACEHOLDER)
        d.cornerRadius = dp(8).toFloat()
        return d
    }

    private fun rounded(r: Int): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, r.toFloat())
        }
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        style(TextView(context), sizeSp, color, bold)

    private fun weight(scale: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, scale
    )

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun wrap(l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(l, t, r, b)
        return p
    }

    private fun fmt(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val SHEET = 0xF20B0B0F.toInt()
        private const val SUB = 0x9AFFFFFF.toInt()
        private const val ACTIVE = 0x1FFFFFFF
        private const val CHIP = 0x4DFFFFFF
        private const val FIELD = 0x14FFFFFF
        private const val PLACEHOLDER = 0x14FFFFFF
    }
}

/** 信息面板里的一行；值可能很长（路径、URI），由面板自己决定换行。 */
class InfoRow(val label: String, val value: String)

/**
 * 当前这条视频的详细信息：媒体库字段 + 解码器实测参数 + 播放器状态。
 * 排查「这条为什么播不了 / 为什么是这个比例」用得上，中间弹出的卡片式对话框。
 */
class InfoSheet(context: Context, title: String) :
    Dialog(context, android.R.style.Theme_DeviceDefault_Dialog) {

    private val rows = LinearLayout(context)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = cardBg()
        card.setPadding(dp(18), dp(14), dp(18), dp(14))

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val tvTitle = text(15f, Color.WHITE, true)
        tvTitle.text = "视频信息"
        head.addView(tvTitle, weight(1f))
        val close = text(16f, SUB)
        close.text = "✕"
        close.setPadding(dp(10), dp(2), dp(2), dp(2))
        close.isClickable = true
        close.setOnClickListener { dismiss() }
        head.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(head, matchWrap())

        val name = text(13f, Color.WHITE)
        name.text = title
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        val nameLp = matchWrap()
        nameLp.topMargin = dp(6)
        card.addView(name, nameLp)

        rows.orientation = LinearLayout.VERTICAL
        val scroll = object : ScrollView(context) {
            /** 内容少就贴着内容高，内容多时最多占到屏幕的 42%，剩下的靠滚动。 */
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val cap = (context.resources.displayMetrics.heightPixels * 0.42f).toInt()
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        // ScrollView 自己没有公开的 LayoutParams 内部类，用父类 FrameLayout 的
        scroll.addView(rows, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val scrollLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scrollLp.topMargin = dp(10)
        card.addView(scroll, scrollLp)

        setContentView(card)
    }

    /** 全量刷新：解码器的参数是异步取回来的，晚到一次就重画一遍。 */
    fun setRows(data: List<InfoRow>) {
        rows.removeAllViews()
        for (r in data) {
            val line = LinearLayout(context)
            line.orientation = LinearLayout.HORIZONTAL
            line.setPadding(0, dp(4), 0, dp(4))
            val label = text(11f, SUB)
            label.text = r.label
            val lp = LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT)
            line.addView(label, lp)
            val value = text(12f, Color.WHITE)
            value.text = r.value
            value.setTextIsSelectable(true)
            line.addView(value, weight(1f))
            rows.addView(line, matchWrap())
        }
    }

    override fun onStart() {
        super.onStart()
        val w = window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenW = context.resources.displayMetrics.widthPixels
        w.setLayout((screenW * 0.94f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        w.setGravity(Gravity.CENTER)
        w.setDimAmount(0.65f)
    }

    private fun cardBg(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(SHEET)
        d.cornerRadius = dp(18).toFloat()
        return d
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        style(TextView(context), sizeSp, color, bold)

    private fun weight(scale: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, scale
    )

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val SHEET = 0xF5151519.toInt()
        private const val SUB = 0x9AFFFFFF.toInt()
    }
}

private fun style(tv: TextView, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
    tv.setTextColor(color)
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    if (bold) tv.typeface = Typeface.DEFAULT_BOLD
    tv.isClickable = false
    tv.isFocusable = false
    return tv
}
