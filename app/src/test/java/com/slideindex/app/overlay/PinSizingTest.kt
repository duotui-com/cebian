package com.slideindex.app.overlay

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PinSizingTest {
    @Test
    fun resolvePinPlacementRect_mapsLogicalRectToCaptureSpace() {
        val screenRect = Rect(259, 2186, 817, 2318)
        val layoutMeta = ScreenshotLayoutMeta(
            screenWidth = 1080,
            screenHeight = 2400,
            captureWidth = 1080,
            captureHeight = 2280,
        )
        val placement = resolvePinPlacementRect(screenRect, layoutMeta)!!
        assertEquals(
            FloatBallOcrRegions.mapScreenRectToBitmap(
                screenRect,
                1080,
                2400,
                1080,
                2280,
            ),
            placement,
        )
    }

    @Test
    fun resolvePinImageDisplaySizePx_usesMappedPlacementWhenMetaDiffers() {
        val screenRect = Rect(100, 200, 400, 500)
        val layoutMeta = ScreenshotLayoutMeta(
            screenWidth = 1080,
            screenHeight = 2400,
            captureWidth = 1080,
            captureHeight = 2280,
        )
        val (width, height) = resolvePinImageDisplaySizePx(
            bitmap = android.graphics.Bitmap.createBitmap(280, 280, android.graphics.Bitmap.Config.ARGB_8888),
            screenRect = screenRect,
            layoutMeta = layoutMeta,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
        )
        val placement = resolvePinPlacementRect(screenRect, layoutMeta)!!
        assertEquals(placement.width(), width)
        assertEquals(placement.height(), height)
    }

    @Test
    fun measureTextPinSizePx_keepsShortTextCompact() {
        val (width, height) = measureTextPinSizePx(
            text = "短文本",
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            density = 3f,
            scaledDensity = 3f,
        )

        // 旧实现固定 55% 屏宽 × 240dp，短文本应明显更小。
        assertTrue(width < (1080 * 0.55f).roundToInt())
        assertTrue(height < (240 * 3f).roundToInt())
    }

    @Test
    fun measureTextPinSizePx_clampsLongTextToMaxBox() {
        val (width, height) = measureTextPinSizePx(
            text = "很长的钉图文字内容，需要换行显示。".repeat(120),
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            density = 3f,
            scaledDensity = 3f,
        )

        assertTrue(width <= (1080 * 0.55f).roundToInt())
        assertTrue(height <= (2400 * 0.5f).roundToInt())
    }
}
