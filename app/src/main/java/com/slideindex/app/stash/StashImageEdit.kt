package com.slideindex.app.stash

import android.graphics.Bitmap
import com.slideindex.app.clipboard.ClipboardBlockKind

/** 至多一段文字 + 至多一张图的条目，才能在管理页里改写（见 [StashRepository.updateContent]）。 */
fun StashEntry.isSimpleContent(): Boolean {
    val blocks = resolvedContentBlocks()
    return blocks.count { it.kind == ClipboardBlockKind.TEXT } <= 1 &&
        blocks.count { it.kind == ClipboardBlockKind.IMAGE } <= 1
}

/** [StashRepository.updateContent] 对条目图片的处理方式。 */
sealed interface StashImageEdit {
    /** 保持原图不变。 */
    data object Keep : StashImageEdit

    /** 去掉图片。 */
    data object Remove : StashImageEdit

    /** 换成新图，旧图文件随之删除。 */
    class Replace(val bitmap: Bitmap) : StashImageEdit
}
