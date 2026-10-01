package com.slideindex.app.xposed.hook

/*
 * Portions derived from Clipboard Whitelist (https://github.com/Tehcneko/ClipboardWhitelist)
 * Licensed under GPL-3.0. Modified for com.slideindex.app.
 */

import com.slideindex.app.xposed.HookParam
import com.slideindex.app.xposed.LibXposedMethodHook
import com.slideindex.app.xposed.LibXposedReflect
import com.slideindex.app.xposed.XposedLog
import com.slideindex.app.xposed.bridge.HookConfigReader
import com.slideindex.app.xposed.bridge.ModuleHookSnapshot
import com.slideindex.app.xposed.hookMethod
import io.github.libxposed.api.XposedInterface

/**
 * 剪贴板白名单：hook `ClipboardService.isDefaultIme(int, String)`。
 *
 * 命中白名单的包会被系统当成「默认输入法」，于是后台也能读剪贴板——
 * 这是"不抢焦点"的路径：省掉 16×16 焦点探针，也不再和别的剪贴板应用抢焦点。
 *
 * 单例：安装点（[SlideIndexLibXposedModule]）和配置广播入口（[SystemInputFilterHook]）
 * 必须共用同一个 `configReader`，否则广播下发的新名单到不了这个 reader。
 */
object ClipboardWhitelistHook {
  private const val TAG = "ClipboardWhitelist"
  private const val HOOK_ID = "clipboard_whitelist_is_default_ime"

  const val STATUS_OK = "ok"
  const val STATUS_MISSING_SERVICE = "missing-service"
  const val STATUS_MISSING_METHOD = "missing-method"
  const val STATUS_FAILED = "failed"

  private val configReader = HookConfigReader { message -> XposedLog.w(TAG, message) }

  @Volatile
  private var cachedWhitelist: Set<String> = emptySet()

  /**
   * 最近一次安装结果，随模块状态串回传给 app（system_server 单进程，volatile 读足够）。
   *
   * 默认 [STATUS_FAILED]：app 侧看到它就知道"白名单 hook 此刻没生效"，不会误报正常。
   */
  @Volatile
  var installStatus: String = STATUS_FAILED
    private set

  fun install(xposed: XposedInterface, classLoader: ClassLoader): List<XposedInterface.HookHandle> =
    runCatching { installInternal(xposed, classLoader) }
      .getOrElse {
        XposedLog.e(TAG, "ClipboardWhitelistHook failed", it)
        installStatus = STATUS_FAILED
        emptyList()
      }

  /**
   * app 侧配置广播（由 [SystemInputFilterHook] 的状态通道转进来）。
   *
   * 为什么必须有这条：磁盘快照在部分 ROM 上 system_server 读不到
   * （外部 `Android/data` 被 FUSE 拒、设备保护目录是 app 私有），冷启动只能退到
   * `/data/system` 的遗留快照；那份可能停在几天前。实测 Flyme/Android 16 上就是这个后果：
   * 磁盘读到的是旧快照 → 白名单里只有自己 → 用户后加的包永远拿不到放行，
   * 而广播通道本身是好的（接管的 reader 同一个广播就能收到 "hook config applied"）。
   */
  fun onConfigBroadcast(json: String?) {
    val fresh = whitelistFromBroadcast(json) ?: run {
      XposedLog.w(TAG, "clipboard whitelist broadcast ignored: unparsable snapshot")
      return
    }
    // 让 reader 的内存缓存也换成广播这一份，避免下一次 current() 又回落到旧盘上那份。
    configReader.applyBroadcast(json)
    cachedWhitelist = fresh
    XposedLog.i(TAG, "clipboard whitelist updated: size=${fresh.size} packages=$fresh")
  }

  /**
   * 广播 JSON → 白名单集合；解析失败返回 null。
   *
   * 单独抽出来是为了能直测「广播里的包真的进了名单」这条回归（见 ClipboardWhitelistBroadcastTest）。
   */
  internal fun whitelistFromBroadcast(json: String?): Set<String>? =
    ModuleHookSnapshot.parse(json)?.clipboardWhitelist?.toSet()

  /** 当前生效的白名单（诊断 / 单测用）。 */
  internal fun activeWhitelist(): Set<String> = cachedWhitelist

  private fun installInternal(
    xposed: XposedInterface,
    classLoader: ClassLoader,
  ): List<XposedInterface.HookHandle> {
    val serviceClass = LibXposedReflect.findClassIfExists(
      "com.android.server.clipboard.ClipboardService",
      classLoader,
    ) ?: run {
      XposedLog.w(TAG, "ClipboardService not found")
      installStatus = STATUS_MISSING_SERVICE
      return emptyList()
    }
    val method = LibXposedReflect.findMethodExactIfExists(
      serviceClass,
      "isDefaultIme",
      Int::class.javaPrimitiveType,
      String::class.java,
    ) ?: run {
      // 系统版本改了内部实现时走到这里：记录一条状态，避免"装上了但没生效"的黑盒。
      XposedLog.w(TAG, "ClipboardService.isDefaultIme(int, String) not found")
      installStatus = STATUS_MISSING_METHOD
      return emptyList()
    }
    val handle = xposed.hookMethod(
      method,
      object : LibXposedMethodHook() {
        override fun beforeHookedMethod(param: HookParam) {
          val packageName = param.arg(1) as? String ?: return
          if (currentWhitelist().contains(packageName)) {
            param.result = true
            param.returnEarly = true
          }
        }
      },
      id = HOOK_ID,
    )
    XposedLog.i(TAG, "ClipboardWhitelistHook installed")
    installStatus = STATUS_OK
    return listOf(handle)
  }

  /** 白名单命中判断在 system_server 的热路径上：读不到新快照时沿用上一次的缓存。 */
  private fun currentWhitelist(): Set<String> {
    val snapshot = configReader.current() ?: return cachedWhitelist
    val fresh = snapshot.clipboardWhitelist.toSet()
    if (fresh != cachedWhitelist) cachedWhitelist = fresh
    return fresh
  }
}
