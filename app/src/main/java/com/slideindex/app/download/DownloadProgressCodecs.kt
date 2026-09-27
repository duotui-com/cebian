package com.slideindex.app.download

import android.os.Bundle
import com.slideindex.app.nativeengine.NativeEnginePackDownloadPhase
import com.slideindex.app.nativeengine.NativeEnginePackDownloadState
import com.slideindex.app.ocr.OcrModelDownloadPhase
import com.slideindex.app.ocr.OcrModelDownloadState
import com.slideindex.app.ocr.OcrModelDownloadStep

/**
 * 下载进度在跨进程通道上传输的两种载荷。
 *
 * 只放基础类型（String/Long/Int），[DownloadProgressChannel] 才能安全地 parcel 化，
 * 也不会像 PendingIntent 那样在 `marshall()` 时炸掉。
 */
object OcrModelDownloadChannel {
    const val ID = "ocr_model"

    private const val KEY_MODEL_ID = "modelId"
    private const val KEY_PHASE = "phase"
    private const val KEY_BYTES = "bytesDownloaded"
    private const val KEY_TOTAL = "totalBytes"
    private const val KEY_FILE_INDEX = "currentFileIndex"
    private const val KEY_TOTAL_FILES = "totalFiles"
    private const val KEY_ERROR = "errorMessage"
    private const val KEY_STEP = "step"
    private const val KEY_STEP_INDEX = "stepIndex"
    private const val KEY_STEP_COUNT = "stepCount"

    fun encode(state: OcrModelDownloadState): Bundle = Bundle().apply {
        putString(KEY_MODEL_ID, state.modelId)
        putString(KEY_PHASE, state.phase.name)
        putLong(KEY_BYTES, state.bytesDownloaded)
        // 不要用 putString(key, null) 占位：那会让 getLong 读到字符串并打出类型警告，
        // 也分不清"没有总大小"和"总大小是 0"。缺键即为 null。
        state.totalBytes?.let { putLong(KEY_TOTAL, it) }
        putInt(KEY_FILE_INDEX, state.currentFileIndex)
        putInt(KEY_TOTAL_FILES, state.totalFiles)
        putString(KEY_ERROR, state.errorMessage)
        putString(KEY_STEP, state.step.name)
        putInt(KEY_STEP_INDEX, state.stepIndex)
        putInt(KEY_STEP_COUNT, state.stepCount)
    }

    fun decode(bundle: Bundle): OcrModelDownloadState? {
        val modelId = bundle.getString(KEY_MODEL_ID) ?: return null
        val phase = bundle.getString(KEY_PHASE)
            ?.let { name -> OcrModelDownloadPhase.entries.firstOrNull { it.name == name } }
            ?: return null
        return OcrModelDownloadState(
            modelId = modelId,
            phase = phase,
            bytesDownloaded = bundle.getLong(KEY_BYTES),
            totalBytes = if (bundle.containsKey(KEY_TOTAL)) bundle.getLong(KEY_TOTAL) else null,
            currentFileIndex = bundle.getInt(KEY_FILE_INDEX),
            totalFiles = bundle.getInt(KEY_TOTAL_FILES),
            errorMessage = bundle.getString(KEY_ERROR),
            step = bundle.getString(KEY_STEP)
                ?.let { name -> OcrModelDownloadStep.entries.firstOrNull { it.name == name } }
                ?: OcrModelDownloadStep.MODEL,
            stepIndex = bundle.getInt(KEY_STEP_INDEX, 1).coerceAtLeast(1),
            stepCount = bundle.getInt(KEY_STEP_COUNT, 1).coerceAtLeast(1),
        )
    }
}

object NativeEnginePackDownloadChannel {
    const val ID = "engine_pack"

    private const val KEY_PACK_ID = "packId"
    private const val KEY_PHASE = "phase"
    private const val KEY_BYTES = "bytesDownloaded"
    private const val KEY_TOTAL = "totalBytes"
    private const val KEY_ERROR = "errorMessage"

    fun encode(state: NativeEnginePackDownloadState): Bundle = Bundle().apply {
        putString(KEY_PACK_ID, state.packId)
        putString(KEY_PHASE, state.phase.name)
        putLong(KEY_BYTES, state.bytesDownloaded)
        state.totalBytes?.let { putLong(KEY_TOTAL, it) }
        putString(KEY_ERROR, state.errorMessage)
    }

    fun decode(bundle: Bundle): NativeEnginePackDownloadState? {
        val packId = bundle.getString(KEY_PACK_ID) ?: return null
        val phase = bundle.getString(KEY_PHASE)
            ?.let { name -> NativeEnginePackDownloadPhase.entries.firstOrNull { it.name == name } }
            ?: return null
        return NativeEnginePackDownloadState(
            packId = packId,
            phase = phase,
            bytesDownloaded = bundle.getLong(KEY_BYTES),
            totalBytes = if (bundle.containsKey(KEY_TOTAL)) bundle.getLong(KEY_TOTAL) else null,
            errorMessage = bundle.getString(KEY_ERROR),
        )
    }
}
