package com.slideindex.app.ui.settings.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模块回传的名单大小 vs 本地设置。
 *
 * 回归点：真机上模块只放行自己（`wl=1`）而本地设了 3 个，设置页以前照样显示"就绪"，
 * 用户只能去翻 LSPosed 日志才能发现「配置没下发到 system_server」。这条判定把那件事搬到了状态行上。
 */
class ClipboardLsposedModuleStatusTest {
  @Test
  fun staleWhenModuleHoldsFewerPackagesThanConfigured() {
    assertEquals(
      ClipboardLsposedModuleStatus.WhitelistState.Stale,
      ClipboardLsposedModuleStatus.whitelistStateOf(moduleSize = 1, localSize = 3),
    )
  }

  @Test
  fun staleWhenModuleHoldsMorePackagesThanConfigured() {
    assertEquals(
      ClipboardLsposedModuleStatus.WhitelistState.Stale,
      ClipboardLsposedModuleStatus.whitelistStateOf(moduleSize = 4, localSize = 3),
    )
  }

  @Test
  fun syncedWhenCountsMatch() {
    assertEquals(
      ClipboardLsposedModuleStatus.WhitelistState.Synced,
      ClipboardLsposedModuleStatus.whitelistStateOf(moduleSize = 3, localSize = 3),
    )
    assertEquals(
      ClipboardLsposedModuleStatus.WhitelistState.Synced,
      ClipboardLsposedModuleStatus.whitelistStateOf(moduleSize = 0, localSize = 0),
    )
  }

  @Test
  fun unknownWhenModuleDoesNotReportTheField() {
    // 旧模块不带 wl=：不误报，交给 code= / clip= 两条判定去管。
    assertEquals(
      ClipboardLsposedModuleStatus.WhitelistState.Unknown,
      ClipboardLsposedModuleStatus.whitelistStateOf(moduleSize = null, localSize = 3),
    )
  }
}
