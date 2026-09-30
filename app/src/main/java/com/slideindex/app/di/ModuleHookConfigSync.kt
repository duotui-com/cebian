package com.slideindex.app.di

import android.content.Context
import com.slideindex.app.overlay.FloatBallScreenMetrics
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.overlay.TakeoverExtraRects
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.settings.edgeTriggerWidthDp
import com.slideindex.app.settings.interceptWindowWidthDp
import com.slideindex.app.settings.triggerHandles
import com.slideindex.app.util.OverlaySuppression
import com.slideindex.app.xposed.bridge.ModuleHookConfigWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 把接管开关与触钮几何同步给 system_server 模块（EdgeX 的快照文件 + 广播模式）。
 *
 * 仅在与接管相关的配置真正变化时才下发，避免无谓的跨进程广播。
 */
@Singleton
class ModuleHookConfigSync @Inject constructor(
  @ApplicationContext private val context: Context,
  private val settingsRepository: SettingsRepository,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var started = false

  @OptIn(FlowPreview::class)
  fun start() {
    if (started) return
    started = true
    scope.launch {
      settingsRepository.settings
        .map { settings -> takeoverSignature(settings) }
        .distinctUntilChanged()
        .debounce(SYNC_DEBOUNCE_MS)
        .collect { publish() }
    }
  }

  fun publish() {
    runCatching {
      ModuleHookConfigWriter.publish(context, settingsRepository.readSnapshot())
    }
  }

  private fun takeoverSignature(settings: AppSettings): String = buildString {
    append(ModuleHookConfigWriter.takeoverGroups(settings))
    append('|').append(settings.interceptSystemBackGesture)
    append('|').append(ModuleHookConfigWriter.navigationMode(context))
    append('|').append(settings.limitMaxInterceptLength)
    // 角轮盘接管矩形的输入；不含这些的话开关/尺寸变化不会重新下发。
    val corner = settings.cornerGestureSettings
    append('|').append(corner.enabled)
    append('|').append(corner.leftEnabled).append(corner.rightEnabled)
    append('|').append(corner.verticalEdgeWidthDp).append(',').append(corner.verticalEdgeHeightDp)
    append('|').append(corner.horizontalEdgeWidthDp).append(',').append(corner.horizontalEdgeHeightDp)
    append('|').append(corner.hideInLandscape)
    append('|').append(OverlaySuppression.isLandscape(context))
    // 剪贴板白名单变化也要重新下发（LSPosed 模式靠它决定放行哪些包）。
    append('|').append(settings.clipboardLsposedWhitelist.sorted().joinToString(","))
    for (side in listOf(PanelSide.LEFT, PanelSide.RIGHT, PanelSide.BOTTOM, PanelSide.TOP)) {
      append('#').append(side.name)
      append(':').append(settings.interceptWindowWidthDp(side))
      append(':').append(settings.edgeTriggerWidthDp(side))
      settings.triggerHandles(side).forEach { handle ->
        append(',').append(handle.id)
          .append('@').append(handle.topFraction)
          .append('+').append(handle.heightFraction)
          .append('*').append(handle.edgeWidthDp)
      }
    }
    // 悬浮球 / 双贴边线条的**接管矩形指纹**。
    //
    // 这些矩形是按下发那一刻的球位、停靠侧、球径、线条开关与键盘避让算出来的比例矩形；
    // 少了这一段签名，球线换位/拖动后签名不变 → distinctUntilChanged 把这次发射吞掉 →
    // 模块手里还是旧侧的矩形，而球的新位置通常正好落在该侧两条触钮手柄之间的竖直空隙里
    //（没有任何矩形覆盖）→ 模块直接放行 → 只剩系统返回手势。真机复现：球线换位后必现。
    append('|').append(extraRectsFingerprint(settings))
  }

  /**
   * [TakeoverExtraRects] 生成结果的指纹。
   *
   * 不逐字段枚举，直接拿生成出来的 data class 文本——这样球的位置、停靠侧、球径、
   * 线条开关与长度、键盘避让、横竖屏 任一变化都会改变签名，不会漏字段。
   */
  private fun extraRectsFingerprint(settings: AppSettings): String = runCatching {
    val (screenWidthPx, screenHeightPx) = FloatBallScreenMetrics.sizePx(context)
    TakeoverExtraRects.build(
      settings = settings,
      screenWidthPx = screenWidthPx,
      screenHeightPx = screenHeightPx,
      density = context.resources.displayMetrics.density,
      isLandscape = OverlaySuppression.isLandscape(context),
    ).joinToString(";")
  }.getOrDefault("")

  private companion object {
    const val SYNC_DEBOUNCE_MS = 300L
  }
}
