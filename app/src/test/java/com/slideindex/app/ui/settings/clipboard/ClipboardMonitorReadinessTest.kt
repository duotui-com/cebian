package com.slideindex.app.ui.settings.clipboard

import com.slideindex.app.settings.ClipboardMonitoringMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这套模式能力齐不齐」与「副标题该怎么说」这两条纯逻辑。
 *
 * 回归点（真机现象）：跑的是 LSPosed 白名单、监听服务在跑、常驻通知也写着「运行中」，
 * 但暂存夹页 / 扩展 tab 的副标题写「监听未就绪」——因为它把 LSPosed 当成"要 Shizuku"
 * （Shizuku 那一刻没连上就判未就绪），而且完全不看真实运行状态。
 */
class ClipboardMonitorReadinessTest {
  private fun state(
    shizukuGranted: Boolean = false,
    rootAvailable: Boolean = false,
    overlayGranted: Boolean = false,
    monitorRunning: Boolean = false,
    activeMode: ClipboardMonitoringMode? = null,
  ) = ClipboardMonitoringUiState(
    shizukuGranted = shizukuGranted,
    rootAvailable = rootAvailable,
    overlayGranted = overlayGranted,
    monitorRunning = monitorRunning,
    activeMode = activeMode,
  )

  @Test
  fun lsposedDoesNotNeedShizukuOrOverlay() {
    assertTrue(
      isClipboardMonitoringBackendReady(
        ClipboardMonitoringMode.LSPOSED,
        state(shizukuGranted = false, overlayGranted = false),
      ),
    )
  }

  @Test
  fun shizukuModeStillNeedsShizukuAndOverlay() {
    assertFalse(isClipboardMonitoringBackendReady(ClipboardMonitoringMode.SHIZUKU_LOGS, state()))
    assertFalse(
      isClipboardMonitoringBackendReady(
        ClipboardMonitoringMode.SHIZUKU_LOGS,
        state(shizukuGranted = true, overlayGranted = false),
      ),
    )
    assertTrue(
      isClipboardMonitoringBackendReady(
        ClipboardMonitoringMode.SHIZUKU_LOGS,
        state(shizukuGranted = true, overlayGranted = true),
      ),
    )
  }

  @Test
  fun rootModeNeedsRootAndOverlay() {
    assertTrue(
      isClipboardMonitoringBackendReady(
        ClipboardMonitoringMode.ROOT_LOGS,
        state(rootAvailable = true, overlayGranted = true),
      ),
    )
    assertFalse(
      isClipboardMonitoringBackendReady(
        ClipboardMonitoringMode.ROOT_LOGS,
        state(rootAvailable = false, overlayGranted = true),
      ),
    )
  }

  @Test
  fun summaryFollowsTheModeThatIsActuallyRunning() {
    assertEquals(
      ClipboardMonitorSummaryKind.Lsposed,
      clipboardMonitorSummaryKind(
        monitoringEnabled = true,
        state = state(monitorRunning = true, activeMode = ClipboardMonitoringMode.LSPOSED),
      ),
    )
    assertEquals(
      ClipboardMonitorSummaryKind.Privileged,
      clipboardMonitorSummaryKind(
        monitoringEnabled = true,
        state = state(monitorRunning = true, activeMode = ClipboardMonitoringMode.SHIZUKU_LOGS),
      ),
    )
    assertEquals(
      ClipboardMonitorSummaryKind.Root,
      clipboardMonitorSummaryKind(
        monitoringEnabled = true,
        state = state(monitorRunning = true, activeMode = ClipboardMonitoringMode.ROOT_HIDDEN_API),
      ),
    )
    assertEquals(
      ClipboardMonitorSummaryKind.Standard,
      clipboardMonitorSummaryKind(
        monitoringEnabled = true,
        state = state(monitorRunning = true, activeMode = ClipboardMonitoringMode.STANDARD),
      ),
    )
  }

  @Test
  fun summarySaysNotReadyOnlyWhenNothingIsActuallyRunning() {
    assertEquals(
      ClipboardMonitorSummaryKind.NotReady,
      clipboardMonitorSummaryKind(monitoringEnabled = true, state = state()),
    )
    assertEquals(
      ClipboardMonitorSummaryKind.Off,
      clipboardMonitorSummaryKind(monitoringEnabled = false, state = state()),
    )
  }

  @Test
  fun runningLsposedStaysLsposedEvenWithoutShizuku() {
    // 这条就是真机上"通知说运行中、副标题说未就绪"的那一步。
    assertEquals(
      ClipboardMonitorSummaryKind.Lsposed,
      clipboardMonitorSummaryKind(
        monitoringEnabled = true,
        state = state(
          shizukuGranted = false,
          overlayGranted = true,
          monitorRunning = true,
          activeMode = ClipboardMonitoringMode.LSPOSED,
        ),
      ),
    )
  }
}
