package com.pd.player

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 短视频式竖向翻页容器。零依赖：自己处理拖动、惯性、吸附和 3 个页槽复用。
 *
 * 只搬封面（3 张位图），视频画面由背后的全屏 SurfaceView 透出，
 * 所以滑动过程与解码无关，低配机也不会掉帧。
 */
class PagerView(context: Context) : ViewGroup(context) {

    interface Listener {
        fun onPageChanged(to: Int)
        fun onTap()

        /** 双击画面：左半屏后退、右半屏前进。 */
        fun onDoubleTapSeek(forward: Boolean)
        fun onLongPressStart()
        fun onLongPressEnd()

        /** 手势进行中：封面必须重新盖住画面，否则滑动时会露出静止的视频。 */
        fun onDragStart()

        /** 吸附动画结束（翻页或回弹），Activity 可决定是否重新隐藏封面。 */
        fun onPagerIdle()
    }

    var listener: Listener? = null

    var data: List<VideoItem> = emptyList()
        set(value) {
            field = value
            index = index.coerceIn(0, (value.size - 1).coerceAtLeast(0))
            rebind()
            layoutSlots()
        }

    var index = 0
        private set

    private val slots = ArrayList<PageView>(3)
    private val slotPage = IntArray(3) { -1 }
    private var videoPage = -1
    private var snapIn = false
    private var drag = 0f
    private var animator: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressFired = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout: Long = if (Build.VERSION.SDK_INT >= 29) {
        ViewConfiguration.getLongPressTimeout().toLong()
    } else {
        400L
    }

    private val longPressTask = Runnable {
        if (!dragging) {
            longPressFired = true
            // 之前那一下单击还没落地就被按住了，说明用户想长按加速而不是暂停
            removeCallbacks(singleTapTask)
            listener?.onLongPressStart()
        }
    }

    /** 单击要等双击窗口过去再执行，否则双击快进会顺手把播放暂停掉。 */
    private val singleTapTask = Runnable { listener?.onTap() }

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val tapSlop = 72f * resources.displayMetrics.density

    init {
        for (i in 0..2) {
            val v = PageView(context)
            slots.add(v)
            addView(v)
        }
    }

    private fun pageH(): Int = if (height > 0) height else 1

    /** 当前页对应的槽位。连续 3 个页号 mod 3 恰好互不冲突，天然形成环形复用。 */
    private fun slotOf(page: Int): Int = page.mod(SLOT_COUNT)

    fun setPosition(to: Int, notify: Boolean) {
        val target = to.coerceIn(0, (data.size - 1).coerceAtLeast(0))
        animator?.cancel()
        drag = 0f
        index = target
        rebind()
        layoutSlots()
        if (notify) listener?.onPageChanged(target)
    }

    /**
     * 换序专用：列表和名次都变了，但屏幕上还是同一条在播。
     * 走 setPosition 的话当前页会被封面先盖回去再淡出 120ms，看着就是画面闪一下；
     * 这里让当前页直接按「视频已经透着」处理，不留这一帧，左右邻居照常重贴。
     */
    fun reorder(newData: List<VideoItem>, at: Int) {
        val target = at.coerceIn(0, (newData.size - 1).coerceAtLeast(0))
        // 面板开着时自动播下一条也可能正在吸附，先停下再改名次
        animator?.cancel()
        drag = 0f
        data = newData
        index = target
        videoPage = target
        snapIn = true
        rebind()
        layoutSlots()
    }

    /** 视频首帧出来后隐藏该页封面；离开该页时封面自动恢复。 */
    fun markVideoPage(page: Int) {
        videoPage = page
        applyPosters()
    }

    private fun applyPosters() {
        for (i in 0 until SLOT_COUNT) {
            val v = slots[i]
            val showVideo = slotPage[i] == videoPage
            if (showVideo) {
                if (snapIn) {
                    // 换序时当前页直接透明：0 时长的动画也要下一帧才生效，会露一帧封面
                    v.animate().cancel()
                    v.alpha = 0f
                } else if (v.alpha != 0f) {
                    v.animate().alpha(0f).setDuration(120L).start()
                }
            } else {
                v.animate().alpha(1f).setDuration(0L).start()
                v.visibility = VISIBLE
            }
        }
        snapIn = false
    }

    private fun rebind() {
        val w = width
        val h = height
        for (p in index - 1..index + 1) {
            val item = if (p in data.indices) data[p] else null
            val s = slotOf(p)
            if (slotPage[s] != p) {
                slotPage[s] = p
                slots[s].bind(item, w, h)
            } else if (item == null) {
                slots[s].bind(null, w, h)
            }
        }
        applyPosters()
    }

    /** 尺寸变化（旋转）后重新排版并重取封面。 */
    fun relayout() {
        rebind()
        layoutSlots()
    }

