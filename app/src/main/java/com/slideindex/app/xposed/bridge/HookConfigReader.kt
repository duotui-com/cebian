package com.slideindex.app.xposed.bridge

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.File

/**
 * 模块侧（system_server）配置读取器。
 *
 * 读取顺序：app 导出的外部快照 → 本进程目录 → app 设备保护目录 → 保持“无配置”（即完全放行）。
 * 带 TTL 缓存，避免每条输入事件都读盘；读不到时反向请求 app 重新下发。
 *
 * 持久化由 app 负责（写外部私有目录不需要权限）；模块侧不再写盘——
 * 电话进程往 /data/system 写会恒 EACCES。
 */
class HookConfigReader(
  private val log: (String) -> Unit = {},
) {
  @Volatile
  private var cached: ModuleHookSnapshot? = null

  @Volatile
  private var lastLoadAtMs: Long = 0L

  @Volatile
  private var lastRequestAtMs: Long = 0L

  @Volatile
  private var requestedOnce: Boolean = false

  fun current(): ModuleHookSnapshot? {
    val now = SystemClock.elapsedRealtime()
    val cachedValue = cached
    if (cachedValue != null && now - lastLoadAtMs < ModuleHookBridgeContract.SNAPSHOT_TTL_MS) {
      return cachedValue
    }
    // 盘上那份**只有比内存里更新时才允许替换**。
    //
    // 为什么必须比：历史遗留的 [/data/system/slideindex] 快照 app 早已写不动，
    // 内容会永久停在某个旧状态（例如"两侧接管关闭"时的 groups=4）。而它是本进程唯一
    // 读得到的快照（外部私有目录 / 设备保护目录 system_server 都读不到）。原来无条件
    // `cached = parsed`，于是每次 TTL（2s）到期都会用这份旧快照把广播刚下发的新配置顶掉：
    // 现象就是「开关关→开之后只有两三秒生效，之后又变回系统手势」。
    val fromDisk = loadFromDisk()
    return preferNewer(fromDisk, cachedValue)
  }

  /** 取 `updatedAtMs` 更新的一份并记为缓存；相同时间戳时以调用方传入的第一份（盘上那份）为准。 */
  private fun preferNewer(
    disk: ModuleHookSnapshot?,
    memory: ModuleHookSnapshot?,
  ): ModuleHookSnapshot? {
    val best = when {
      disk == null -> memory
      memory == null -> disk
      else -> if (disk.updatedAtMs >= memory.updatedAtMs) disk else memory
    }
    if (best != null && best !== memory) {
      cached = best
      log("hook config kept newest: groups=${best.takeoverGroups} updatedAt=${best.updatedAtMs}")
    }
    return best
  }

  fun applyBroadcast(json: String?) {
    val parsed = ModuleHookSnapshot.parse(json) ?: return
    cached = parsed
    lastLoadAtMs = SystemClock.elapsedRealtime()
    log("hook config applied: groups=${parsed.takeoverGroups}")
  }

  /**
   * 请求 app 重新下发配置（带节流）。
   *
   * 与 EdgeX 的差异：不因"磁盘已有旧配置"而跳过——模块在 app 之前启动时会错过
   * 启动广播，必须允许主动拉取，否则会一直停留在旧配置上。
   */
  fun requestSnapshotIfNeeded(context: Context) {
    if (requestedOnce && SystemClock.elapsedRealtime() - lastRequestAtMs <
      ModuleHookBridgeContract.SNAPSHOT_REQUEST_THROTTLE_MS
    ) {
      return
    }
    requestedOnce = true
    lastRequestAtMs = SystemClock.elapsedRealtime()
    runCatching {
      context.sendBroadcast(
        Intent(ModuleHookBridgeContract.ACTION_CONFIG_SNAPSHOT_REQUEST).apply {
          setPackage(ModuleHookBridgeContract.MODULE_PACKAGE)
          addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        },
      )
      log("hook config snapshot requested from app")
    }.onFailure { log("hook config snapshot request failed: ${it.message}") }
  }

  private fun loadFromDisk(): ModuleHookSnapshot? {
    // 读取顺序：app 导出的外部快照 → 设备保护目录（system_server 多读不到，试一下无害）
    // → 历史遗留的 /data/system/slideindex（**仅在内存里没有任何快照时**兜底）。
    val fromExternal = runCatching {
      readText(File(ModuleHookBridgeContract.APP_EXTERNAL_SNAPSHOT_PATH))
    }.getOrNull()
    val fromApp = if (fromExternal == null) {
      runCatching { readText(File(ModuleHookBridgeContract.APP_SNAPSHOT_PATH)) }.getOrNull()
    } else {
      null
    }
    // 遗留目录只在冷启动（内存里还没有任何配置）时用一次，避免它每 2 秒把广播下发的配置顶掉。
    val fromLegacySystem = if (fromExternal == null && fromApp == null && cached == null) {
      runCatching { readText(systemSnapshotFile()) }.getOrNull()
    } else {
      null
    }
    lastLoadAtMs = SystemClock.elapsedRealtime()
    return ModuleHookSnapshot.parse(fromExternal ?: fromApp ?: fromLegacySystem)
  }

  private fun readText(file: File): String? {
    if (!file.isFile || !file.canRead()) return null
    val text = file.readText()
    return text.ifBlank { null }
  }

  private fun systemSnapshotFile(): File =
    File(
      File(ModuleHookBridgeContract.SYSTEM_SNAPSHOT_DIR),
      ModuleHookBridgeContract.SNAPSHOT_FILE_NAME,
    )
}
