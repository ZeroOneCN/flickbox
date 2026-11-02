package com.pd.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

private fun Context.px(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

private const val IDLE = 0
private const val PENDING = 1
private const val SCRUB = 2
private const val HANDED = 3

/**
 * 短视频式细进度线：平时是 3dp 的一条线，按住后加粗、滑块变大。
 * 横向拖动或轻点 = 定位；一旦判定是纵向滑动，就把整条手势原样交还给翻页层，
 * 进度线所在的那几条像素不会变成翻页死区。
 */
class ProgressLine(context: Context) : View(context) {

    interface Callback {
        fun onScrubStart()
        fun onScrubTo(ms: Int)
        fun onScrubEnd(ms: Int)
        /** asDown 为真时先补发一个 ACTION_DOWN，翻页层才能接住这次手势。 */
        fun onHandoff(ev: MotionEvent, asDown: Boolean)
    }

    var callback: Callback? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val edge = context.px(14f).toFloat()

    private var durationMs = 0
    private var frac = 0f
    private var mode = IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastMs = 0
    private var active = false

    fun setDuration(ms: Int) {
        durationMs = ms
        lastMs = 0
        frac = 0f
        invalidate()
    }

    /** 播放进度回填；用户正在拖动时不覆盖。 */
    fun setProgress(ms: Int) {
        if (mode == SCRUB || durationMs <= 0) return
        setFrac(ms.toFloat() / durationMs)
    }

    private fun setFrac(v: Float) {
        val n = v.coerceIn(0f, 1f)
        if (n != frac) {
            frac = n
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.px(26f))
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val thick = context.px(if (active) 5f else 3f).toFloat()
        val left = edge
        val right = width - edge
        val knob = context.px(if (active) 8f else 4.5f).toFloat()
        val px = left + (right - left) * frac

        paint.style = Paint.Style.FILL
        paint.color = TRACK
        rect.set(left, cy - thick / 2f, right, cy + thick / 2f)
        canvas.drawRoundRect(rect, thick / 2f, thick / 2f, paint)

        if (px > left) {
            paint.color = PLAYED
            rect.set(left, cy - thick / 2f, px, cy + thick / 2f)
            canvas.drawRoundRect(rect, thick / 2f, thick / 2f, paint)
        }
        paint.color = KNOB
        canvas.drawCircle(px, cy, knob, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                mode = PENDING
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == HANDED) {
                    callback?.onHandoff(event, false)
                } else if (mode == PENDING) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        mode = HANDED
                        callback?.onHandoff(event, true)
                    } else if (abs(dx) > slop) {
                        mode = SCRUB
                        active = true
                        invalidate()
                        callback?.onScrubStart()
                        scrubTo(event.x)
                    }
                } else if (mode == SCRUB) {
                    scrubTo(event.x)
                }
            }

            MotionEvent.ACTION_UP -> {
                when (mode) {
                    HANDED -> callback?.onHandoff(event, false)
                    SCRUB -> {
                        scrubTo(event.x)
                        callback?.onScrubEnd(lastMs)
                    }
                    PENDING -> {
                        // 轻点：直接定位到该处
                        callback?.onScrubStart()
                        callback?.onScrubEnd(msAt(event.x))
                    }
                }
                release()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (mode == HANDED) callback?.onHandoff(event, false)
                else if (mode == SCRUB) callback?.onScrubEnd(lastMs)
                release()
            }
        }
        return true
    }

    private fun release() {
        mode = IDLE
        if (active) {
            active = false
            invalidate()
        }
    }

    private fun scrubTo(x: Float) {
        lastMs = msAt(x)
        setFrac(if (durationMs > 0) lastMs.toFloat() / durationMs else 0f)
        callback?.onScrubTo(lastMs)
    }

    private fun msAt(x: Float): Int {
        if (durationMs <= 0) return 0
        val usable = width - edge * 2f
        if (usable <= 0f) return 0
        val ratio = ((x - edge) / usable).coerceIn(0f, 1f)
        return (ratio * durationMs).toInt().coerceIn(0, durationMs)
    }

    companion object {
        private const val TRACK = 0x59FFFFFF
        private const val PLAYED = 0xFFFFFFFF.toInt()
        private const val KNOB = 0xFFFFFFFF.toInt()
    }
}

