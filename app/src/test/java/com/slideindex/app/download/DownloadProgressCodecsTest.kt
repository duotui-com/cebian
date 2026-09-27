package com.slideindex.app.download

import com.slideindex.app.nativeengine.NativeEnginePackDownloadPhase
import com.slideindex.app.nativeengine.NativeEnginePackDownloadState
import com.slideindex.app.ocr.OcrModelDownloadPhase
import com.slideindex.app.ocr.OcrModelDownloadState
import com.slideindex.app.ocr.OcrModelDownloadStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 跨进程通道的载荷编解码：所有字段都必须能原样过一遍。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DownloadProgressCodecsTest {

    @Test
    fun `ocr state survives encode and decode`() {
        val state = OcrModelDownloadState(
            modelId = "ppocrv6-medium",
            phase = OcrModelDownloadPhase.DOWNLOADING,
            bytesDownloaded = 12_345_678L,
            totalBytes = 62_032_837L,
            currentFileIndex = 2,
            totalFiles = 3,
            errorMessage = null,
            step = OcrModelDownloadStep.ENGINE,
            stepIndex = 1,
            stepCount = 2,
        )

        assertEquals(state, OcrModelDownloadChannel.decode(OcrModelDownloadChannel.encode(state)))
    }

    @Test
    fun `ocr state keeps null total bytes and error message`() {
        val state = OcrModelDownloadState(
            modelId = "mlkit-chinese",
            phase = OcrModelDownloadPhase.FAILED,
            errorMessage = "another_download_in_progress",
        )

        assertEquals(state, OcrModelDownloadChannel.decode(OcrModelDownloadChannel.encode(state)))
    }

    @Test
    fun `ocr decode rejects payload without model id`() {
        assertNull(OcrModelDownloadChannel.decode(android.os.Bundle()))
    }

    @Test
    fun `engine pack state survives encode and decode`() {
        val state = NativeEnginePackDownloadState(
            packId = "ocr-engine",
            phase = NativeEnginePackDownloadPhase.EXTRACTING,
            bytesDownloaded = 987_654L,
            totalBytes = 1_234_567L,
            errorMessage = null,
        )

        assertEquals(
            state,
            NativeEnginePackDownloadChannel.decode(NativeEnginePackDownloadChannel.encode(state)),
        )
    }

    @Test
    fun `engine pack decode rejects unknown phase`() {
        val bundle = android.os.Bundle().apply {
            putString("packId", "ocr-engine")
            putString("phase", "NOT_A_PHASE")
        }

        assertNull(NativeEnginePackDownloadChannel.decode(bundle))
    }
}
