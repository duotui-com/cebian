package com.slideindex.app.ui.navigation

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import com.slideindex.app.MainActivity
import com.slideindex.app.R
import com.slideindex.app.clipboard.ClipboardPermissionHelper
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.overlay.LayoutPreviewContent
import com.slideindex.app.overlay.LayoutPreviewFocus
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.service.OverlayService
import com.slideindex.app.service.SlideIndexAccessibilityService
import com.slideindex.app.gesture.GestureAngles
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.util.HapticHelper
import com.slideindex.app.util.KeepAliveHelper
import com.slideindex.app.util.MediaSessionHelper
import com.slideindex.app.util.PermissionHelper
import com.slideindex.app.util.SecureSettingsHelper
import com.slideindex.app.util.TaskManagerUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Stable
class MainNavContext(
    val activity: MainActivity,
    val deps: AppDependencies,
    val backStack: NavBackStack,
    val permissionStates: NavPermissionStates,
    val floatingPointerAreaPreviewEnabledState: MutableState<Boolean>,
    val rootBottomContentPadding: Dp,
    val bottomNavReselectCount: Int = 0,
    val onBottomNavBlurPreviewChange: (Float) -> Unit = {},
    val onBottomNavBlurPreviewStop: () -> Unit = {},
) {
    @Composable
    fun collectAppSettings(): AppSettings {
        val settings by deps.settingsRepository.settings.collectAsStateWithLifecycle(
            initialValue = deps.settingsRepository.readSnapshot(),
        )
        return settings
    }

    @Composable
    fun collectPermissions(): NavPermissionSnapshot = permissionStates.collect()

    @Composable
    fun collectAreaPreviewEnabled(): Boolean {
        val enabled by floatingPointerAreaPreviewEnabledState
        return enabled
    }

    fun setFloatingPointerAreaPreviewEnabled(enabled: Boolean) {
        floatingPointerAreaPreviewEnabledState.value = enabled
    }

    fun gestureActive(settings: AppSettings, permissions: NavPermissionSnapshot): Boolean =
        gestureActive(settings.serviceEnabled, permissions)

    fun gestureActive(serviceEnabled: Boolean, permissions: NavPermissionSnapshot): Boolean =
        serviceEnabled && permissions.accessibilityGranted && permissions.notificationGranted

    private var deferredNavigateJob: Job? = null

    /** 推迟一帧再入栈，避免 Hub 行在 Miuix 按压高亮绘制前就被 Nav 转场卸掉。 */
    fun navigate(key: AppNavKey) {
        deferredNavigateJob?.cancel()
        deferredNavigateJob = activity.lifecycleScope.launch {
            awaitFrame()
            backStack.navigate(key)
        }
    }

    fun navigateBackTo(key: AppNavKey) = backStack.navigateBackTo(key)

    fun replaceRoot(key: AppNavKey) = backStack.replaceRoot(key)

    fun launch(block: suspend () -> Unit) {
        activity.lifecycleScope.launch { block() }
    }

    fun sendOverlayPreviewIntent(
        action: String,
        content: LayoutPreviewContent = LayoutPreviewContent.TRIGGER_ONLY,
        focus: LayoutPreviewFocus? = null,
    ) {
        activity.sendOverlayPreviewIntent(action, content, focus)
    }

    fun startFocusedTriggerPreview(
        side: PanelSide,
        handleId: String,
        showPairedGroup: Boolean = false,
    ) {
        retainFocusedTriggerPreview(
            triggerPreviewFocus(
                side = side,
                handleId = handleId,
                showSwipeDistances = false,
                showPairedGroup = showPairedGroup,
            ),
        )
    }

    fun startSwipeDistancePreview(
        side: PanelSide,
        handleId: String,
        showPairedGroup: Boolean = false,
    ) {
        retainFocusedTriggerPreview(
            triggerPreviewFocus(
                side = side,
                handleId = handleId,
                showSwipeDistances = true,
                showPairedGroup = showPairedGroup,
            ),
        )
    }

    fun startTriggerDesignPreview(side: PanelSide, handleId: String) {
        retainFocusedTriggerPreview(
            LayoutPreviewFocus(
                side = side,
                handleId = handleId,
                showSwipeDistances = false,
                showPairedGroup = side.isHorizontalEdge,
            ),
        )
    }

    fun refreshFocusedTriggerPreview(
        side: PanelSide,
        handleId: String,
        showPairedGroup: Boolean = false,
    ) {
        sendOverlayPreviewIntent(
            action = OverlayService.ACTION_PREVIEW_START,
            content = LayoutPreviewContent.TRIGGER_ONLY,
            focus = triggerPreviewFocus(
                side = side,
                handleId = handleId,
                showSwipeDistances = false,
                showPairedGroup = showPairedGroup,
            ),
        )
    }

    fun refreshSwipeDistancePreview(
        side: PanelSide,
        handleId: String,
        showPairedGroup: Boolean = false,
    ) {
        sendOverlayPreviewIntent(
            action = OverlayService.ACTION_PREVIEW_START,
            content = LayoutPreviewContent.TRIGGER_ONLY,
            focus = triggerPreviewFocus(
                side = side,
                handleId = handleId,
                showSwipeDistances = true,
                showPairedGroup = showPairedGroup,
            ),
        )
    }

    fun releaseFocusedTriggerPreview() {
        cancelPendingTriggerPreviewStop()
        focusedTriggerPreviewRetainCount = (focusedTriggerPreviewRetainCount - 1).coerceAtLeast(0)
        if (focusedTriggerPreviewRetainCount > 0) return
        scheduleTriggerPreviewStop()
    }

    fun stopGestureAnglesPreview() {
        clearGestureAnglesPreview()
    }

    fun startGestureAnglesPreview(angles: GestureAngles) {
        previewGestureAngles(angles)
    }

    fun updateGestureAnglesPreview(angles: GestureAngles) {
        previewGestureAngles(angles)
    }

    // 悬浮指针"行程范围预览"：窗口与跟手位置都在 :overlay，拖动期只推实时灵敏度。
    fun previewFloatingPointerAreaSensitivityStart() {
        activity.setFloatingPointerAreaPreview(true)
    }

    fun previewFloatingPointerAreaSensitivity(fraction: Float) {
        activity.setFloatingPointerAreaPreview(true, fraction)
    }

    /** 松手：清掉临时灵敏度，预览继续跟随已保存设置。 */
    fun previewFloatingPointerAreaSensitivityEnd() {
        activity.setFloatingPointerAreaPreview(true, Float.NaN)
    }

    /** 小组件编辑器点"预览"：由 :overlay 弹出小组件面板显示真身。 */
    fun showWidgetPanelPreview() {
        activity.showWidgetPanelPreview()
    }

    fun stopTriggerPreview() {
        cancelPendingTriggerPreviewStop()
        focusedTriggerPreviewRetainCount = 0
        sendOverlayPreviewIntent(OverlayService.ACTION_PREVIEW_STOP)
    }

    fun startFloatBallStripZonePreview() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_FLOAT_BALL_STRIP_ZONE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_ACTIVE, true)
        }
    }

    fun stopFloatBallStripZonePreview() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_FLOAT_BALL_STRIP_ZONE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_ACTIVE, false)
        }
    }

    fun previewFloatBallPositionY(fraction: Float) {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_FLOAT_BALL_POSITION_Y) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_POSITION_Y_FRACTION, fraction)
        }
    }

    fun endFloatBallPositionYPreview(restoreIfNeeded: Boolean) {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_FLOAT_BALL_POSITION_Y) { intent ->
            if (restoreIfNeeded) intent.putExtra(OverlayService.EXTRA_PREVIEW_RESTORE, true)
        }
    }

    fun clearFloatBallPositionYPreviewRestore() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_FLOAT_BALL_POSITION_Y) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CLEAR_RESTORE, true)
        }
    }

    fun previewFloatBallAppearance(
        sizeDp: Float? = null,
        opacity: Float? = null,
        visibleFraction: Float? = null,
        lineHeightFraction: Float? = null,
        lineWidthFraction: Float? = null,
        lineOpacity: Float? = null,
    ) {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_FLOAT_BALL_APPEARANCE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_ACTIVE, true)
            if (sizeDp != null) intent.putExtra(OverlayService.EXTRA_PREVIEW_SIZE_DP, sizeDp)
            if (opacity != null) intent.putExtra(OverlayService.EXTRA_PREVIEW_OPACITY, opacity)
            if (visibleFraction != null) {
                intent.putExtra(OverlayService.EXTRA_PREVIEW_VISIBLE_FRACTION, visibleFraction)
            }
            if (lineHeightFraction != null) {
                intent.putExtra(OverlayService.EXTRA_PREVIEW_LINE_HEIGHT_FRACTION, lineHeightFraction)
            }
            if (lineWidthFraction != null) {
                intent.putExtra(OverlayService.EXTRA_PREVIEW_LINE_WIDTH_FRACTION, lineWidthFraction)
            }
            if (lineOpacity != null) intent.putExtra(OverlayService.EXTRA_PREVIEW_LINE_OPACITY, lineOpacity)
        }
    }

    fun endFloatBallAppearancePreview(restoreIfNeeded: Boolean) {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_FLOAT_BALL_APPEARANCE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_ACTIVE, false)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_RESTORE, restoreIfNeeded)
        }
    }

    fun clearFloatBallAppearancePreviewRestore() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_FLOAT_BALL_APPEARANCE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CLEAR_RESTORE, true)
        }
    }

    // 浮层预览必须跨进程送达 :overlay —— 设置界面在独立进程，
    // 直连 SlideIndexAccessibilityService.instance 只会命中 null 静默失效。

    fun startCornerZonePreview() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_CORNER_ZONE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CORNER_ACTIVE, true)
        }
    }

    fun stopCornerZonePreview() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_CORNER_ZONE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CORNER_ACTIVE, false)
        }
    }

    fun updateCornerZonePreview(
        verticalEdgeWidthDp: Float,
        verticalEdgeHeightDp: Float,
        horizontalEdgeWidthDp: Float,
        horizontalEdgeHeightDp: Float,
    ) {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_CORNER_ZONE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CORNER_ACTIVE, true)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CORNER_V_WIDTH, verticalEdgeWidthDp)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CORNER_V_HEIGHT, verticalEdgeHeightDp)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CORNER_H_WIDTH, horizontalEdgeWidthDp)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_CORNER_H_HEIGHT, horizontalEdgeHeightDp)
        }
    }

    fun previewIndexHeightFraction(fraction: Float) {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_INDEX_HEIGHT) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_INDEX_FRACTION, fraction)
        }
    }

    fun clearIndexHeightPreview() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_INDEX_HEIGHT) { }
    }

    fun previewTriggerHandleEdgeWidth(side: PanelSide, handleId: String, edgeWidthDp: Float) {
        previewTriggerHandle(side, handleId) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_EDGE_WIDTH_DP, edgeWidthDp)
        }
    }

    fun previewTriggerHandleVerticalRange(
        side: PanelSide,
        handleId: String,
        topFraction: Float,
        bottomFraction: Float,
    ) {
        previewTriggerHandle(side, handleId) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_TOP_FRACTION, topFraction)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_BOTTOM_FRACTION, bottomFraction)
        }
    }

    fun previewTriggerHandleSwipeDistances(
        side: PanelSide,
        handleId: String,
        shortSwipeDistanceDp: Float? = null,
        longSwipeDistanceDp: Float? = null,
    ) {
        previewTriggerHandle(side, handleId) { intent ->
            if (shortSwipeDistanceDp != null) {
                intent.putExtra(OverlayService.EXTRA_PREVIEW_SHORT_SWIPE_DP, shortSwipeDistanceDp)
            }
            if (longSwipeDistanceDp != null) {
                intent.putExtra(OverlayService.EXTRA_PREVIEW_LONG_SWIPE_DP, longSwipeDistanceDp)
            }
        }
    }

    fun previewTriggerHandleDesign(
        side: PanelSide,
        handleId: String,
        design: com.slideindex.app.gesture.TriggerHandleDesign,
    ) {
        previewTriggerHandle(side, handleId) { intent ->
            intent.putExtra(
                OverlayService.EXTRA_PREVIEW_DESIGN,
                com.slideindex.app.gesture.TriggerHandleDesignCodec.encode(design),
            )
        }
    }

    /** 手势角度预览：同样必须跨进程。 */
    fun previewGestureAngles(angles: com.slideindex.app.gesture.GestureAngles) {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_GESTURE_ANGLES) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_GESTURE_ANGLES, angles.toFloatArray())
        }
    }

    fun clearGestureAnglesPreview() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_GESTURE_ANGLES) { }
    }

    fun clearTriggerHandleLayoutPreview() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_CLEAR) { }
    }

    fun clearOverlayLayoutPreview() {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_CLEAR) { }
    }

    private fun previewTriggerHandle(
        side: PanelSide,
        handleId: String,
        configure: (android.content.Intent) -> Unit,
    ) {
        activity.sendOverlayPreviewExtras(OverlayService.ACTION_PREVIEW_TRIGGER_HANDLE) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_SIDE, side.name)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_HANDLE_ID, handleId)
            configure(intent)
        }
    }

    private fun triggerPreviewFocus(
        side: PanelSide,
        handleId: String,
        showSwipeDistances: Boolean,
        showPairedGroup: Boolean,
    ): LayoutPreviewFocus = LayoutPreviewFocus(
        side = side,
        handleId = handleId,
        showSwipeDistances = showSwipeDistances,
        showPairedGroup = showPairedGroup,
    )

    private fun retainFocusedTriggerPreview(focus: LayoutPreviewFocus) {
        cancelPendingTriggerPreviewStop()
        focusedTriggerPreviewRetainCount++
        sendOverlayPreviewIntent(
            action = OverlayService.ACTION_PREVIEW_START,
            content = LayoutPreviewContent.TRIGGER_ONLY,
            focus = focus,
        )
    }

    private fun scheduleTriggerPreviewStop() {
        cancelPendingTriggerPreviewStop()
        pendingTriggerPreviewStop = Runnable {
            if (focusedTriggerPreviewRetainCount == 0) {
                sendOverlayPreviewIntent(OverlayService.ACTION_PREVIEW_STOP)
            }
        }
        triggerPreviewHandler.postDelayed(pendingTriggerPreviewStop!!, TRIGGER_PREVIEW_HANDOFF_MS)
    }

    private fun cancelPendingTriggerPreviewStop() {
        pendingTriggerPreviewStop?.let(triggerPreviewHandler::removeCallbacks)
        pendingTriggerPreviewStop = null
    }

    companion object {
        private const val TRIGGER_PREVIEW_HANDOFF_MS = 80L
        private val triggerPreviewHandler = Handler(Looper.getMainLooper())
        private var focusedTriggerPreviewRetainCount = 0
        private var pendingTriggerPreviewStop: Runnable? = null
    }

    fun startActivity(intent: Intent) {
        activity.startActivity(intent)
    }

    fun requestNotificationPermission() {
        activity.requestNotificationPermission()
    }

    fun requestShizuku() {
        TaskManagerUtil.requestPermission(activity)
    }

    fun refreshServiceState() {
        activity.refreshServiceState()
    }

    fun refreshPermissionState() {
        activity.refreshPermissionState()
    }

    fun previewHaptic(enabled: Boolean = true, strengthLevel: Int? = null) {
        launch {
            val latest = deps.settingsRepository.settings.first()
            HapticHelper.preview(
                activity.window.decorView,
                latest.copy(
                    hapticEnabled = enabled,
                    hapticStrengthLevel = strengthLevel ?: latest.hapticStrengthLevel,
                ),
            )
        }
    }

    fun openAccessibilitySettings() {
        startActivity(PermissionHelper.accessibilitySettingsIntent())
    }

    fun openOverlaySettings() {
        startActivity(PermissionHelper.overlaySettingsIntent(activity))
    }

    fun openNotificationListenerSettings() {
        startActivity(MediaSessionHelper.notificationListenerSettingsIntent())
    }

    fun openUsageAccessSettings() {
        PermissionHelper.requestUsageAccess(activity)
    }

    fun requestBatteryOptimization() {
        if (!PermissionHelper.requestBatteryOptimizationAccess(activity)) {
            deps.userMessageBus.showError(
                activity.getString(R.string.battery_optimization_request_failed),
            )
        }
    }

    fun openAutoStartSettings() {
        KeepAliveHelper.gotoSettings(activity)
    }

    fun requestSecureSettingsGrant(): Boolean {
        val granted = SecureSettingsHelper.grantViaShizuku(activity)
        refreshPermissionState()
        val messageRes = if (granted) {
            R.string.secure_settings_grant_success
        } else {
            R.string.secure_settings_grant_failed
        }
        val message = activity.getString(messageRes)
        if (granted) {
            deps.userMessageBus.showSuccess(message)
        } else {
            deps.userMessageBus.showError(message)
        }
        return granted
    }

    fun requestReadLogsGrant(): Boolean {
        val granted = ClipboardPermissionHelper.grantViaShizuku(activity)
        val messageRes = if (granted) {
            R.string.secure_settings_grant_success
        } else {
            R.string.secure_settings_grant_failed
        }
        val message = activity.getString(messageRes)
        if (granted) {
            deps.userMessageBus.showSuccess(message)
        } else {
            deps.userMessageBus.showError(message)
        }
        return granted
    }
}
