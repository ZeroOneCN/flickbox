package com.pd.player

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.concurrent.Executors

/** 相册里的一条视频。除播放外，排序/搜索/信息面板还要用到大小、目录和加入时间。 */
class VideoItem(
    val id: Long,
    val title: String,
    val durationMs: Int,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val bucket: String,
    val addedAt: Long,
    val path: String
) {
    val uri: Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}

/**
 * 本地视频索引与缩略图缓存。全部走 MediaStore，不访问网络。
 */
object VideoRepo {

    /** 一次扫描得到的列表，按加入时间倒序（最新在前，符合短视频流的直觉）。 */
    fun scan(ctx: Context): ArrayList<VideoItem> {
        val out = ArrayList<VideoItem>(64)
        val c = openCursor(ctx) ?: return out
        try {
            c.use {
                val iId = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iDur = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iW = it.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val iH = it.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val iStd = it.getColumnIndex(COL_STD_LIVE)
                val iVendor = it.getColumnIndex(COL_VENDOR_LIVE)
                val iSize = it.getColumnIndex(MediaStore.Video.Media.SIZE)
                val iBucket = it.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val iAdded = it.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val iPath = it.getColumnIndex(MediaStore.Video.Media.DATA)
                while (it.moveToNext()) {
                    val dur = it.getInt(iDur)
                    if (dur < 1000) continue // 跳过 1 秒以下的碎片文件
                    if (isLivePhoto(it, iStd, iVendor)) continue
                    out.add(
                        VideoItem(
                            it.getLong(iId),
                            it.getString(iName) ?: "",
                            dur,
                            if (iW >= 0) it.getInt(iW) else 0,
                            if (iH >= 0) it.getInt(iH) else 0,
                            if (iSize >= 0) it.getLong(iSize) else 0L,
                            if (iBucket >= 0) it.getString(iBucket) ?: "" else "",
                            if (iAdded >= 0) it.getLong(iAdded) else 0L,
                            if (iPath >= 0) it.getString(iPath) ?: "" else ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // 无权限或 MediaStore 异常：返回已取到的部分，由界面上报空状态
        }
        return out
    }

    /**
     * 实况照片的动态部分在媒体库里也是一条 video 记录，但它不是视频。
     * AOSP 用 is_live_photo，厂商（vivo/OPPO 等）用私有的 live_photo 字符串列，两个都认。
     */
    private fun isLivePhoto(c: Cursor, iStd: Int, iVendor: Int): Boolean {
        if (iStd >= 0 && c.getInt(iStd) != 0) return true
        if (iVendor >= 0 && !c.isNull(iVendor)) return true
        return false
    }

    /**
     * 私有列在别的机型上可能不存在，查询会直接抛异常，所以按列的多少逐级降级重试。
     */
    private fun openCursor(ctx: Context): Cursor? {
        val base = listOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATA
        )
        val variants = listOf(
            base + listOf(COL_STD_LIVE, COL_VENDOR_LIVE),
            base + COL_VENDOR_LIVE,
            base + COL_STD_LIVE,
            base
        )
        for (v in variants) {
            try {
                val c = ctx.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    v.toTypedArray(), null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )
                if (c != null) return c
            } catch (e: Exception) {
                // 换下一种投影再试
            }
        }
        return null
    }

    // ---------------- 排序 / 展示 ----------------

    /** 选片面板的四种排列方式，和 [SORT_NAMES] 一一对应。 */
    const val SORT_TIME = 0
    const val SORT_NAME = 1
    const val SORT_SIZE = 2
    const val SORT_FOLDER = 3
    val SORT_NAMES = arrayOf("时间", "名称", "大小", "目录")

    /**
     * 按方式重排。只在内存里排，不再查一次媒体库：列表最多几百条，
     * 重新查询反而会把扫描时的实时性和已过滤的实况照片规则弄乱。
     */
    fun sorted(list: List<VideoItem>, mode: Int): List<VideoItem> = when (mode) {
        SORT_NAME -> list.sortedWith(
            compareBy<VideoItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
        SORT_SIZE -> list.sortedByDescending { it.sizeBytes }
        SORT_FOLDER -> list.sortedWith(
            compareBy<VideoItem, String>(String.CASE_INSENSITIVE_ORDER) { it.bucket }
                .thenByDescending { it.addedAt }
        )
        else -> list.sortedByDescending { it.addedAt }
    }

    fun fmtSize(bytes: Long): String = when {
        bytes <= 0L -> "未知"
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
        else -> String.format("%.0f KB", bytes.toDouble() / 1024)
    }

    /** 目录名：优先用媒体库给的 BUCKET_DISPLAY_NAME，缺了就从路径倒数第二段推。 */
    fun folderOf(item: VideoItem): String {
        if (item.bucket.isNotEmpty()) return item.bucket
        val p = item.path
        if (p.isNotEmpty()) {
            val end = p.lastIndexOf('/')
            if (end > 0) {
                val start = p.lastIndexOf('/', end - 1)
                return p.substring(start + 1, end)
            }
        }
        return "未知目录"
    }

    // ---------------- 元数据 ----------------

    /** 解码器视角的真实参数，信息面板用。取不到的字段留空字符串，由界面显示「未知」。 */
    class Meta(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val bitrate: Long = 0L,
        val codec: String = "",
        val frameRate: String = "",
        val rotation: String = "",
        val container: String = "",
        val fileBytes: Long = 0L
    )

    /**
     * 异步读取一条视频的真实解码参数，回调在主线程执行。
     * MediaExtractor 会真的打开文件，绝不能放在主线程；失败不抛异常，
     * 返回空 Meta，界面退回用媒体库字段显示。
     */
    fun probe(ctx: Context, item: VideoItem, onReady: (Meta) -> Unit) {
        io.execute {
            val m = try {
                readMeta(ctx, item)
            } catch (e: Exception) {
                Meta()
            }
            main.post { onReady(m) }
        }
    }

    private fun readMeta(ctx: Context, item: VideoItem): Meta {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, item.uri, null)
            var durUs = 0L
            var w = 0
            var h = 0
            var bitrate = 0L
            var codec = ""
            var frameRate = ""
            var rotation = 0
            for (i in 0 until ex.trackCount) {
                val f = try {
                    ex.getTrackFormat(i)
                } catch (e: Exception) {
                    continue
                }
                val mime = str(f, KEY_MIME)
                if (mime.startsWith("video/")) {
                    codec = mime.removePrefix("video/")
                    durUs = num(f, KEY_DURATION_US)
                    w = num(f, KEY_WIDTH).toInt()
                    h = num(f, KEY_HEIGHT).toInt()
                    bitrate = num(f, KEY_BITRATE)
                    frameRate = rateText(f)
                    rotation = num(f, KEY_ROTATION).toInt()
                } else if (durUs == 0L && mime.startsWith("audio/")) {
                    // 极少数文件视频轨没写时长，退回问音频轨
                    durUs = num(f, KEY_DURATION_US)
                }
            }
            return Meta(
                durUs / 1000L,
                if (rotation == 90 || rotation == 270) h else w,
                if (rotation == 90 || rotation == 270) w else h,
                bitrate,
                codec,
                frameRate,
                if (rotation == 0) "" else rotation.toString(),
                containerOf(item),
                fileBytes(item)
            )
        } finally {
            try {
                ex.release()
            } catch (e: Exception) {
                // release 在个别 ROM 上会抛，忽略
            }
        }
    }

    /**
     * 帧率这个键在不同编码器写入时可能是 int、float 或 long，取到哪个算哪个；
     * 都没有就不显示，不猜。
     */
    private fun rateText(f: MediaFormat): String {
        if (!f.containsKey(KEY_FRAME_RATE)) return ""
        val v = try {
            f.getFloat(KEY_FRAME_RATE).toDouble()
        } catch (e: Exception) {
            try {
                f.getInteger(KEY_FRAME_RATE).toDouble()
            } catch (e2: Exception) {
                try {
                    f.getLong(KEY_FRAME_RATE).toDouble()
                } catch (e3: Exception) {
                    return ""
                }
            }
        }
        if (v <= 0.0) return ""
        return if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.2f", v)
    }

    /** 容器格式媒体库不记，按文件后缀给；看不出来就标未知。 */
    private fun containerOf(item: VideoItem): String {
        val src = if (item.path.isNotEmpty()) item.path else item.title
        val dot = src.lastIndexOf('.')
        val ext = if (dot in 1 until src.length - 1) src.substring(dot + 1).lowercase() else ""
        return when (ext) {
            "mp4", "m4v" -> "mp4"
            "mov" -> "mov"
            "mkv" -> "matroska/mkv"
            "webm" -> "webm"
            "ts" -> "mpegts"
            "flv" -> "flv"
            "avi" -> "avi"
            "" -> "未知"
            else -> ext
        }
    }

    /** 媒体库里的 SIZE 可能是 0（部分系统未填），退回直接问文件系统。 */
    private fun fileBytes(item: VideoItem): Long {
        if (item.sizeBytes > 0L) return item.sizeBytes
        if (item.path.isEmpty()) return 0L
        val f = File(item.path)
        return if (f.exists()) f.length() else 0L
    }

    private fun str(f: MediaFormat, key: String): String = try {
        f.getString(key) ?: ""
    } catch (e: Exception) {
        ""
    }

    /** int / long 两种存法都兼容：MediaFormat 里同名键的类型随写入方而定。 */
    private fun num(f: MediaFormat, key: String): Long = try {
        f.getLong(key)
    } catch (e: Exception) {
        try {
            f.getInteger(key).toLong()
        } catch (e2: Exception) {
            0L
        }
    }

    private const val CACHE_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    /** 同步取缓存，命中直接返回，未命中返回 null（调用方再走 [thumbnail]）。 */
    fun cached(id: Long): Bitmap? = cache.get(id)

    /**
     * 生成/读取指定尺寸的封面。必须在非主线程调用：API 29 以上是真正解码视频首帧。
     */
    fun thumbnail(ctx: Context, item: VideoItem, w: Int, h: Int): Bitmap? {
        cache.get(item.id)?.let { return it }
        val scale = maxOf(w, h).let { if (it > THUMB_MAX_PX) THUMB_MAX_PX.toFloat() / it else 1f }
        val tw = (w * scale).toInt().coerceAtLeast(16)
        val th = (h * scale).toInt().coerceAtLeast(16)
        val bmp: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= 29) {
                // 分区存储下拿不到文件路径，交给 ContentResolver 生成缩略图
                ctx.contentResolver.loadThumbnail(item.uri, Size(tw, th), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    ctx.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (bmp != null && !bmp.isRecycled) cache.put(item.id, bmp)
        return bmp
    }

    /** 异步取封面，回调在主线程执行。 */
    fun load(ctx: Context, item: VideoItem, w: Int, h: Int, onReady: (Bitmap?) -> Unit) {
        val hit = cache.get(item.id)
        if (hit != null) {
            main.post { onReady(hit) }
            return
        }
        io.execute {
            val bmp = thumbnail(ctx, item, w, h)
            main.post { onReady(bmp) }
        }
    }

    private val smallCache = object : LruCache<Long, Bitmap>(SMALL_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * 选片列表用的小封面。和整屏封面分成两个缓存：尺寸不同却都按 id 存，
     * 混在一起滚一遍列表就会把正在播放的那张封面挤掉。
     */
    /** 缓存里已经有就直接给，省掉一次「先清空再等回主线程」的闪白。 */
    fun peekSmall(id: Long): Bitmap? = smallCache.get(id)?.takeUnless { it.isRecycled }

    fun loadSmall(ctx: Context, item: VideoItem, w: Int, h: Int, onReady: (Bitmap?) -> Unit) {
        smallCache.get(item.id)?.let {
            main.post { onReady(it) }
            return
        }
        io.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ctx.contentResolver.loadThumbnail(item.uri, Size(w, h), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        ctx.contentResolver, item.id, MediaStore.Video.Thumbnails.MICRO_KIND, null
                    )
                }
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bmp != null && !bmp.isRecycled) smallCache.put(item.id, bmp)
            main.post { onReady(bmp) }
        }
    }

    fun clearCache() {
        cache.evictAll()
        smallCache.evictAll()
    }

    private const val THUMB_MAX_PX = 540
    private const val SMALL_CACHE_BYTES = 6 * 1024 * 1024

    /** AOSP 的实况照片标记（部分系统未公开常量，这里按列名取）。 */
    private const val COL_STD_LIVE = "is_live_photo"
    private const val COL_VENDOR_LIVE = "live_photo"

    /** MediaExtractor 轨道格式里的键名（用字面量，避开不同 SDK 版本常量名的差异）。 */
    private const val KEY_MIME = "mime"
    private const val KEY_DURATION_US = "durationUs"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_FRAME_RATE = "frame-rate"
    private const val KEY_ROTATION = "rotation-degrees"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
}
