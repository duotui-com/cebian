package com.slideindex.app.stash

object StashAccess {
    @Volatile
    var repository: StashRepository? = null

    @Volatile
    var categoryRepository: StashCategoryRepository? = null

    @Volatile
    var sendPreferences: StashSendPreferences? = null
}
