package com.slideindex.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.slideindex.app.download.DownloadProgressChannel
import com.slideindex.app.download.OcrModelDownloadChannel
import com.slideindex.app.nativeengine.NativeEnginePackCatalogProvider
import com.slideindex.app.nativeengine.NativeEnginePackCoordinator
import com.slideindex.app.nativeengine.NativeEnginePackIds
import com.slideindex.app.nativeengine.NativeEnginePackVersionState
import com.slideindex.app.ocr.OcrInferenceService
import com.slideindex.app.ocr.OcrModelCatalogProvider
import com.slideindex.app.ocr.OcrModelDownloadPhase
import com.slideindex.app.ocr.OcrModelDownloadState
import com.slideindex.app.ocr.OcrModelDownloader
import com.slideindex.app.ocr.OcrModelEntry
import com.slideindex.app.ocr.OcrModelRepository
import com.slideindex.app.service.OcrModelDownloadService
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.ui.feedback.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OcrModelSettingsViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    userMessageBus: UserMessageBus,
    @ApplicationContext private val context: Context,
    private val catalogProvider: OcrModelCatalogProvider,
    private val modelRepository: OcrModelRepository,
    private val downloader: OcrModelDownloader,
    private val inferenceService: OcrInferenceService,
    private val nativeEnginePackCoordinator: NativeEnginePackCoordinator,
    private val nativeEnginePackCatalogProvider: NativeEnginePackCatalogProvider,
    val vlmConfigManager: com.slideindex.app.ocr.vlm.VlmOcrConfigManager,
) : SettingsViewModel(settingsRepository, userMessageBus, context) {
    val catalogModels: List<OcrModelEntry> = catalogProvider.allModels()

    fun updateVlmConfig(
        apiKey: String,
        baseUrl: String,
        model: String,
        promptEnabled: Boolean,
        promptDraft: String,
    ) {
        vlmConfigManager.apiKey = apiKey
        vlmConfigManager.baseUrl = baseUrl
        vlmConfigManager.model = model
        vlmConfigManager.setProviderPromptConfig(
            vlmConfigManager.activeProvider,
            promptEnabled,
            promptDraft,
        )
    }

    fun updateProviderConfig(
        provider: com.slideindex.app.ocr.vlm.VlmProvider,
        apiKey: String,
        baseUrl: String,
        model: String,
        promptEnabled: Boolean,
        promptDraft: String,
    ) {
        vlmConfigManager.setApiKey(provider, apiKey)
        vlmConfigManager.setBaseUrl(provider, baseUrl)
        vlmConfigManager.setModel(provider, model)
        vlmConfigManager.setProviderPromptConfig(provider, promptEnabled, promptDraft)
        vlmConfigManager.setActiveProvider(provider)
    }

    fun selectProviderAndModel(provider: com.slideindex.app.ocr.vlm.VlmProvider, modelName: String) {
        vlmConfigManager.setActiveProvider(provider)
        vlmConfigManager.setModel(provider, modelName)
        selectModel("vlm-formula-qwen")
    }

    fun showWarning(message: String) {
        userMessageBus.showError(message)
    }

    val ocrEngineInstalled: Boolean
        get() = nativeEnginePackCoordinator.isPackInstalled(NativeEnginePackIds.OCR)

    val ocrEngineSizeBytes: Long
        get() = nativeEnginePackCatalogProvider.findPack(NativeEnginePackIds.OCR)?.sizeBytes ?: 0L

    val ocrEngineVersionState: NativeEnginePackVersionState?
        get() = nativeEnginePackCoordinator.packVersionState(NativeEnginePackIds.OCR)

    private val _installedModelIds = MutableStateFlow(modelRepository.installedModelIds())
    val installedModelIds: StateFlow<Set<String>> = _installedModelIds.asStateFlow()

    private val _downloadState = MutableStateFlow<OcrModelDownloadState?>(null)
    val downloadState: StateFlow<OcrModelDownloadState?> = _downloadState.asStateFlow()

    init {
        refreshInstalled()
        viewModelScope.launch {
            var previousPhase: OcrModelDownloadPhase? = null
            var firstEmission = true
            // 进度来自 :engine（下载服务所在进程）的跨进程通道；
            // OcrModelDownloadController 是进程内单例，在主进程永远是初始值。
            DownloadProgressChannel.observe(context, OcrModelDownloadChannel.ID).collect { bundle ->
                val decoded = bundle?.let(OcrModelDownloadChannel::decode)
                val state = if (decoded != null &&
                    isInProgress(decoded.phase) &&
                    !DownloadProgressChannel.isFresh(bundle)
                ) {
                    // 发布方进程被硬杀留下的过期"进行中"快照，清掉当作没有任务。
                    DownloadProgressChannel.clear(context, OcrModelDownloadChannel.ID)
                    null
                } else {
                    decoded
                }
                _downloadState.value = state
                val phase = state?.phase
                // 首次发射（快照）只当基线：历史快照不该在打开页面时改用户选中。
                if (!firstEmission) {
                    if (phase == OcrModelDownloadPhase.READY &&
                        previousPhase != OcrModelDownloadPhase.READY
                    ) {
                        refreshInstalled()
                        selectModel(state.modelId)
                    } else if (
                        (phase == OcrModelDownloadPhase.FAILED ||
                            phase == OcrModelDownloadPhase.CANCELLED) &&
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

    private fun isInProgress(phase: OcrModelDownloadPhase): Boolean =
        phase == OcrModelDownloadPhase.DOWNLOADING ||
            phase == OcrModelDownloadPhase.VERIFYING ||
            phase == OcrModelDownloadPhase.FINALIZING

    fun refreshInstalled() {
        _installedModelIds.value = modelRepository.installedModelIds()
    }

    fun selectModel(modelId: String) = launchSettingsWrite {
        settingsRepository.setFloatBallOcrModelId(modelId).also {
            inferenceService.invalidateIfModelChanged(modelId)
        }
    }

    fun clearSelectedModel() = launchSettingsWrite {
        settingsRepository.setFloatBallOcrModelId("").also {
            inferenceService.invalidateIfModelChanged(null)
        }
    }

    fun setDownloadWifiOnly(enabled: Boolean) = launchSettingsWrite {
        settingsRepository.setOcrDownloadWifiOnly(enabled)
    }

    fun downloadModel(modelId: String) {
        if (downloader.isDownloading(modelId)) return
        // 跨进程看：正在跑的是通道里那个任务，而不是本进程的 activeModelId。
        val active = _downloadState.value
        if (active != null && active.modelId != modelId && isInProgress(active.phase)) {
            _downloadState.value =
                OcrModelDownloadState(
                    modelId = modelId,
                    phase = OcrModelDownloadPhase.FAILED,
                    errorMessage = "another_download_in_progress",
                )
            return
        }
        val wifiOnly = settings.value.ocrDownloadWifiOnly
        OcrModelDownloadService.start(context, modelId, wifiOnly)
    }

    fun deleteOcrEngine() = viewModelScope.launch {
        nativeEnginePackCoordinator.deletePack(NativeEnginePackIds.OCR)
        inferenceService.invalidateIfModelChanged(settings.value.floatBallOcrModelId.ifBlank { null })
    }

    fun deleteModel(modelId: String) = viewModelScope.launch {
        downloader.deleteModel(modelId)
        refreshInstalled()
        if (settings.value.floatBallOcrModelId == modelId) {
            clearSelectedModel()
        }
        inferenceService.invalidateIfModelChanged(settings.value.floatBallOcrModelId.ifBlank { null })
    }
}
