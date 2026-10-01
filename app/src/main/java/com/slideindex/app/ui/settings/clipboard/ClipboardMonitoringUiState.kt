package com.slideindex.app.ui.settings.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.R
import com.slideindex.app.clipboard.monitor.ClipboardMonitorController
import com.slideindex.app.clipboard.monitor.ClipboardMonitorStatusPort
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.ClipboardMonitoringMode
import com.slideindex.app.settings.effectiveClipboardMonitoringMode
import com.slideindex.app.util.PermissionHelper
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

data class ClipboardMonitoringUiState(
    val shizukuGranted: Boolean,
    val rootAvailable: Boolean,
    val overlayGranted: Boolean,
    val monitorRunning: Boolean,
    /** 当前实际在跑的监听模式；未在监听时为 null。 */
    val activeMode: ClipboardMonitoringMode? = null,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClipboardMonitorControllerEntryPoint {
    fun clipboardMonitorController(): ClipboardMonitorController
}

/** 状态轮询间隔：只做兜底，1s 足够，也不至于浪费电。 */
private const val STATUS_POLL_INTERVAL_MS = 1_000L

@Composable
fun rememberClipboardMonitoringUiState(settings: AppSettings): ClipboardMonitoringUiState {
    val context = LocalContext.current
    val controller = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ClipboardMonitorControllerEntryPoint::class.java,
        ).clipboardMonitorController()
    }
    // 监听状态直接跟着 controller 的 StateFlow 走：切换通道后状态行立刻更新，不必等页面 resume。
    val localRunning by controller.isListeningFlow.collectAsStateWithLifecycle()
    val localMode by controller.activeModeFlow.collectAsStateWithLifecycle()
    // 监听服务跑在 :clipboard 进程，设置页在主进程：优先用跨进程镜像，
    // 没收到镜像时（例如就在监听进程内）再回退到本进程 controller。
    //
    // 状态只有一个槽：推送（订阅）与轮询都往同一个槽写"当前值"，
    // 所以不会出现"旧值优先"——两条路径写进去的都是同一份镜像的当前内容，
    // 推送负责即时、轮询负责兜底（切换模式时主线程最忙，推送最容易晚到）。
    var liveStatus by remember { mutableStateOf(ClipboardMonitorStatusPort.status.value) }
    LaunchedEffect(Unit) {
        ClipboardMonitorStatusPort.status.collect { liveStatus = it }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val latest = ClipboardMonitorStatusPort.status.value
            if (latest != liveStatus) liveStatus = latest
            kotlinx.coroutines.delay(STATUS_POLL_INTERVAL_MS)
        }
    }
    // 切通道/切采集方式时立刻要一帧，缩短"刚切换完还显示旧状态"的窗口。
    LaunchedEffect(settings.clipboardMonitoringChannel, settings.clipboardMonitoringCapture) {
        ClipboardMonitorStatusPort.requestStatus(context)
    }
    val monitorRunning = liveStatus?.listening ?: localRunning
    val activeMode = liveStatus?.mode ?: localMode
    var shizukuGranted by remember { mutableStateOf(controller.hasShizukuPermission()) }
    var rootAvailable by remember { mutableStateOf(controller.isRootAvailable()) }
    var overlayGranted by remember {
        mutableStateOf(PermissionHelper.canDrawOverlays(context))
    }

    fun refresh() {
        shizukuGranted = controller.hasShizukuPermission()
        rootAvailable = controller.isRootAvailable()
        overlayGranted = PermissionHelper.canDrawOverlays(context)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, settings.clipboardBackgroundMonitoring) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
                ClipboardMonitorStatusPort.requestStatus(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 首帧也主动要一次状态，避免刚进页面时状态还是空的。
    DisposableEffect(Unit) {
        ClipboardMonitorStatusPort.requestStatus(context)
        onDispose { }
    }

    return remember(shizukuGranted, rootAvailable, overlayGranted, monitorRunning, activeMode, settings) {
        ClipboardMonitoringUiState(
            shizukuGranted = shizukuGranted,
            rootAvailable = rootAvailable,
            overlayGranted = overlayGranted,
            monitorRunning = monitorRunning,
            activeMode = activeMode,
        )
    }
}

