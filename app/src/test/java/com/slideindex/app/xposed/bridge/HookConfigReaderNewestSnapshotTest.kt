package com.slideindex.app.xposed.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 磁盘快照选择：三份来源一起比 `updatedAtMs`，取最新的一份。
 *
 * 回归点：以前是"读到第一份就用"（遗留快照还只在内存为空时读一次）。实测 Flyme/Android 16 上
 * system_server 只读得到 `/data/system/slideindex` 那份几天前的旧快照，于是 LSPosed 剪贴板白名单
 * 永远停在旧名单上（只有自己），用户后加的包拿不到后台放行 —— 而接管的 reader 走广播所以一直正常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HookConfigReaderNewestSnapshotTest {
  private val staleLegacy = snapshot(
    updatedAtMs = 1_790_384_744_822L, // 2026-09-26 09:05（真机遗留快照）
    whitelist = listOf("com.slideindex.app"),
  )

  private val freshExternal = snapshot(
    updatedAtMs = 1_790_821_208_687L, // 2026-10-01 10:20
    whitelist = listOf("app.octoclip.v1", "com.fooview.android.fooview", "com.slideindex.app"),
  )

  @Test
  fun newestWins_evenWhenTheOlderOneIsReadFirst() {
    assertEquals(
      listOf("app.octoclip.v1", "com.fooview.android.fooview", "com.slideindex.app"),
      HookConfigReader.newestSnapshot(listOf(freshExternal, null, staleLegacy))?.clipboardWhitelist,
    )
    assertEquals(
      listOf("app.octoclip.v1", "com.fooview.android.fooview", "com.slideindex.app"),
      HookConfigReader.newestSnapshot(listOf(staleLegacy, null, freshExternal))?.clipboardWhitelist,
    )
  }

  @Test
  fun fallsBackToTheOnlyReadableSnapshot() {
    assertEquals(
      listOf("com.slideindex.app"),
      HookConfigReader.newestSnapshot(listOf(null, null, staleLegacy))?.clipboardWhitelist,
    )
  }

  @Test
  fun nullWhenNothingReadableOrAllGarbage() {
    assertNull(HookConfigReader.newestSnapshot(listOf(null, null, null)))
    assertNull(HookConfigReader.newestSnapshot(listOf("", "{not json", "   ")))
  }

  @Test
  fun garbageSourceDoesNotHideTheGoodOne() {
    assertEquals(
      listOf("app.octoclip.v1", "com.fooview.android.fooview", "com.slideindex.app"),
      HookConfigReader.newestSnapshot(listOf("{not json", null, freshExternal))?.clipboardWhitelist,
    )
  }

  private fun snapshot(updatedAtMs: Long, whitelist: List<String>): String =
    ModuleHookSnapshot(
      takeoverGroups = ModuleHookBridgeContract.GROUP_SIDES,
      updatedAtMs = updatedAtMs,
      clipboardWhitelist = whitelist,
    ).toJson()
}
