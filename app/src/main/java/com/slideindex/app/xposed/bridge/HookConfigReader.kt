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
    // 为什么必须比：部分 ROM 上 system_server 只能读到磁盘三种来源里的一种，而那份可能停在
    // 几天前的旧状态（例如"两侧接管关闭"时的 groups=4、或白名单里只有自己）。原来无条件
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
    // 三个可读来源放在一起比 `updatedAtMs`，取最新的一份。
    //
    // 为什么不能"读到第一份就用"：磁盘来源在部分 ROM 上只有其中一份可读
    // （实测 Flyme/Android 16：system_server 读不到 app 外部快照，设备保护目录多半也读不到）。
    // 原来"遗留目录只在内存为空时读一次"，一旦某份旧快照先被读到就会永久停在那里——
    // 现象就是白名单只有自己、用户后加的包永远不生效。取最新一份后，
    // 无论先读到谁，都不会压住更新的配置（时间戳比较见 [preferNewer]）。
    val raws = listOf(
      readText(File(ModuleHookBridgeContract.APP_EXTERNAL_SNAPSHOT_PATH)),
      readText(File(ModuleHookBridgeContract.APP_SNAPSHOT_PATH)),
      readText(systemSnapshotFile()),
    )
    lastLoadAtMs = SystemClock.elapsedRealtime()
    return newestSnapshot(raws)
  }

  /** 读盘失败（不存在 / 无权限 / IO 异常）一律当"这份没有"，绝不让异常冒到 hook 热路径上。 */
  private fun readText(file: File): String? = runCatching {
    if (!file.isFile || !file.canRead()) return@runCatching null
    file.readText().ifBlank { null }
  }.getOrNull()

  private fun systemSnapshotFile(): File =
    File(
      File(ModuleHookBridgeContract.SYSTEM_SNAPSHOT_DIR),
      ModuleHookBridgeContract.SNAPSHOT_FILE_NAME,
    )

  internal companion object {
    /**
     * 多份磁盘快照里取 `updatedAtMs` 最新的一份（不可读 / 解析失败的忽略），全空返回 null。
     *
     * 抽成纯函数是为了能直测这条回归：只"读到第一份就用"会让一份几天前的旧快照
     * 永久压住新配置 —— Flyme/Android 16 上「白名单里永远只有自己」就是这么来的。
     */
    fun newestSnapshot(raws: List<String?>): ModuleHookSnapshot? =
      raws.mapNotNull { ModuleHookSnapshot.parse(it) }.maxByOrNull { it.updatedAtMs }
  }
}
