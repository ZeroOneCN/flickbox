package com.pd.player

import android.content.Context
import android.graphics.Color
import android.widget.ImageView

/**
 * 一页 = 一张封面。视频由全屏 SurfaceView 透出，封面按页滑动，
 * 这样滑动手势只搬运 3 张位图，翻页过程中不会有解码卡顿。
 */
class PageView(context: Context) : ImageView(context) {

    private var boundId = -1L
    private var requesting = false

    init {
        setBackgroundColor(Color.BLACK)
        // 封面和实际画面用同一种铺法（等比完整显示），否则首帧出现时会跳一下
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val pageId: Long get() = boundId

    fun bind(item: VideoItem?, w: Int, h: Int) {
        if (item == null) {
            boundId = -1L
            setImageDrawable(null)
            return
        }
        if (boundId == item.id && drawable != null) return
        boundId = item.id
        setImageDrawable(null)
        val hit = VideoRepo.cached(item.id)
        if (hit != null) {
            setImageBitmap(hit)
            return
        }
        if (w <= 0 || h <= 0 || requesting) return
        requesting = true
        val expect = item.id
        VideoRepo.load(context, item, w, h) { bmp ->
            requesting = false
            if (bmp != null && boundId == expect && !bmp.isRecycled) setImageBitmap(bmp)
        }
    }
}
