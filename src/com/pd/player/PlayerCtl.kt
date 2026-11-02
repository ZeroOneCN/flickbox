package com.pd.player

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.view.SurfaceHolder

/**
 * 单个 [MediaPlayer] 实例驱动整个竖向信息流：换页时 reset 复用，不并行多开。
 * 只在 Surface 就绪后开始播放，避免 holder 未就绪时崩溃。
 */
class PlayerCtl(
    private val ctx: Context,
    private val holder: SurfaceHolder
) {

    interface Callback {
        fun onVideoSize(w: Int, h: Int)
        fun onFirstFrame()
        fun onEnded()
        fun onFailed(item: VideoItem)
        fun onPlayStateChanged(playing: Boolean)
    }

    var listener: Callback? = null

    private var mp: MediaPlayer? = null
    private var surfaceReady = false
    private var pendingItem: VideoItem? = null
    private var pendingStart = 0

    var item: VideoItem? = null
        private set

    /** 首帧已渲染，进度才可信。 */
    var started = false
        private set

    private var prepared = false

    private var wantPlay = true
    private var boosted = false
    private var baseSpeed = 1.0f

    var duration = 0
        private set

    /** 变速在 API 23 以下不可用。 */
    val speedSupported: Boolean get() = Build.VERSION.SDK_INT >= 23

    fun surfaceAvailable() {
        surfaceReady = true
        val p = pendingItem
        if (p != null) {
            pendingItem = null
            start(p, pendingStart)
        } else {
            rebindSurface()
        }
    }

    /** holder 尺寸变了（旋转、分屏）也可能换了缓冲队列，重绑一次最稳。 */
    fun surfaceResized() {
        if (surfaceReady) rebindSurface()
    }

    fun surfaceLost() {
        surfaceReady = false
    }

    /**
     * 切到别的软件再回来时 SurfaceView 拿到的是全新的 surface，而 MediaPlayer 还抱着
     * 那个已经失效的旧 surface：声音照常有画面全黑。重新绑一次，暂停中还要 seek 一下
     * 逼解码器往新 surface 上补一帧，否则新 surface 一直是初始的黑色缓冲。
     */
    private fun rebindSurface() {
        val p = mp ?: return
        if (!prepared) return
        try {
            p.setDisplay(holder)
            if (!p.isPlaying) p.seekTo(p.currentPosition)
        } catch (e: Exception) {
        }
    }

    /** 打开一条视频，startMs 为上次保存的进度。 */
    fun open(next: VideoItem, startMs: Int) {
        if (!surfaceReady) {
            pendingItem = next
            pendingStart = startMs
            return
        }
        start(next, startMs)
    }

    private fun start(next: VideoItem, startMs: Int) {
        item = next
        started = false
        prepared = false
        wantPlay = true
        duration = 0
        pendingStart = startMs
        try {
            val p = ensurePlayer()
            p.reset()
            // reset() 会清掉之前的显示目标，必须重新绑定
            p.setDisplay(holder)
            p.setDataSource(ctx, next.uri)
            p.prepareAsync()
        } catch (e: Exception) {
            listener?.onFailed(next)
        }
    }

    /** 全应用只保留一个 MediaPlayer，换页时 reset 复用。 */
    private fun ensurePlayer(): MediaPlayer {
        mp?.let { return it }
        val p = MediaPlayer()
        p.setDisplay(holder)
        p.setScreenOnWhilePlaying(true)
        p.setOnCompletionListener { listener?.onEnded() }
        p.setOnErrorListener { _, _, _ ->
            item?.let { listener?.onFailed(it) }
            true
        }
        p.setOnVideoSizeChangedListener { _, w, h -> listener?.onVideoSize(w, h) }
        p.setOnInfoListener { _, what, _ ->
            if (what == MEDIA_INFO_VIDEO_RENDERING_START) {
                started = true
                listener?.onFirstFrame()
            }
            false
        }
        p.setOnPreparedListener { mq ->
            prepared = true
            duration = mq.duration.coerceAtLeast(0)
            val from = pendingStart.coerceIn(0, (duration - RESUME_GUARD_MS).coerceAtLeast(0))
            if (from > 0) mq.seekTo(from)
            pendingStart = 0
            if (wantPlay) {
                mq.start()
                applySpeed()
                listener?.onPlayStateChanged(true)
            } else {
                listener?.onPlayStateChanged(false)
            }
        }
        mp = p
        return p
    }

    fun toggle() {
        val p = mp ?: return
        if (!prepared) {
            // 还在准备中：记住用户意图，prepare 完成时再决定是否播放
            wantPlay = !wantPlay
            listener?.onPlayStateChanged(wantPlay)
            return
        }
        try {
            if (p.isPlaying) {
                p.pause()
                wantPlay = false
                listener?.onPlayStateChanged(false)
            } else {
                p.start()
                wantPlay = true
                applySpeed()
                listener?.onPlayStateChanged(true)
            }
        } catch (e: Exception) {
        }
    }

    fun pauseSilently() {
        val p = mp ?: return
        wantPlay = false
        try {
            if (prepared && p.isPlaying) p.pause()
        } catch (e: Exception) {
        }
        listener?.onPlayStateChanged(false)
    }

    fun resume() {
        val p = mp ?: return
        wantPlay = true
        if (!prepared) return
        try {
            p.start()
            applySpeed()
        } catch (e: Exception) {
        }
        listener?.onPlayStateChanged(true)
    }

    /** 单条循环：播完之后从头再播（完成状态下必须先 seek 再 start）。 */
    fun replay() {
        val p = mp ?: return
        if (!prepared) return
        wantPlay = true
        try {
            p.seekTo(0)
            if (!p.isPlaying) p.start()
            applySpeed()
        } catch (e: Exception) {
        }
        listener?.onPlayStateChanged(true)
    }

    /**
     * 定位到 ms，夹在 0 ~ 时长-300（最后那几百毫秒常常没有可解码帧，seek 进去会黑屏）。
     * @return 真正落点；还没准备好时返回 -1，调用方据此决定要不要刷新界面。
     */
    fun seekTo(ms: Int): Int {
        val p = mp ?: return -1
        if (!prepared) return -1
        val target = ms.coerceIn(0, (duration - 300).coerceAtLeast(0))
        try {
            p.seekTo(target)
        } catch (e: Exception) {
        }
        return target
    }

    val position: Int
        get() = try {
            mp?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }

    /** 用户设定的常速，0.5~3.0，超出范围会被夹住。 */
    fun setBaseSpeed(speed: Float) {
        baseSpeed = speed.coerceIn(0.5f, 3.0f)
        applySpeed()
    }

    val baseSpeedValue: Float get() = baseSpeed

    /** 长按临时加速，松开用 [endBoost] 还原。 */
    val boosting: Boolean get() = boosted

    fun beginBoost() {
        boosted = true
        applySpeed()
    }

    fun endBoost() {
        boosted = false
        applySpeed()
    }

    private fun targetSpeed(): Float {
        if (!speedSupported) return 1.0f
        return if (boosted) maxOf(baseSpeed, BOOST_SPEED) else baseSpeed
    }

    private fun applySpeed() {
        if (!speedSupported) return
        val p = mp ?: return
        try {
            val pp = p.playbackParams
            pp.speed = targetSpeed()
            p.playbackParams = pp
            if (wantPlay && !p.isPlaying) p.start()
        } catch (e: Exception) {
        }
    }

    /** 存档用的进度：没开始或接近结尾都记 0。 */
    fun savePoint(): Int {
        val d = duration
        if (!started || d <= 0) return 0
        val pos = position
        return if (pos in 1 until d - RESUME_GUARD_MS) pos else 0
    }

    fun release() {
        val p = mp
        mp = null
        item = null
        pendingItem = null
        try {
            p?.stop()
        } catch (e: Exception) {
        }
        p?.release()
    }

    companion object {
        private const val MEDIA_INFO_VIDEO_RENDERING_START = 3
        private const val BOOST_SPEED = 2.0f
        private const val RESUME_GUARD_MS = 2000
    }
}