    private fun layoutSlots() {
        val h = pageH()
        val w = width
        for (i in 0 until SLOT_COUNT) {
            val top = ((slotPage[i] - index) * h + drag).toInt()
            slots[i].layout(0, top, w, top + h)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val exact = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        val exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        for (i in 0 until SLOT_COUNT) slots[i].measure(exactW, exact)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (changed) rebind()
        layoutSlots()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (data.isEmpty()) return false
        if (tracker == null) {
            tracker = VelocityTracker.obtain()
        }
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (animator?.isRunning == true) animator?.cancel()
                animator = null
                downY = event.y
                lastY = event.y
                dragging = false
                longPressFired = false
                postDelayed(longPressTask, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                lastY = event.y
                if (!dragging && !longPressFired && abs(event.y - downY) > slop) {
                    dragging = true
                    removeCallbacks(longPressTask)
                    removeCallbacks(singleTapTask)
                    // 一开始拖动就恢复封面，让前后两页都作为图片一起滑动
                    videoPage = -1
                    applyPosters()
                    listener?.onDragStart()
                }
                if (dragging) {
                    drag = clampWithResistance(drag + dy)
                    layoutSlots()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressTask)
                val t = tracker
                t?.computeCurrentVelocity(1000)
                val vy = t?.yVelocity ?: 0f
                t?.recycle()
                tracker = null
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                val wasLongPress = longPressFired
                longPressFired = false
                if (wasLongPress) {
                    // 长按已消费这次手势，松手不能再当成单击，否则加速结束的同时会被暂停
                    listener?.onLongPressEnd()
                } else if (dragging) {
                    dragging = false
                    if (cancelled) snapBack() else settle(vy)
                } else if (!cancelled) {
                    onCleanTap(event)
                }
            }
        }
        return true
    }

    /**
     * 一次干净的点击（没拖动、没长按）：落在双击窗口和距离范围内就算快进/快退，
     * 否则把单击推迟 [DOUBLE_TAP_MS] 再执行，留出判断第二下的时间。
     */
    private fun onCleanTap(event: MotionEvent) {
        // 不能用 eventTime：它是相对本次 DOWN 的偏移，每段手势都从 0 开始，跨两次点击比不出间隔
        val now = SystemClock.uptimeMillis()
        val paired = now - lastTapTime <= DOUBLE_TAP_MS &&
            abs(event.x - lastTapX) <= tapSlop && abs(event.y - lastTapY) <= tapSlop
        if (paired) {
            removeCallbacks(singleTapTask)
            lastTapTime = 0L
            listener?.onDoubleTapSeek(event.x >= width / 2f)
        } else {
            lastTapTime = now
            lastTapX = event.x
            lastTapY = event.y
            removeCallbacks(singleTapTask)
            postDelayed(singleTapTask, DOUBLE_TAP_MS)
        }
    }

    /** 越界时给 1/4 阻尼，最多再拖 1/3 屏，形成回弹手感。 */
    private fun clampWithResistance(value: Float): Float {
        val h = pageH().toFloat()
        val upper = if (index > 0) h else 0f
        val lower = if (index < data.size - 1) -h else 0f
        var v = value
        if (v > upper) v = upper + (v - upper).coerceAtMost(h * OVERSHOOT) * 0.25f
        if (v < lower) v = lower + (v - lower).coerceAtLeast(-h * OVERSHOOT) * 0.25f
        return v
    }

    private fun settle(vy: Float) {
        val h = pageH().toFloat()
        val projected = drag + vy * (INERTIA_MS / 1000f)
        val target = (index - (projected / h).roundToInt())
            .coerceIn(0, (data.size - 1).coerceAtLeast(0))
        if (target == index) {
            snapBack()
            return
        }
        animateDragTo((index - target) * h) {
            index = target
            drag = 0f
            rebind()
            layoutSlots()
            listener?.onPageChanged(target)
            listener?.onPagerIdle()
        }
    }

    private fun snapBack() {
        animateDragTo(0f) { listener?.onPagerIdle() }
    }

    private fun animateDragTo(to: Float, onEnd: (() -> Unit)?): ValueAnimator {
        val a = ValueAnimator.ofFloat(drag, to)
        a.duration = SNAP_MS
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener {
            drag = it.animatedValue as Float
            layoutSlots()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onEnd?.invoke()
            }
        })
        animator = a
        a.start()
        return a
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressTask)
        removeCallbacks(singleTapTask)
        animator?.cancel()
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val SLOT_COUNT = 3
        private const val SNAP_MS = 240L
        private const val INERTIA_MS = 80f
        private const val OVERSHOOT = 0.34f

        /** 两次点击隔在这之内算双击；单击也要等这么久才生效。 */
        private const val DOUBLE_TAP_MS = 300L
    }
}
