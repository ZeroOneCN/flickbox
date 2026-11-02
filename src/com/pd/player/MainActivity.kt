package com.pd.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.Calendar

/**
 * 单 Activity 应用：SurfaceView 负责显示，[PagerView] 负责上下滑，[Overlay] 负责控制。
 * 不依赖 AndroidX，不联网，只读本机相册视频。
 */
class MainActivity : Activity(), PagerView.Listener, PlayerCtl.Callback, Overlay.Listener,
    SurfaceHolder.Callback {

    private lateinit var content: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var pager: PagerView
    private lateinit var badge: StateBadge
    private lateinit var overlay: Overlay
    private lateinit var player: PlayerCtl
    private lateinit var sp: SharedPreferences

    private val ui = Handler(Looper.getMainLooper())
    private var items: List<VideoItem> = emptyList()
    private var videoW = 0
    private var videoH = 0
    private var wasPlaying = true
    private var inForeground = false
    private var playingBeforePause = false
    private var flashOnResume = false
    private var tickCount = 0
    private var loading = false
    private var loopMode = LOOP_ORDER
    private var sortMode = VideoRepo.SORT_TIME
    private var pickerKeyword = ""
    private var sheet: PickerSheet? = null
    private var info: InfoSheet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = getSharedPreferences("progress", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // 画面区域 = 整屏减去底部控制条，视频不会被控件挡住
        content = FrameLayout(this)
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        content.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        pager = PagerView(this)
        pager.listener = this
        content.addView(pager, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 旋转/分屏时 onConfigurationChanged 里 pager 还是旧尺寸，
        // 必须等真正排版完成后再按新屏幕重算画面大小。
        pager.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applySurfaceSize() }

        badge = StateBadge(this)
        content.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        overlay = Overlay(this)
        overlay.listener = this
        overlay.pager = pager
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(root)
        overlay.setLandscape(isLandscape())
        loopMode = sp.getInt(KEY_LOOP, LOOP_ORDER)
        sortMode = sp.getInt(KEY_SORT, VideoRepo.SORT_TIME)
        overlay.setLoopText(LOOP_NAMES[loopMode])
        overlay.setControlsHidden(wasPlaying)
        player = PlayerCtl(this, surface.holder)
        player.listener = this
        immersive()
        // rootWindowInsets 要等窗口真正挂上才有值，applyBottomGuard 内部会在排版后再校准一次
        applyBottomGuard()
        ensureAccessThenLoad()
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        immersive()
        applyBottomGuard()
        val shouldResume = playingBeforePause
        playingBeforePause = false
        if (items.isEmpty() && !loading) {
            ensureAccessThenLoad()
        } else if (shouldResume) {
            // 切走之前是在播的就继续播；被用户手动暂停过的不要自作主张
            player.resume()
        } else if (items.isNotEmpty()) {
            // 暂停着切走的，回来要重新给出暂停图标，否则只剩一张静止画面
            badge.showPlay()
        }
        syncControls()
    }

    override fun onPause() {
        inForeground = false
        saveProgress()
        playingBeforePause = wasPlaying
        player.pauseSilently()
        badge.hide()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tickTask)
        // Activity 先走、Dialog 还挂着会泄漏窗口（日志里那条 has leaked window）
        sheet?.dismiss()
        info?.dismiss()
        sheet = null
        info = null
        saveProgress()
        player.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转时 Activity 不重建：重新计算画面尺寸并重排封面
        overlay.setLandscape(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        syncControls()
        pager.relayout()
        applySurfaceSize()
        immersive()
        applyBottomGuard()
    }

    // ---------------- 权限与索引 ----------------

    private fun permissionName(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 23)
            checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED
        else true

    private fun ensureAccessThenLoad() {
        if (hasAccess()) {
            overlay.showHint(null)
            loadLibrary()
        } else if (Build.VERSION.SDK_INT >= 23) {
            overlay.showHint("需要读取本机视频的权限，点此授权")
            requestPermissions(arrayOf(permissionName()), REQ_PERM)
        } else {
            overlay.showHint("没有读取本机视频的权限，请在系统设置中开启，点此重试")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == REQ_PERM && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            overlay.showHint(null)
            loadLibrary()
        } else {
            overlay.showHint("权限被拒绝，点此再次授权")
        }
    }

    override fun onHintTap() {
        if (hasAccess()) loadLibrary() else ensureAccessThenLoad()
    }

    private fun loadLibrary() {
        if (loading) return
        loading = true
        overlay.showHint("正在扫描本机视频…")
        Thread {
            val all = VideoRepo.sorted(VideoRepo.scan(this), sortMode)
            ui.post {
                loading = false
                items = all
                if (all.isEmpty()) {
                    overlay.showHint("相册里没有可播放的视频（实况照片已过滤）")
                    pager.data = emptyList()
                } else {
                    overlay.showHint(null)
                    pager.data = all
                    pager.setPosition(restoreIndex(all), true)
                }
            }
        }.start()
    }

    /**
     * 上次看的是哪一条：优先按 id 找，列表变动导致 id 不在时退回记下序号。
     */
    private fun restoreIndex(all: List<VideoItem>): Int {
        val id = sp.getLong(KEY_ID, -1L)
        if (id > 0L) {
            val i = all.indexOfFirst { it.id == id }
            if (i >= 0) return i
        }
        return sp.getInt(KEY_INDEX, 0).coerceIn(0, all.size - 1)
    }

    // ---------------- PagerView.Listener ----------------

    override fun onPageChanged(to: Int) {
        val item = items.getOrNull(to) ?: return
        sp.edit().putInt(KEY_INDEX, to).putLong(KEY_ID, item.id).apply()
        player.open(item, sp.getLong(key(item), 0L).toInt())
        overlay.bindItem(item, to, items.size, item.durationMs, speedText())
    }

    override fun onTap() {
        if (items.isEmpty()) return
        flashOnResume = true
        player.toggle()
    }

    override fun onLongPressStart() {
        if (!player.speedSupported || items.isEmpty()) return
        player.beginBoost()
        overlay.setSpeedText(speedText())
    }

    override fun onLongPressEnd() {
        if (!player.speedSupported) return
        player.endBoost()
        overlay.setSpeedText(speedText())
    }

    override fun onDragStart() {
        // 拖动期间不需要额外处理：封面已重新盖上，视频在后面继续播
    }

    override fun onPagerIdle() {
        val current = items.getOrNull(pager.index) ?: return
        if (player.started && player.item === current) pager.markVideoPage(pager.index)
    }

    // ---------------- PlayerCtl.Callback ----------------

    override fun onVideoSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            videoW = w
            videoH = h
            ui.post { applySurfaceSize() }
        }
    }

    override fun onFirstFrame() {
        pager.markVideoPage(pager.index)
        applySurfaceSize()
    }

    override fun onEnded() {
        val last = pager.index >= items.size - 1
        when (loopMode) {
            LOOP_SINGLE -> player.replay()
            LOOP_LIST -> pager.setPosition(if (last) 0 else pager.index + 1, true)
            else -> if (!last) {
                pager.setPosition(pager.index + 1, true)
            } else {
                // 顺序播放放到最后一条才算停住，这时把功能区放出来
                wasPlaying = false
                syncControls()
                badge.showPlay()
            }
        }
    }

    override fun onFailed(item: VideoItem) {
        saveProgress()
        items = items.filter { it.id != item.id }
        pager.data = items
        ui.post {
            overlay.showHint(if (items.isEmpty()) "没有可播放的视频了" else null)
        }
        if (items.isNotEmpty()) pager.setPosition(pager.index.coerceIn(0, items.size - 1), true)
    }

    override fun onPlayStateChanged(playing: Boolean) {
        wasPlaying = playing
        syncControls()
        if (player.duration > 0) overlay.setDuration(player.duration)
        ui.removeCallbacks(tickTask)
        if (playing) {
            if (flashOnResume) badge.flashPause() else badge.hide()
            flashOnResume = false
            ui.postDelayed(tickTask, 500L)
        } else {
            flashOnResume = false
            badge.showPlay()
        }
    }

    private val tickTask = object : Runnable {
        override fun run() {
            if (player.started) overlay.setProgress(player.position)
            if (++tickCount % 4 == 0) saveProgress()
            ui.postDelayed(this, 500L)
        }
    }

    // ---------------- Overlay.Listener ----------------

    override fun onSeekStart() {
        // pauseSilently 会把 wasPlaying 置为 false，先记下拖动前的状态
        resumeAfterSeek = wasPlaying
        player.pauseSilently()
    }

    override fun onSeekMove(ms: Int) = Unit

    override fun onSeekEnd(ms: Int) {
        player.seekTo(ms)
        overlay.setProgress(ms)
        if (resumeAfterSeek) player.resume()
        resumeAfterSeek = false
    }

    private var resumeAfterSeek = false

    override fun onSpeedTap() {
        if (!player.speedSupported) {
            overlay.showHint("当前系统不支持倍速播放")
            return
        }
        speedIndex = (speedIndex + 1) % SPEEDS.size
        player.setBaseSpeed(SPEEDS[speedIndex])
        overlay.setSpeedText(speedText())
    }

    /** 点序号打开选片面板，直接跳到指定一条。 */
    override fun onPickTap() = showPicker()

    private fun showPicker() {
        if (items.isEmpty()) return
        // 面板还挂着时又点了一次序号：先关掉旧的，避免两层叠着、底下那层的点击穿透
        sheet?.dismiss()
        sheet = null
        val s = PickerSheet(
            this, items, pager.index, sortMode, pickerKeyword,
            onPick = { which -> pager.setPosition(which, true) },
            onSort = { mode, keyword ->
                // 排序 tab 就地换序：面板不收起来也不重新拉起，只重排列表和刷新高亮
                pickerKeyword = keyword
                applySort(mode)
                sheet?.updateSort(mode, items, pager.index)
            }
        )
        s.setOnDismissListener {
            if (sheet === s) sheet = null
            immersive()
        }
        sheet = s
        s.show()
    }

    /**
     * 换排序方式：重排列表后仍然停在原来那条视频上。
     * 播放中的 MediaPlayer 不用重开（还是同一条），只要把翻页层的名次和序号标签对齐。
     */
    private fun applySort(mode: Int) {
        if (mode == sortMode) return
        sortMode = mode
        sp.edit().putInt(KEY_SORT, mode).apply()
        val keep = items.getOrNull(pager.index)
        items = VideoRepo.sorted(items, mode)
        val at = if (keep == null) 0 else (items.indexOfFirst { it.id == keep.id }).coerceAtLeast(0)
        pager.reorder(items, at)
        overlay.setCounter(at, items.size)
        items.getOrNull(at)?.let {
            sp.edit().putInt(KEY_INDEX, at).putLong(KEY_ID, it.id).apply()
        }
    }

    /** 双击画面左右半屏快进/后退；正在播也允许，功能区收起时靠提示条反馈。 */
    override fun onDoubleTapSeek(forward: Boolean) {
        if (items.isEmpty() || player.duration <= 0) return
        val to = player.position + (if (forward) SEEK_STEP else -SEEK_STEP)
        val landed = player.seekTo(to)
        if (landed < 0) return
        overlay.setProgress(landed)
        overlay.showSeekHint(forward, landed, SEEK_STEP)
        saveProgress()
    }

    /**
     * 信息面板：先立刻显示媒体库里的字段，解码器实测的码率/编码/帧率晚到后再刷一遍。
     * 每次打开都重新探一遍，因为面板要反映当前进度。
     */
    override fun onInfoTap() {
        val item = items.getOrNull(pager.index) ?: return
        val s = InfoSheet(this, item.title)
        s.setOnDismissListener {
            if (info === s) info = null
            immersive()
        }
        info = s
        s.show()
        s.setRows(infoRows(item, null))
        VideoRepo.probe(this, item) { meta ->
            if (s.isShowing && info === s) s.setRows(infoRows(item, meta))
        }
    }

    private fun infoRows(item: VideoItem, meta: VideoRepo.Meta?): List<InfoRow> {
        val durMs = if (meta != null && meta.durationMs > 0L) meta.durationMs.toInt()
        else player.duration.coerceAtLeast(item.durationMs)
        val w = if (meta != null && meta.width > 0) meta.width else item.width
        val h = if (meta != null && meta.height > 0) meta.height else item.height
        val rot = meta?.rotation.orEmpty().let { if (it == "0" || it.isEmpty()) "" else " · 旋转 $it°" }
        val bytes = if (meta != null && meta.fileBytes > 0L) meta.fileBytes else item.sizeBytes
        val out = ArrayList<InfoRow>(16)
        out.add(InfoRow("分辨率", if (w > 0 && h > 0) "$w × $h$rot" else "未知"))
        out.add(InfoRow("时长", fmtClock(durMs)))
        out.add(InfoRow("文件大小", VideoRepo.fmtSize(bytes)))
        out.add(InfoRow("码率", fmtBitrate(meta?.bitrate ?: 0L, bytes, durMs)))
        out.add(InfoRow("视频编码", orUnknown(meta?.codec)))
        out.add(InfoRow("容器", orUnknown(meta?.container)))
        out.add(InfoRow("帧率", meta?.frameRate.orEmpty().let {
            if (it.isEmpty()) "未知（部分系统不报）" else "$it fps"
        }))
        out.add(InfoRow("目录", VideoRepo.folderOf(item)))
        out.add(InfoRow("文件路径", if (item.path.isEmpty()) "未公开（媒体库代理访问）" else item.path))
        out.add(InfoRow("媒体库 ID", item.id.toString()))
        out.add(InfoRow("URI", item.uri.toString()))
        out.add(InfoRow("加入时间", fmtDate(item.addedAt)))
        out.add(InfoRow("当前进度", "${fmtClock(player.position)}/${if (player.duration > 0) fmtClock(player.duration) else fmtClock(durMs)}"))
        out.add(InfoRow("播放速度", speedText()))
        out.add(InfoRow("循环方式", LOOP_NAMES[loopMode]))
        out.add(InfoRow("列表排序", VideoRepo.SORT_NAMES[sortMode] + " · 第 ${pager.index + 1}/${items.size} 条"))
        return out
    }

    /** 解码器没给码率时，用文件大小和时长折算一个平均码率。 */
    private fun fmtBitrate(bps: Long, bytes: Long, durMs: Int): String {
        val v = if (bps > 0L) bps
        else if (bytes > 0L && durMs > 1000) bytes * 8000L / durMs
        else 0L
        if (v <= 0L) return "未知"
        return if (v >= 1_000_000L) String.format("%.2f Mbps", v.toDouble() / 1_000_000)
        else String.format("%d kbps", v / 1000)
    }

    private fun orUnknown(v: String?): String = if (v.isNullOrEmpty()) "未知" else v

    private fun fmtClock(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun fmtDate(epochSec: Long): String {
        if (epochSec <= 0L) return "未知"
        val c = Calendar.getInstance()
        c.timeInMillis = epochSec * 1000L
        return String.format(
            "%04d-%02d-%02d %02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    override fun onRotateTap() {
        requestedOrientation = if (isLandscape()) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** 点循环 chip 切一档：顺序播放 → 单条循环 → 列表循环。 */
    override fun onLoopTap() {
        loopMode = (loopMode + 1) % LOOP_NAMES.size
        sp.edit().putInt(KEY_LOOP, loopMode).apply()
        overlay.setLoopText(LOOP_NAMES[loopMode])
    }

    /** 正在播放就收起功能区（横竖屏一致），点暂停后再放出来。 */
    private fun syncControls() {
        if (!inForeground) return
        overlay.setControlsHidden(wasPlaying)
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private var speedIndex = 0

    private fun speedText(): String {
        // 加速中换页时不能把「加速」写回常速，否则标签和实际速度不一致
        if (player.boosting) return fmtSpeed(boostSpeed) + " 加速"
        return fmtSpeed(SPEEDS[speedIndex]) + " 倍速"
    }

    private fun fmtSpeed(v: Float) = if (v % 1f == 0f) "${v.toInt()}.0x" else "${v}x"

    // ---------------- SurfaceHolder.Callback ----------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        player.surfaceAvailable()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        player.surfaceResized()
        applySurfaceSize()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        saveProgress()
        player.surfaceLost()
    }

    // ---------------- 画面适配 ----------------

    /**
     * 一律按原比例完整显示：不裁、不拉伸、不放大。
     * 竖屏视频在横屏里如果铺满，等于把画面裁掉一大块（字幕、水印都会看不全），
     * 所以只做等比缩放，长边贴边、短边留黑。基准是 [pager] 的宽高。
     */
    private fun applySurfaceSize() {
        // 以 pager（画面区域）为基准，不能用 surface 自身尺寸，否则缩放会自我参照并抖动
        val sw = pager.width
        val sh = pager.height
        if (sw <= 0 || sh <= 0) return
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        val targetW: Int
        val targetH: Int
        if (videoW <= 0 || videoH <= 0) {
            targetW = sw
            targetH = sh
        } else {
            val vw = videoW.toFloat()
            val vh = videoH.toFloat()
            val scale = minOf(sw / vw, sh / vh)
            targetW = (vw * scale + 0.5f).toInt()
            targetH = (vh * scale + 0.5f).toInt()
        }
        // 尺寸没变就不要重新赋值，否则会不停触发布局并刷爆 Surface 缓冲队列
        if (lp.width == targetW && lp.height == targetH && lp.gravity == Gravity.CENTER) return
        lp.width = targetW
        lp.height = targetH
        lp.gravity = Gravity.CENTER
        surface.layoutParams = lp
    }

    private fun saveProgress() {
        val item = items.getOrNull(pager.index) ?: return
        val pos = player.savePoint()
        sp.edit().putLong(key(item), pos.toLong()).apply()
    }

    private fun key(item: VideoItem) = "p_${item.id}"

    /**
     * 底部系统手势区的高度。控制条必须整体抬到它上面：进度线压在屏幕最下沿时，
     * 横向拖定位会被系统认成上滑回桌面/切应用的手势，两边一起响应。
     */
    private fun applyBottomGuard() {
        val guard = bottomGuardPx()
        overlay.setBottomGuard(guard)
        // 旋转后 inset 才会更新，排版完成后再校准一次
        window.decorView.post { overlay.setBottomGuard(bottomGuardPx()) }
    }

    private fun bottomGuardPx(): Int {
        var inset = -1
        if (Build.VERSION.SDK_INT >= 30) {
            val ins = window.decorView.rootWindowInsets
            if (ins != null) {
                inset = ins.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.systemGestures()
                ).bottom
            }
        }
        if (inset < 0) {
            // 读不到 inset 的老系统：竖屏按导航栏高度估，横屏本来就在两侧
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            inset = if (!isLandscape() && id > 0) resources.getDimensionPixelSize(id) else 0
        }
        // 横屏时手势带在左右两侧，底部只需一点点留白；竖屏要整条带子都让出来
        val floor = if (isLandscape()) dp(10) else dp(26)
        return maxOf(inset + dp(8), floor)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 全屏沉浸。Android 11 起旧的 systemUiVisibility 标志已不生效，
     * 必须走 WindowInsetsController 才能真正铺满状态栏区域。
     */
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 28) {
            // 不声明 cutout 模式的话，窗口会被刘海缺口压到 y=103 以下，顶部留一条黑
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            return
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    companion object {
        private const val REQ_PERM = 1
        private const val KEY_INDEX = "last_index"
        private const val KEY_ID = "last_id"
        private const val KEY_LOOP = "loop_mode"
        private const val KEY_SORT = "sort_mode"
        private const val LOOP_ORDER = 0
        private const val LOOP_SINGLE = 1
        private const val LOOP_LIST = 2
        private val LOOP_NAMES = arrayOf("顺序播放", "单条循环", "列表循环")
        private val SPEEDS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)

        /** 双击一次跳多长。 */
        private const val SEEK_STEP = 10_000

        /** 长按时的临时倍速。 */
        const val boostSpeed = 2.0f
    }
}
