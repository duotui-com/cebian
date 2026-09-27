package com.slideindex.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slideindex.app.download.DownloadProgressChannel
import com.slideindex.app.download.NativeEnginePackDownloadChannel
import com.slideindex.app.nativeengine.NativeEnginePackCatalogProvider
import com.slideindex.app.nativeengine.NativeEnginePackCoordinator
import com.slideindex.app.nativeengine.NativeEnginePackDownloadPhase
import com.slideindex.app.nativeengine.NativeEnginePackDownloadState
import com.slideindex.app.nativeengine.NativeEnginePackDownloader
import com.slideindex.app.nativeengine.NativeEnginePackEntry
import com.slideindex.app.service.NativeEnginePackDownloadService
import com.slideindex.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.slideindex.app.settings.AppSettings

@HiltViewModel
class NativeEnginePackSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val catalogProvider: NativeEnginePackCatalogProvider,
    private val coordinator: NativeEnginePackCoordinator,
    private val downloader: NativeEnginePackDownloader,
) : ViewModel() {
    val packs: List<NativeEnginePackEntry> = catalogProvider.allPacks()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _packRows = MutableStateFlow(loadPackRows())
    val packRows: StateFlow<List<NativeEnginePackRowState>> = _packRows.asStateFlow()

    private val _downloadState = MutableStateFlow<NativeEnginePackDownloadState?>(null)
    val downloadState: StateFlow<NativeEnginePackDownloadState?> = _downloadState.asStateFlow()

    init {
        viewModelScope.launch {
            var previousPhase: NativeEnginePackDownloadPhase? = null
            var firstEmission = true
            // 下载服务在 :engine，进度必须走跨进程通道；进程内单例在主进程恒为空。
            DownloadProgressChannel.observe(context, NativeEnginePackDownloadChannel.ID).collect { bundle ->
                val decoded = bundle?.let(NativeEnginePackDownloadChannel::decode)
                val state = if (decoded != null && !DownloadProgressChannel.isFresh(bundle)) {
                    DownloadProgressChannel.clear(context, NativeEnginePackDownloadChannel.ID)
                    null
                } else {
                    decoded
                }
                _downloadState.value = state
                val phase = state?.phase
                if (!firstEmission) {
                    if (phase == NativeEnginePackDownloadPhase.READY &&
                        previousPhase != NativeEnginePackDownloadPhase.READY
                    ) {
                        refreshInstalled()
                    } else if (
                        (phase == NativeEnginePackDownloadPhase.FAILED ||
                            phase == NativeEnginePackDownloadPhase.CANCELLED) &&
                        phase != previousPhase
                    ) {
                        refreshInstalled()
                    }
                }
                firstEmission = false
                previousPhase = phase
            }
        }
    }

    private fun isInProgress(phase: NativeEnginePackDownloadPhase): Boolean =
        phase == NativeEnginePackDownloadPhase.DOWNLOADING ||
            phase == NativeEnginePackDownloadPhase.VERIFYING ||
            phase == NativeEnginePackDownloadPhase.EXTRACTING

    fun refreshInstalled() {
        _packRows.value = loadPackRows()
    }

    fun downloadPack(packId: String) {
        if (downloader.isDownloading(packId)) return
        val active = _downloadState.value
        if (active != null && active.packId != packId && isInProgress(active.phase)) {
            _downloadState.value =
                NativeEnginePackDownloadState(
                    packId = packId,
                    phase = NativeEnginePackDownloadPhase.FAILED,
                    errorMessage = "another_download_in_progress",
                )
            return
        }
        viewModelScope.launch {
            val wifiOnly = settings.value.ocrDownloadWifiOnly
            NativeEnginePackDownloadService.start(context, packId, wifiOnly)
        }
    }

    fun deletePack(packId: String) = viewModelScope.launch {
        coordinator.deletePack(packId)
        refreshInstalled()
    }

    fun setDownloadWifiOnly(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setOcrDownloadWifiOnly(enabled)
    }

    private fun loadPackRows(): List<NativeEnginePackRowState> =
        packs.map { entry ->
            NativeEnginePackRowState(
                entry = entry,
                installed = coordinator.isPackInstalled(entry.id),
                installedRevision = coordinator.installedPackRevision(entry.id),
                installedDisplayVersion = coordinator.installedDisplayVersion(entry.id),
                updateAvailable = coordinator.isPackUpdateAvailable(entry.id),
            )
        }
}
