package com.slideindex.app.xposed.hook

import com.slideindex.app.xposed.bridge.ModuleHookSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 配置广播 → 剪贴板白名单。
 *
 * 回归点：[ClipboardWhitelistHook] 的 `HookConfigReader` 以前从不接广播（模块里唯一收广播的
 * 地方只把 JSON 转给了接管控制器），白名单只能从磁盘读；而 Flyme/Android 16 上 system_server
 * 读得到的只有几天前的遗留快照，名单就永远停在"只有自己"。这条测试锁住"广播能换掉名单"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ClipboardWhitelistBroadcastTest {
  private val whitelisted = listOf(
    "app.octoclip.v1",
    "com.fooview.android.fooview",
    "com.slideindex.app",
  )

  private val configJson = ModuleHookSnapshot(
    takeoverGroups = 4,
    updatedAtMs = 1_790_821_208_687L,
    clipboardWhitelist = whitelisted,
  ).toJson()

  @Test
  fun broadcastReplacesActiveWhitelist() {
    ClipboardWhitelistHook.onConfigBroadcast(configJson)

    assertEquals(whitelisted.toSet(), ClipboardWhitelistHook.activeWhitelist())
    assertEquals(whitelisted.toSet(), ClipboardWhitelistHook.whitelistFromBroadcast(configJson))
  }

  @Test
  fun unparsableBroadcastKeepsTheOldWhitelist() {
    ClipboardWhitelistHook.onConfigBroadcast(configJson)
    val before = ClipboardWhitelistHook.activeWhitelist()

    ClipboardWhitelistHook.onConfigBroadcast("{not json")
    assertNull(ClipboardWhitelistHook.whitelistFromBroadcast("{not json"))
    assertEquals(before, ClipboardWhitelistHook.activeWhitelist())
  }

  @Test
  fun emptyWhitelistIsAppliedAsIs() {
    ClipboardWhitelistHook.onConfigBroadcast(
      ModuleHookSnapshot(takeoverGroups = 0, clipboardWhitelist = emptyList()).toJson(),
    )
    assertEquals(emptySet<String>(), ClipboardWhitelistHook.activeWhitelist())
  }
}
