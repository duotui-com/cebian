package com.slideindex.app.service

import android.content.Intent
import com.slideindex.app.gesture.GestureAngle
import com.slideindex.app.gesture.GestureAngles
import com.slideindex.app.overlay.PanelSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 跨进程预览通道的参数编解码：设置页在独立进程，这些值必须能原样送达 :overlay。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class OverlayPreviewChannelTest {

    @Test
    fun `absent float extra stays null so unchanged params are distinguishable`() {
        val intent = Intent()

        assertNull(intent.floatExtraOrNull(OverlayService.EXTRA_PREVIEW_TOP_FRACTION))
    }

    @Test
    fun `explicit zero float extra is not treated as absent`() {
        val intent = Intent().putExtra(OverlayService.EXTRA_PREVIEW_TOP_FRACTION, 0f)

        assertEquals(0f, intent.floatExtraOrNull(OverlayService.EXTRA_PREVIEW_TOP_FRACTION))
    }

    @Test
    fun `preview side parses known sides and rejects unknown`() {
        assertEquals(
            PanelSide.LEFT,
            Intent().putExtra(OverlayService.EXTRA_PREVIEW_SIDE, "LEFT").parsePreviewSide(),
        )
        assertEquals(
            PanelSide.BOTTOM,
            Intent().putExtra(OverlayService.EXTRA_PREVIEW_SIDE, "bottom").parsePreviewSide(),
        )
        assertNull(Intent().putExtra(OverlayService.EXTRA_PREVIEW_SIDE, "MIDDLE").parsePreviewSide())
        assertNull(Intent().parsePreviewSide())
    }

    @Test
    fun `gesture angles survive the float array round trip`() {
        val angles = GestureAngles(
            left = GestureAngle(0.1f, 0.3f, 0.5f, 0.7f),
            right = GestureAngle(0.2f, 0.4f, 0.6f, 0.8f),
            bottom = GestureAngle(0f, 0.25f, 0.5f, 0.75f),
            top = GestureAngle(0.05f, 0.35f, 0.65f, 0.95f),
        )

        assertEquals(angles, angles.toFloatArray().toGestureAnglesOrNull())
    }

    @Test
    fun `illegal gesture angle payload degrades to clear preview`() {
        assertNull(FloatArray(15).toGestureAnglesOrNull())
        // p1 > p2 违反 GestureAngle 约束 → 视为“清预览”，不能把坏值喂给渲染层。
        val illegal = GestureAngles().toFloatArray().also { it[0] = 0.9f }
        assertNull(illegal.toGestureAnglesOrNull())
    }
}
