package com.slideindex.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置 UI 与浮层宿主之间的进程边界回归守卫。
 *
 * 设置界面跑在主进程，浮层宿主在 `:overlay`；下面这些预览入口以前是进程内静态直连，
 * 从主进程调用必然命中 `instance == null` 而静默失效（"拖滑条没有预览"就是这么坏的）。
 * 现在它们必须经由 [OverlayService] 的 Intent 通道，本测试用源码扫描把这条规矩钉住。
 */
class OverlayProcessBoundaryTest {

    @Test
    fun `settings ui must not call overlay process preview apis directly`() {
        val moduleRoot = findAppModuleRoot()
        val uiDir = File(moduleRoot, "src/main/java/com/slideindex/app/ui")
        assertTrue("找不到设置 UI 源码目录：$uiDir", uiDir.isDirectory)

        val offenders = mutableListOf<String>()
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                OVERLAY_PROCESS_ONLY_APIS.forEach { api ->
                    if (text.contains(api)) {
                        offenders += "${file.relativeTo(moduleRoot).invariantSeparatorsPath} -> $api"
                    }
                }
            }

        assertEquals(
            "设置 UI 不得直连 :overlay 的预览入口，必须走 OverlayService 通道：\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    private fun findAppModuleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile &&
                File(dir, "build.gradle.kts").isFile
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 app 模块根目录（当前工作目录：${File("").absolutePath}）")
    }

    private companion object {
        val OVERLAY_PROCESS_ONLY_APIS = listOf(
            "SlideIndexAccessibilityService.mergeTriggerHandleLayoutPreview",
            "SlideIndexAccessibilityService.clearTriggerHandleLayoutPreview",
            "SlideIndexAccessibilityService.clearOverlayLayoutPreview",
            "SlideIndexAccessibilityService.previewIndexHeightFraction",
            "SlideIndexAccessibilityService.clearIndexHeightPreview",
            "SlideIndexAccessibilityService.setCornerZonePreviewActive",
            "SlideIndexAccessibilityService.applyCornerZonePreviewDimensions",
            "SlideIndexAccessibilityService.setGestureAnglesPreview",
            "SlideIndexAccessibilityService.previewFloatBallAppearance",
            "SlideIndexAccessibilityService.endFloatBallAppearancePreview",
            "SlideIndexAccessibilityService.clearFloatBallAppearancePreviewRestore",
            "SlideIndexAccessibilityService.previewFloatBallPositionYFraction",
            "SlideIndexAccessibilityService.endFloatBallPositionYPreview",
            "SlideIndexAccessibilityService.clearFloatBallPositionYPreviewRestore",
            "SlideIndexAccessibilityService.setFloatBallStripZonePreview",
        )
    }
}