/** 中央播放状态图标，取代原来的「已暂停」文字。 */
class StateBadge(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val triangle = Path()
    private var play = true

    init {
        visibility = GONE
    }

    /** 暂停状态下常驻显示播放三角。 */
    fun showPlay() {
        animate().cancel()
        play = true
        alpha = 1f
        visibility = VISIBLE
        invalidate()
    }

    /** 刚恢复播放：闪一下暂停条再淡出。 */
    fun flashPause() {
        animate().cancel()
        play = false
        alpha = 1f
        visibility = VISIBLE
        invalidate()
        animate().alpha(0f).setStartDelay(420L).setDuration(220L)
            .withEndAction { visibility = GONE }
            .start()
    }

    fun hide() {
        animate().cancel()
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = BADGE_BG
        canvas.drawCircle(cx, cy, context.px(27f).toFloat(), paint)
        paint.color = Color.WHITE
        if (play) {
            val w = context.px(19f).toFloat()
            val h = context.px(22f).toFloat()
            triangle.reset()
            triangle.moveTo(cx - w / 2f + context.px(3f), cy - h / 2f)
            triangle.lineTo(cx - w / 2f + context.px(3f), cy + h / 2f)
            triangle.lineTo(cx + w / 2f + context.px(3f), cy)
            triangle.close()
            canvas.drawPath(triangle, paint)
        } else {
            val bw = context.px(5f).toFloat()
            val bh = context.px(22f).toFloat()
            val gap = context.px(4f).toFloat()
            val r = bw / 2f
            rectOf(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f)
            canvas.drawRoundRect(tmpRect, r, r, paint)
            rectOf(cx, cy - bh / 2f, cx + bw, cy + bh / 2f)
            canvas.drawRoundRect(tmpRect, r, r, paint)
        }
    }

    private val tmpRect = RectF()

    private fun rectOf(l: Float, t: Float, r: Float, b: Float) {
        tmpRect.set(l, t, r, b)
    }

    companion object {
        private const val BADGE_BG = 0x73000000
    }
}

/** 横屏/放大按钮：描边圆角框 + 全屏四角图标 + 文字，刻意做得比其它控件显眼。 */
class RotateButton(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val stroke = context.px(1.5f).toFloat()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
    }
    private var fm = textPaint.fontMetrics

    /** true 表示当前已是横屏，按钮提示回到竖屏。 */
    var landscape = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    init {
        isClickable = true
    }

    private fun label(): String = if (landscape) "竖屏" else "横屏"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = context.px(11f) + context.px(14f) + context.px(6f) +
            textPaint.measureText(label()).toInt() + context.px(11f)
        setMeasuredDimension(w, context.px(31f))
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = CHIP_FILL
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = CHIP_STROKE
        box.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        canvas.drawRoundRect(box, radius, radius, paint)

        drawCorners(canvas, (context.px(11f) + context.px(7f)).toFloat(), height / 2f)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val cx = width - context.px(11f) - textPaint.measureText(label()) / 2f
        canvas.drawText(label(), cx, height / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawCorners(canvas: Canvas, cx: Float, cy: Float) {
        val half = context.px(7f).toFloat()
        val arm = context.px(4.5f).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.px(2f).toFloat()
        paint.color = Color.WHITE
        val l = cx - half
        val r = cx + half
        val t = cy - half
        val b = cy + half
        canvas.drawLine(l, t, l + arm, t, paint)
        canvas.drawLine(l, t, l, t + arm, paint)
        canvas.drawLine(r - arm, t, r, t, paint)
        canvas.drawLine(r, t, r, t + arm, paint)
        canvas.drawLine(r, b - arm, r, b, paint)
        canvas.drawLine(r - arm, b, r, b, paint)
        canvas.drawLine(l, b - arm, l, b, paint)
        canvas.drawLine(l, b, l + arm, b, paint)
    }

    companion object {
        private const val CHIP_FILL = 0x40FFFFFF
        private const val CHIP_STROKE = 0xF2FFFFFF.toInt()
    }
}
