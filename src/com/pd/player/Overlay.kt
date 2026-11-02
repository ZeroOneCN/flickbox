package com.pd.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 固定在底部的控制条：标题、序号（可点开选片）、时间、倍速、横屏按钮和细进度线。
 * 自身不消费空白区域的触摸，所以上下滑手势仍然落到下面的 [PagerView]。
 */
class Overlay(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onSeekStart()
        fun onSeekMove(ms: Int)
        fun onSeekEnd(ms: Int)
        fun onSpeedTap()
        fun onHintTap()
        fun onPickTap()
        fun onRotateTap()

        /** 循环方式：顺序播放 / 单条循环 / 列表循环，点一下切一档。 */
        fun onLoopTap()

        /** 打开当前这条视频的详细信息。 */
        fun onInfoTap()
    }

    var listener: Listener? = null

    /** 进度线上判定为纵向滑动的手势转交给它。 */
    var pager: View? = null

    private val tvTitle = TextView(context)
    private val tvInfo = TextView(context)
    private val tvLoop = TextView(context)
    private val tvCounter = TextView(context)
    private val tvTime = TextView(context)
    private val tvSpeed = TextView(context)
    private val tvHint = TextView(context)
    private val tvSeekLeft = TextView(context)
    private val tvSeekRight = TextView(context)
    private val line = ProgressLine(context)
    private val btnRotate = RotateButton(context)
    private val strip = LinearLayout(context)
    private val srcLoc = IntArray(2)
    private val dstLoc = IntArray(2)
    private val baseBottom = dp(6)
    private var guard = 0
    private var controlsHidden = false
    private var durationMs = 0
    private var seeking = false

    init {
        strip.orientation = LinearLayout.VERTICAL
        strip.setPadding(dp(14), 0, dp(14), baseBottom)

        // 标题行：标题占满剩余宽度，右边依次是信息和循环方式
        val titleRow = LinearLayout(context)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        style(tvTitle, 15f)
        tvTitle.maxLines = 1
        tvTitle.ellipsize = TextUtils.TruncateAt.END
        titleRow.addView(tvTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        style(tvInfo, 12f)
        tvInfo.text = "信息"
        tvInfo.background = chip()
        tvInfo.setPadding(dp(10), dp(3), dp(10), dp(3))
        tvInfo.maxLines = 1
        tvInfo.setOnClickListener { listener?.onInfoTap() }
        titleRow.addView(tvInfo, wrap(dp(6), dp(0), dp(0), dp(0)))
        style(tvLoop, 12f)
        tvLoop.text = "顺序播放"
        tvLoop.background = chip()
        tvLoop.setPadding(dp(10), dp(3), dp(10), dp(3))
        tvLoop.maxLines = 1
        tvLoop.setOnClickListener { listener?.onLoopTap() }
        titleRow.addView(tvLoop, wrap(dp(8), dp(0), dp(0), dp(0)))
        strip.addView(titleRow, matchWrap())

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val rowLp = matchWrap()
        rowLp.topMargin = dp(2)

        val left = LinearLayout(context)
        left.orientation = LinearLayout.HORIZONTAL
        left.gravity = Gravity.CENTER_VERTICAL
        style(tvCounter, 12f)
        tvCounter.text = ""
        tvCounter.background = chip()
        tvCounter.setPadding(dp(8), dp(3), dp(8), dp(3))
        tvCounter.setOnClickListener { listener?.onPickTap() }
        left.addView(tvCounter, wrap(dp(0), dp(0), dp(8), dp(0)))
        style(tvTime, 12f)
        left.addView(tvTime, wrap(dp(0), dp(0), dp(0), dp(0)))
        row.addView(left, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        style(tvSpeed, 12f)
        tvSpeed.setTypeface(null, Typeface.BOLD)
        tvSpeed.text = "1.0x"
        tvSpeed.background = chip()
        tvSpeed.setPadding(dp(10), dp(4), dp(10), dp(4))
        tvSpeed.setOnClickListener { listener?.onSpeedTap() }
        row.addView(tvSpeed, wrap(dp(0), dp(0), dp(0), dp(0)))
        btnRotate.setOnClickListener { listener?.onRotateTap() }
        row.addView(btnRotate, wrap(dp(10), dp(0), dp(0), dp(0)))
        strip.addView(row, rowLp)

        val lineLp = matchWrap()
        lineLp.topMargin = dp(2)
        strip.addView(line, lineLp)

        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        style(tvHint, 14f)
        tvHint.gravity = Gravity.CENTER
        tvHint.setPadding(dp(16), dp(10), dp(16), dp(10))
        tvHint.visibility = GONE
        tvHint.setOnClickListener { listener?.onHintTap() }
        addView(tvHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 双击快进/快退的提示条，贴在画面左右两侧，只在亮一下时出现
        seekPill(tvSeekLeft, Gravity.START or Gravity.CENTER_VERTICAL)
        seekPill(tvSeekRight, Gravity.END or Gravity.CENTER_VERTICAL)

        line.callback = object : ProgressLine.Callback {
            override fun onScrubStart() {
                if (!seeking) {
                    seeking = true
                    listener?.onSeekStart()
                }
            }

            override fun onScrubTo(ms: Int) {
                tvTime.text = fmt(ms) + "/" + fmt(durationMs)
                listener?.onSeekMove(ms)
            }

            override fun onScrubEnd(ms: Int) {
                seeking = false
                listener?.onSeekEnd(ms)
            }

            override fun onHandoff(ev: MotionEvent, asDown: Boolean) {
                passToPager(ev, asDown)
            }
        }
    }

    /** 进度线上判成纵向滑动的手势，换算坐标后交给翻页层。 */
    private fun passToPager(ev: MotionEvent, asDown: Boolean) {
        val target = pager ?: return
        line.getLocationInWindow(srcLoc)
        target.getLocationInWindow(dstLoc)
        val action = if (asDown) MotionEvent.ACTION_DOWN else ev.actionMasked
        val copy = MotionEvent.obtain(
            ev.downTime, ev.eventTime, action,
            ev.x + srcLoc[0] - dstLoc[0], ev.y + srcLoc[1] - dstLoc[1], ev.metaState
        )
        target.dispatchTouchEvent(copy)
        copy.recycle()
    }

    /** 换页时刷新静态信息。 */
    fun bindItem(item: VideoItem?, index: Int, total: Int, duration: Int, speedText: String) {
        tvTitle.text = item?.title ?: ""
        setCounter(index, total)
        durationMs = duration
        line.setDuration(duration)
        tvTime.text = "0:00/" + fmt(duration)
        tvSpeed.text = speedText
    }

    /** 只刷新序号：排序会改变同一条视频的名次，但进度不该被重置。 */
    fun setCounter(index: Int, total: Int) {
        tvCounter.text = if (total > 0) "${index + 1}/$total ▾" else ""
    }

    /** 播放进度回调；用户正在拖进度线时不覆盖它。 */
    fun setProgress(posMs: Int) {
        if (seeking) return
        line.setProgress(posMs)
        tvTime.text = fmt(posMs) + "/" + fmt(durationMs)
    }

    /** 用解码器给出的真实时长纠正列表里的时长。 */
    fun setDuration(ms: Int) {
        if (ms <= 0 || ms == durationMs) return
        durationMs = ms
        line.setDuration(ms)
    }

    fun setSpeedText(text: String) {
        tvSpeed.text = text
    }

    fun setLoopText(text: String) {
        tvLoop.text = text
    }

    /** 横屏按钮的提示文字随当前方向切换。 */
    fun setLandscape(landscape: Boolean) {
        btnRotate.landscape = landscape
    }

    /**
     * 底部系统手势区的高度，控制条整体抬到它上面，
     * 这样拖进度条不会被系统当成上滑切应用的手势。
     */
    fun setBottomGuard(px: Int) {
        if (guard == px) return
        guard = px
        strip.setPadding(dp(14), 0, dp(14), baseBottom + px)
    }

    /**
     * 横屏播放时整条功能区收起来，画面独占全屏；点暂停再淡入显示。
     * GONE 之后不再排版，靠 [Listener.onBarHeight] 之外没有副作用。
     */
    fun setControlsHidden(hidden: Boolean) {
        if (controlsHidden == hidden) return
        controlsHidden = hidden
        strip.animate().cancel()
        if (hidden) {
            strip.animate().alpha(0f).setDuration(140L).withEndAction {
                if (controlsHidden) strip.visibility = GONE
            }.start()
        } else {
            strip.visibility = VISIBLE
            strip.animate().alpha(1f).setDuration(140L).start()
        }
    }

    /**
     * 双击快进/后退后亮一下：箭头贴在哪侧就是往哪跳，顺带显示落到了第几秒。
     * 这两颗贴在画面上、不在 [strip] 里，所以功能区收起时（正在播放）也看得见。
     */
    fun showSeekHint(forward: Boolean, posMs: Int, stepMs: Int) {
        val other = if (forward) tvSeekLeft else tvSeekRight
        other.animate().cancel()
        other.visibility = GONE
        other.alpha = 0f
        val tv = if (forward) tvSeekRight else tvSeekLeft
        val jump = "${stepMs / 1000} 秒"
        tv.text = if (forward) "$jump  »   " + fmt(posMs) else "«  $jump   " + fmt(posMs)
        tv.animate().cancel()
        tv.alpha = 0f
        tv.visibility = VISIBLE
        tv.animate().alpha(1f).setDuration(90L).withEndAction {
            tv.animate().alpha(0f).setStartDelay(480L).setDuration(200L)
                .withEndAction { tv.visibility = GONE }.start()
        }.start()
    }

    /** 无权限 / 无视频 / 出错时的中央提示；传 null 关闭。 */
    fun showHint(text: String?) {
        if (text == null) {
            tvHint.visibility = GONE
        } else {
            tvHint.text = text
            tvHint.visibility = VISIBLE
        }
    }

    private fun fmt(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun matchWrap() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun wrap(l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        p.setMargins(l, t, r, b)
        return p
    }

    private fun chip(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(CHIP)
        d.cornerRadius = dp(12).toFloat()
        d.setStroke(dp(1), CHIP_LINE)
        return d
    }

    /** 一颗贴在画面侧边的提示胶囊：不参与排版，只在双击快进时短暂可见。 */
    private fun seekPill(tv: TextView, gravity: Int) {
        style(tv, 13f)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(12), dp(7), dp(12), dp(7))
        tv.maxLines = 1
        tv.background = pill()
        tv.visibility = GONE
        tv.alpha = 0f
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity)
        lp.setMargins(dp(18), 0, dp(18), 0)
        addView(tv, lp)
    }

    private fun pill(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(PILL)
        d.cornerRadius = dp(20).toFloat()
        return d
    }

    private fun style(tv: TextView, sizeSp: Float) {
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tv.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        tv.isClickable = false
        tv.isFocusable = false
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val CHIP = 0x33000000
        private const val CHIP_LINE = 0x66FFFFFF.toInt()
        private const val PILL = 0xB3000000.toInt()
    }
}
