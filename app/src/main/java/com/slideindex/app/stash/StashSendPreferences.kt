package com.slideindex.app.stash

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「一键发送」的偏好。存放在 `filesDir/stash/send_prefs.json`，随 `stash` 目录一起备份，
 * 不占用全局设置项。
 */
@Singleton
class StashSendPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    @Serializable
    private data class Stored(val collapsePanelAfterSend: Boolean = true)

    private val prefsFile = File(File(context.applicationContext.filesDir, "stash").apply { mkdirs() }, "send_prefs.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _collapsePanelAfterSend = MutableStateFlow(read().collapsePanelAfterSend)

    /** 点「发送」后是否自动收起收纳面板，默认开。 */
    val collapsePanelAfterSend: StateFlow<Boolean> = _collapsePanelAfterSend.asStateFlow()

    init {
        StashAccess.sendPreferences = this
    }

    fun setCollapsePanelAfterSend(enabled: Boolean) {
        _collapsePanelAfterSend.value = enabled
        runCatching { prefsFile.writeText(json.encodeToString(Stored(enabled))) }
    }

    private fun read(): Stored {
        if (!prefsFile.exists()) return Stored()
        return runCatching { json.decodeFromString<Stored>(prefsFile.readText()) }.getOrDefault(Stored())
    }
}
