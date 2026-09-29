package com.slideindex.app.stash

import kotlinx.serialization.Serializable

/** 暂存夹分类。带分类的 [StashEntry] 即“话术”。 */
@Serializable
data class StashCategory(
    val id: String,
    val name: String,
    /** 分类之间的顺序，越小越靠前。 */
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long
)
