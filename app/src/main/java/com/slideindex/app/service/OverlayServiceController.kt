package com.slideindex.app.service

import android.content.Context
import com.slideindex.app.settings.SettingsRepository
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.overlay.FloatingPointerAreaPreviewOverlay
import com.slideindex.app.overlay.LayoutPreviewContent
import com.slideindex.app.overlay.LayoutPreviewFocus
import com.slideindex.app.ui.navigation.toNavSide
import com.slideindex.app.ui.navigation.NavPermissionStates
import com.slideindex.app.util.PermissionHelper
import com.slideindex.app.util.SecureSettingsHelper
import com.slideindex.app.util.TaskManagerUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class OverlayServiceController(
    private val context: Context,
    private val permissionStates: NavPermissionStates,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository
) {
    private var lastPreviewDropNoticeMs = 0L

    fun sendPreviewIntent(
        action: String,
        content: LayoutPreviewContent = LayoutPreviewContent.TRIGGER_ONLY,
        focus: LayoutPreviewFocus? = null
    ) {
        if (!permissionStates.accessibilityGranted.value) return
        when (action) {
            OverlayService.ACTION_PREVIEW_START ->
                SlideIndexAccessibilityService.setPreviewMode(true, content, focus)
            OverlayService.ACTION_PREVIEW_STOP ->
                SlideIndexAccessibilityService.setPreviewMode(false)
        }
        val intent = Intent(context, OverlayService::class.java)
            .setAction(action)
            .putExtra(OverlayService.EXTRA_PREVIEW_CONTENT, content.name)
        if (focus != null) {
            intent.putExtra(OverlayService.EXTRA_PREVIEW_FOCUS_SIDE, focus.side.toNavSide())
            intent.putExtra(OverlayService.EXTRA_PREVIEW_HANDLE_ID, focus.handleId)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_SHOW_SWIPE_DISTANCES, focus.showSwipeDistances)
            intent.putExtra(OverlayService.EXTRA_PREVIEW_SHOW_PAIRED_GROUP, focus.showPairedGroup)
        }
        context.startService(intent)
    }

    /**
     * 滑条拖动期的实时预览通道：只构造 Intent 发给 :overlay 的 [OverlayService]。
     *
     * 设置界面跑在主进程，而浮层宿主在 :overlay，直连静态 instance 必然落空，
     * 因此所有浮层预览都必须经由这里跨进程送达。
     */
    fun sendPreviewExtras(action: String, configure: (Intent) -> Unit) {
        if (!permissionStates.accessibilityGranted.value) {
            notifyPreviewUnavailable(action)
            return
        }
        val intent = Intent(context, OverlayService::class.java).setAction(action)
        configure(intent)
        runCatching { context.startService(intent) }
            .onFailure { error ->
                Log.w(TAG, "startService 失败: $action", error)
                notifyPreviewUnavailable(action)
            }
    }

    /**
     * 预览发不出去时必须可见。
     *
     * 这类失败以前是静默的（`instance?.` 命中 null 直接丢弃），用户只会觉得"功能坏了"；
     * 现在至少留下一条日志，并按节流给用户一次提示。
     */
    private fun notifyPreviewUnavailable(action: String) {
        Log.w(TAG, "浮层预览不可用（无障碍未连接或 :overlay 服务无法启动）: $action")
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewDropNoticeMs < PREVIEW_DROP_NOTICE_INTERVAL_MS) return
        lastPreviewDropNoticeMs = now
        runCatching {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.preview_unavailable_hint),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** 悬浮指针"行程范围预览"：窗口建在 :overlay，跟手位置也由那边的边缘触摸驱动。 */
    fun setFloatingPointerAreaPreview(active: Boolean, sensitivity: Float? = null) {
        if (!active) FloatingPointerAreaPreviewOverlay.hide()
        sendPreviewExtras(OverlayService.ACTION_PREVIEW_FLOATING_AREA) { intent ->
            intent.putExtra(OverlayService.EXTRA_PREVIEW_FLOATING_ACTIVE, active)
            if (sensitivity != null) {
                intent.putExtra(OverlayService.EXTRA_PREVIEW_FLOATING_SENSITIVITY, sensitivity)
            }
        }
    }

    /**
     * 设置里的小组件编辑器点"预览"：让 :overlay 把小组件面板浮出来。
     *
     * AppWidgetHostView 只能在 :overlay 创建（共享 hostId 会被抢订阅），
     * 编辑器进程渲染不了真身，所以预览走这条通道。
     */
    fun showWidgetPanelPreview() {
        sendPreviewExtras(OverlayService.ACTION_PREVIEW_WIDGET_PANEL) { }
    }

    fun stopPreviewOnPause() {
        sendPreviewIntent(OverlayService.ACTION_PREVIEW_STOP)
        setFloatingPointerAreaPreview(false)
    }

    fun refreshPermissionState() {
        permissionStates.overlayGranted.value = PermissionHelper.canDrawOverlays(context)
        permissionStates.notificationGranted.value = PermissionHelper.hasNotificationPermission(context)
        permissionStates.usageAccessGranted.value = PermissionHelper.hasUsageAccess(context)
        permissionStates.shizukuGranted.value = TaskManagerUtil.hasPrivilegedAccess()
        permissionStates.accessibilityGranted.value =
            PermissionHelper.isAccessibilityServiceEnabled(context)
        permissionStates.batteryOptimizationExempt.value =
            PermissionHelper.isBatteryOptimizationExempt(context)
        permissionStates.writeSecureSettingsGranted.value =
            SecureSettingsHelper.hasWriteSecureSettings(context)
        val listenerEnabled = com.slideindex.app.util.MediaSessionHelper.isNotificationListenerEnabled(context)
        permissionStates.notificationListenerEnabled.value = listenerEnabled
        if (listenerEnabled) {
            com.slideindex.app.util.MediaSessionHelper.ensureNotificationListenerConnected(context)
        }
        if (TaskManagerUtil.hasPrivilegedAccess()) {
            TaskManagerUtil.warmUpPrivilegedBackend()
        }
    }

    fun refreshServiceState() {
        scope.launch {
            OverlayServiceLifecycle.syncFromSettings(
                context,
                settingsRepository,
                accessibilityRecoverRetries = true,
            )
            permissionStates.accessibilityGranted.value =
                PermissionHelper.isAccessibilityServiceEnabled(context)
        }
    }

    private companion object {
        const val TAG = "OverlayServiceController"

        /** 同一个提示 10s 内只弹一次，避免拖动滑条时刷屏。 */
        const val PREVIEW_DROP_NOTICE_INTERVAL_MS = 10_000L
    }
}
