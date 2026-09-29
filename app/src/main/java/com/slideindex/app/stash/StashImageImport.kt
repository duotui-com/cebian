package com.slideindex.app.stash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.roundToInt

/** 管理页选进来的图片：解码并限制长边，控制暂存夹里图片文件的体积。 */
object StashImageImport {
    /** 入库图片的长边上限。发到微信的图不需要更大，再大只是白占存储。 */
    const val MAX_SIDE_PX = 1600

    /** 解码 [uri]（顺带按 EXIF 摆正方向）；读不出来返回 null。 */
    fun decode(context: Context, uri: Uri, maxSidePx: Int = MAX_SIDE_PX): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 软件位图才能再 compress 存成 PNG。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val target = scaledSize(info.size.width, info.size.height, maxSidePx)
            if (target != null) decoder.setTargetSize(target.first, target.second)
        }
    }.getOrNull()

    /** 长边超过 [maxSidePx] 时按比例缩到刚好等于它，返回目标宽高；不需要缩放返回 null。 */
    fun scaledSize(width: Int, height: Int, maxSidePx: Int): Pair<Int, Int>? {
        val longest = maxOf(width, height)
        if (longest <= maxSidePx || maxSidePx <= 0) return null
        val scale = maxSidePx.toFloat() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }
}