/**
 * 这套模式此刻"能不能跑起来"（能力齐不齐）。
 *
 * 注意这不是"有没有在监听"：UI 想显示真实状态请看 [ClipboardMonitoringUiState.monitorRunning]，
 * 这里只在"没在监听"时用来解释原因。传进来的必须是**已解析**的具体模式
 * （`settings.effectiveClipboardMonitoringMode()`），不要直接把旧的单一模式字段塞进来。
 */
fun isClipboardMonitoringBackendReady(
    mode: ClipboardMonitoringMode,
    state: ClipboardMonitoringUiState,
): Boolean = when {
    // 标准公开 API 不需要任何特权；能不能读到内容由前台/面板时机决定。
    mode.usesStandardApi -> true
    // LSPosed 白名单：模块把包当成默认输入法放行，不依赖 Shizuku / Root，
    // 也不走 16×16 焦点探针（所以不要求悬浮窗权限）。模块自身就绪与否由「LSPosed 状态」单独判定。
    mode.usesLsposed -> true
    mode.usesRoot -> state.rootAvailable && state.overlayGranted
    else -> state.shizukuGranted && state.overlayGranted
}

fun AppSettings.isClipboardMonitoringBackendReady(state: ClipboardMonitoringUiState): Boolean =
    isClipboardMonitoringBackendReady(effectiveClipboardMonitoringMode(), state)

/** 副标题里「剪贴板监听」那一段的取舍。 */
internal enum class ClipboardMonitorSummaryKind { Off, Lsposed, Standard, Root, Privileged, NotReady }

/**
 * 先看**真实运行状态**（[ClipboardMonitoringUiState.monitorRunning] + 实际在跑的模式），
 * 只有"开关开着却没跑起来"才说未就绪。
 *
 * 以前这里只查能力、且把 LSPosed 当成"要 Shizuku"，于是 Shizuku 没授权/binder 没连上时，
 * 明明 LSPosed 通道跑得好好的，副标题却写「监听未就绪」，跟常驻通知对不上。
 */
internal fun clipboardMonitorSummaryKind(
    monitoringEnabled: Boolean,
    state: ClipboardMonitoringUiState,
): ClipboardMonitorSummaryKind = when {
    !monitoringEnabled -> ClipboardMonitorSummaryKind.Off
    state.monitorRunning -> when (state.activeMode) {
        ClipboardMonitoringMode.LSPOSED -> ClipboardMonitorSummaryKind.Lsposed
        ClipboardMonitoringMode.STANDARD -> ClipboardMonitorSummaryKind.Standard
        ClipboardMonitoringMode.ROOT_LOGS,
        ClipboardMonitoringMode.ROOT_HIDDEN_API -> ClipboardMonitorSummaryKind.Root
        // 含 Shizuku 日志 / 隐藏 API；activeMode 缺失时也给一个中性说法（运行中就不说未就绪）。
        else -> ClipboardMonitorSummaryKind.Privileged
    }
    else -> ClipboardMonitorSummaryKind.NotReady
}

/** 剪贴板监听状态的副标题片段：暂存夹页与扩展 tab 共用，避免各写一套再次走偏。 */
@Composable
fun clipboardMonitorSummaryText(
    monitoringEnabled: Boolean,
    state: ClipboardMonitoringUiState,
): String = stringResource(
    when (clipboardMonitorSummaryKind(monitoringEnabled, state)) {
        ClipboardMonitorSummaryKind.Off ->
            R.string.stash_clipboard_entry_summary_clipboard_off
        ClipboardMonitorSummaryKind.Lsposed ->
            R.string.stash_clipboard_entry_summary_clipboard_lsposed
        ClipboardMonitorSummaryKind.Standard ->
            R.string.stash_clipboard_entry_summary_clipboard_standard
        ClipboardMonitorSummaryKind.Root ->
            R.string.stash_clipboard_entry_summary_clipboard_root
        ClipboardMonitorSummaryKind.Privileged ->
            R.string.stash_clipboard_entry_summary_clipboard_shizuku
        ClipboardMonitorSummaryKind.NotReady ->
            R.string.stash_clipboard_entry_summary_clipboard_not_ready
    },
)
