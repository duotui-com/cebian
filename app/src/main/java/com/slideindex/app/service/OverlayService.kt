package com.slideindex.app.service

import com.slideindex.app.di.AppDependencies
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.MainActivity
import com.slideindex.app.R
import com.slideindex.app.overlay.LayoutPreviewContent
import com.slideindex.app.overlay.LayoutPreviewFocus
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.overlay.FloatingPointerAreaPreviewOverlay
import com.slideindex.app.gesture.GestureAngle
import com.slideindex.app.gesture.GestureAngles
import com.slideindex.app.gesture.TriggerHandleDesignCodec
import com.slideindex.app.shake.FaceDownGestureHost
import com.slideindex.app.shake.ShakeGestureHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service for persistent notification and settings sync.
 * Edge overlays are hosted by [SlideIndexAccessibilityService] (SideGesture-style).
 */
@dagger.hilt.android.AndroidEntryPoint
class OverlayService : LifecycleService() {

    @javax.inject.Inject lateinit var deps: AppDependencies
    @javax.inject.Inject lateinit var shakeGestureHost: ShakeGestureHost
    @javax.inject.Inject lateinit var faceDownGestureHost: FaceDownGestureHost

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground()
        GestureToggleTileWarmup.requestListening(this, "overlayService")
        shakeGestureHost.start(lifecycleScope)
        faceDownGestureHost.start(lifecycleScope)
        // 立刻回一帧状态：主进程的看门狗"唤醒 overlay"之后靠这帧判断进程是否真的活着。
        com.slideindex.app.overlay.OverlayStatePort.publish(this, "serviceStart")
        lifecycleScope.launch {
            OverlayServiceLifecycle.recoverAccessibilityBinding(
                this@OverlayService,
                deps.settingsRepository.readSnapshot(),
            )
        }
        startAccessibilityWatchdog()
    }

    private fun startAccessibilityWatchdog() {
        lifecycleScope.launch {
            while (isActive) {
                delay(ACCESSIBILITY_WATCHDOG_INTERVAL_MS)
                // 心跳：主进程以此判断 overlay 进程是否还活着（镜像新鲜度）。
                com.slideindex.app.overlay.OverlayStatePort.publish(this@OverlayService, "heartbeat")
                val settings = deps.settingsRepository.settings.first()
                if (!settings.serviceEnabled) {
                    AccessibilityRecoverNotifier.clearOffline(this@OverlayService)
                    continue
                }
                val outcome = OverlayServiceLifecycle.recoverAccessibilityBinding(
                    this@OverlayService,
                    settings,
                )
                if (outcome == AccessibilityRecoverOutcome.Failed) {
                    // 设置里显示已开启、实际却连不上（覆盖安装或被系统杀掉后系统拒绝重绑）：
                    // 静默重绑做不到时，得明确告诉用户点哪里恢复，不能只写日志。
                    AccessibilityRecoverNotifier.notifyOffline(this@OverlayService)
                } else {
                    AccessibilityRecoverNotifier.clearOffline(this@OverlayService)
                }
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // startForegroundService() requires startForeground() on every delivery, not only in onCreate().
        promoteToForeground()
        // 每一次被"唤醒"都回一帧状态：主进程看门狗靠这帧判断 :overlay 是否活着。
        com.slideindex.app.overlay.OverlayStatePort.publish(this, "serviceCommand")
        when (intent?.action) {
            ACTION_RELOAD_APPS -> SlideIndexAccessibilityService.reloadApps()
            ACTION_PREVIEW_START -> {
                val content = intent.getStringExtra(EXTRA_PREVIEW_CONTENT)
                    ?.let { runCatching { LayoutPreviewContent.valueOf(it) }.getOrNull() }
                    ?: LayoutPreviewContent.TRIGGER_ONLY
                val sideRaw = intent.getStringExtra(EXTRA_PREVIEW_FOCUS_SIDE)
                val focus = intent.parsePreviewFocus()
                when {
                    focus != null -> SlideIndexAccessibilityService.setPreviewMode(true, content, focus)
                    sideRaw == null -> SlideIndexAccessibilityService.setPreviewMode(true, content, null)
                    // Unknown side in extras: keep focus already applied in-process by OverlayServiceController.
                }
            }
            ACTION_PREVIEW_STOP -> SlideIndexAccessibilityService.setPreviewMode(false)
            // 设置页（主进程）拖动滑条时的实时预览：跨进程落到本进程后再驱动浮层。
            // 这些入口以前是主进程直连静态 instance，拆进程后必然落空，所以统一走 Intent。
            ACTION_PREVIEW_TRIGGER_HANDLE -> applyTriggerHandlePreview(intent)
            ACTION_PREVIEW_INDEX_HEIGHT -> applyIndexHeightPreview(intent)
            ACTION_PREVIEW_CORNER_ZONE -> applyCornerZonePreview(intent)
            ACTION_PREVIEW_GESTURE_ANGLES -> applyGestureAnglesPreview(intent)
            ACTION_PREVIEW_FLOATING_AREA -> applyFloatingPointerAreaPreview(intent)
            ACTION_PREVIEW_FLOAT_BALL_APPEARANCE -> applyFloatBallAppearancePreview(intent)
            ACTION_PREVIEW_FLOAT_BALL_POSITION_Y -> applyFloatBallPositionYPreview(intent)
            ACTION_PREVIEW_FLOAT_BALL_STRIP_ZONE ->
                SlideIndexAccessibilityService.setFloatBallStripZonePreview(
                    intent.getBooleanExtra(EXTRA_PREVIEW_ACTIVE, false)
                )
            ACTION_PREVIEW_WIDGET_PANEL ->
                // 设置里的小组件编辑器拿不到 AppWidgetHostView（宿主只在 :overlay），
                // 所以"预览真身"只能是：让 :overlay 把小组件面板浮出来。
                com.slideindex.app.overlay.WidgetPopupOverlayWindow.show(
                    context = this,
                    settings = deps.settingsRepository.readSnapshot(),
                )
            ACTION_PREVIEW_CLEAR -> SlideIndexAccessibilityService.clearOverlayLayoutPreview()
        }
        return START_STICKY
    }

    private fun applyTriggerHandlePreview(intent: Intent) {
        val side = intent.parsePreviewSide() ?: return
        val handleId = intent.getStringExtra(EXTRA_PREVIEW_HANDLE_ID) ?: return
        SlideIndexAccessibilityService.mergeTriggerHandleLayoutPreview(
            side = side,
            handleId = handleId,
            edgeWidthDp = intent.floatExtraOrNull(EXTRA_PREVIEW_EDGE_WIDTH_DP),
            topFraction = intent.floatExtraOrNull(EXTRA_PREVIEW_TOP_FRACTION),
            bottomFraction = intent.floatExtraOrNull(EXTRA_PREVIEW_BOTTOM_FRACTION),
            shortSwipeDistanceDp = intent.floatExtraOrNull(EXTRA_PREVIEW_SHORT_SWIPE_DP),
            longSwipeDistanceDp = intent.floatExtraOrNull(EXTRA_PREVIEW_LONG_SWIPE_DP),
            design = intent.getStringExtra(EXTRA_PREVIEW_DESIGN)
                ?.takeIf { it.isNotBlank() }
                ?.let { TriggerHandleDesignCodec.decode(it) },
        )
    }

    private fun applyIndexHeightPreview(intent: Intent) {
        val fraction = intent.floatExtraOrNull(EXTRA_PREVIEW_INDEX_FRACTION)
        if (fraction == null) {
            SlideIndexAccessibilityService.clearIndexHeightPreview()
        } else {
            SlideIndexAccessibilityService.previewIndexHeightFraction(fraction)
        }
    }

    private fun applyCornerZonePreview(intent: Intent) {
        val active = intent.getBooleanExtra(EXTRA_PREVIEW_CORNER_ACTIVE, false)
        SlideIndexAccessibilityService.setCornerZonePreviewActive(active)
        if (!active) return
        SlideIndexAccessibilityService.applyCornerZonePreviewDimensions(
            verticalEdgeWidthDp = intent.floatExtraOrNull(EXTRA_PREVIEW_CORNER_V_WIDTH) ?: 0f,
            verticalEdgeHeightDp = intent.floatExtraOrNull(EXTRA_PREVIEW_CORNER_V_HEIGHT) ?: 0f,
            horizontalEdgeWidthDp = intent.floatExtraOrNull(EXTRA_PREVIEW_CORNER_H_WIDTH) ?: 0f,
            horizontalEdgeHeightDp = intent.floatExtraOrNull(EXTRA_PREVIEW_CORNER_H_HEIGHT) ?: 0f,
        )
    }

    private fun applyGestureAnglesPreview(intent: Intent) {
        SlideIndexAccessibilityService.setGestureAnglesPreview(
            intent.getFloatArrayExtra(EXTRA_PREVIEW_GESTURE_ANGLES)?.toGestureAnglesOrNull()
        )
    }

    private fun applyFloatingPointerAreaPreview(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_PREVIEW_FLOATING_ACTIVE, false)) {
            FloatingPointerAreaPreviewOverlay.hide()
            return
        }
        FloatingPointerAreaPreviewOverlay.show(deps)
        // 未带 extra = 不改灵敏度；NaN = 松手，清掉临时值回到已保存设置。
        when (val raw = intent.floatExtraOrNull(EXTRA_PREVIEW_FLOATING_SENSITIVITY)) {
            null -> Unit
            else -> FloatingPointerAreaPreviewOverlay.previewSensitivity(
                raw.takeUnless { it.isNaN() }
            )
        }
    }

    private fun applyFloatBallAppearancePreview(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_PREVIEW_CLEAR_RESTORE, false)) {
            SlideIndexAccessibilityService.clearFloatBallAppearancePreviewRestore()
            return
        }
        if (!intent.getBooleanExtra(EXTRA_PREVIEW_ACTIVE, true)) {
            SlideIndexAccessibilityService.endFloatBallAppearancePreview(
                intent.getBooleanExtra(EXTRA_PREVIEW_RESTORE, false)
            )
            return
        }
        SlideIndexAccessibilityService.previewFloatBallAppearance(
            sizeDp = intent.floatExtraOrNull(EXTRA_PREVIEW_SIZE_DP),
            opacity = intent.floatExtraOrNull(EXTRA_PREVIEW_OPACITY),
            visibleFraction = intent.floatExtraOrNull(EXTRA_PREVIEW_VISIBLE_FRACTION),
            lineHeightFraction = intent.floatExtraOrNull(EXTRA_PREVIEW_LINE_HEIGHT_FRACTION),
            lineWidthFraction = intent.floatExtraOrNull(EXTRA_PREVIEW_LINE_WIDTH_FRACTION),
            lineOpacity = intent.floatExtraOrNull(EXTRA_PREVIEW_LINE_OPACITY),
        )
    }

    private fun applyFloatBallPositionYPreview(intent: Intent) {
        val fraction = intent.floatExtraOrNull(EXTRA_PREVIEW_POSITION_Y_FRACTION)
        if (fraction != null) {
            SlideIndexAccessibilityService.previewFloatBallPositionYFraction(fraction)
            return
        }
        if (intent.getBooleanExtra(EXTRA_PREVIEW_CLEAR_RESTORE, false)) {
            SlideIndexAccessibilityService.clearFloatBallPositionYPreviewRestore()
            return
        }
        SlideIndexAccessibilityService.endFloatBallPositionYPreview(
            intent.getBooleanExtra(EXTRA_PREVIEW_RESTORE, false)
        )
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        shakeGestureHost.stop()
        faceDownGestureHost.stop()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_RELOAD_APPS = "com.slideindex.app.RELOAD_APPS"
        const val ACTION_PREVIEW_START = "com.slideindex.app.PREVIEW_START"
        const val ACTION_PREVIEW_STOP = "com.slideindex.app.PREVIEW_STOP"

        /** 设置页滑条拖动期的实时预览：主进程 → :overlay 的跨进程入口。 */
        const val ACTION_PREVIEW_TRIGGER_HANDLE = "com.slideindex.app.PREVIEW_TRIGGER_HANDLE"
        const val ACTION_PREVIEW_INDEX_HEIGHT = "com.slideindex.app.PREVIEW_INDEX_HEIGHT"
        const val ACTION_PREVIEW_CORNER_ZONE = "com.slideindex.app.PREVIEW_CORNER_ZONE"
        const val ACTION_PREVIEW_GESTURE_ANGLES = "com.slideindex.app.PREVIEW_GESTURE_ANGLES"
        const val ACTION_PREVIEW_FLOATING_AREA = "com.slideindex.app.PREVIEW_FLOATING_AREA"
        const val ACTION_PREVIEW_FLOAT_BALL_APPEARANCE = "com.slideindex.app.PREVIEW_FLOAT_BALL_APPEARANCE"
        const val ACTION_PREVIEW_FLOAT_BALL_POSITION_Y = "com.slideindex.app.PREVIEW_FLOAT_BALL_POSITION_Y"
        const val ACTION_PREVIEW_FLOAT_BALL_STRIP_ZONE = "com.slideindex.app.PREVIEW_FLOAT_BALL_STRIP_ZONE"
        const val ACTION_PREVIEW_WIDGET_PANEL = "com.slideindex.app.PREVIEW_WIDGET_PANEL"
        const val ACTION_PREVIEW_CLEAR = "com.slideindex.app.PREVIEW_CLEAR"

        const val EXTRA_PREVIEW_SIDE = "preview_side"
        const val EXTRA_PREVIEW_EDGE_WIDTH_DP = "preview_edge_width_dp"
        const val EXTRA_PREVIEW_TOP_FRACTION = "preview_top_fraction"
        const val EXTRA_PREVIEW_BOTTOM_FRACTION = "preview_bottom_fraction"
        const val EXTRA_PREVIEW_SHORT_SWIPE_DP = "preview_short_swipe_dp"
        const val EXTRA_PREVIEW_LONG_SWIPE_DP = "preview_long_swipe_dp"
        const val EXTRA_PREVIEW_DESIGN = "preview_design"
        const val EXTRA_PREVIEW_INDEX_FRACTION = "preview_index_fraction"
        const val EXTRA_PREVIEW_CORNER_ACTIVE = "preview_corner_active"
        const val EXTRA_PREVIEW_CORNER_V_WIDTH = "preview_corner_v_width"
        const val EXTRA_PREVIEW_CORNER_V_HEIGHT = "preview_corner_v_height"
        const val EXTRA_PREVIEW_CORNER_H_WIDTH = "preview_corner_h_width"
        const val EXTRA_PREVIEW_CORNER_H_HEIGHT = "preview_corner_h_height"
        const val EXTRA_PREVIEW_GESTURE_ANGLES = "preview_gesture_angles"
        const val EXTRA_PREVIEW_FLOATING_ACTIVE = "preview_floating_active"
        const val EXTRA_PREVIEW_FLOATING_SENSITIVITY = "preview_floating_sensitivity"
        const val EXTRA_PREVIEW_ACTIVE = "preview_active"
        const val EXTRA_PREVIEW_RESTORE = "preview_restore"
        const val EXTRA_PREVIEW_CLEAR_RESTORE = "preview_clear_restore"
        const val EXTRA_PREVIEW_SIZE_DP = "preview_size_dp"
        const val EXTRA_PREVIEW_OPACITY = "preview_opacity"
        const val EXTRA_PREVIEW_VISIBLE_FRACTION = "preview_visible_fraction"
        const val EXTRA_PREVIEW_LINE_HEIGHT_FRACTION = "preview_line_height_fraction"
        const val EXTRA_PREVIEW_LINE_WIDTH_FRACTION = "preview_line_width_fraction"
        const val EXTRA_PREVIEW_LINE_OPACITY = "preview_line_opacity"
        const val EXTRA_PREVIEW_POSITION_Y_FRACTION = "preview_position_y_fraction"

        const val EXTRA_PREVIEW_CONTENT = "preview_content"
        const val EXTRA_PREVIEW_FOCUS_SIDE = "preview_focus_side"
        const val EXTRA_PREVIEW_HANDLE_ID = "preview_focus_handle_id"
        const val EXTRA_PREVIEW_SHOW_SWIPE_DISTANCES = "preview_show_swipe_distances"
        const val EXTRA_PREVIEW_SHOW_PAIRED_GROUP = "preview_show_paired_group"

        internal fun Intent.parsePreviewFocus(): LayoutPreviewFocus? {
            val sideRaw = getStringExtra(EXTRA_PREVIEW_FOCUS_SIDE) ?: return null
            val handleId = getStringExtra(EXTRA_PREVIEW_HANDLE_ID) ?: return null
            val side = when (sideRaw.uppercase()) {
                "LEFT" -> PanelSide.LEFT
                "RIGHT" -> PanelSide.RIGHT
                "BOTTOM" -> PanelSide.BOTTOM
                "TOP" -> PanelSide.TOP
                else -> return null
            }
            return LayoutPreviewFocus(
                side = side,
                handleId = handleId,
                showSwipeDistances = getBooleanExtra(EXTRA_PREVIEW_SHOW_SWIPE_DISTANCES, false),
                showPairedGroup = getBooleanExtra(EXTRA_PREVIEW_SHOW_PAIRED_GROUP, false)
            )
        }

        @Volatile
        var foregroundPackage: String? = null

        @Volatile
        var gestureForegroundPackage: String? = null

        fun captureGestureForegroundPackage() {
            gestureForegroundPackage = foregroundPackage
        }

        private const val CHANNEL_ID = "slide_index_service"
        private const val NOTIFICATION_ID = 1001
        private const val ACCESSIBILITY_WATCHDOG_INTERVAL_MS = 25_000L
    }
}

/**
 * 只在"确实带了该 extra"时返回数值；未带表示"这一项沿用当前值"。
 *
 * 预览参数里 0f 是合法取值（例如触钮顶到 0%），所以不能用 0 当"未设置"。
 */
internal fun Intent.floatExtraOrNull(key: String): Float? =
    if (hasExtra(key)) getFloatExtra(key, 0f) else null

internal fun Intent.parsePreviewSide(): PanelSide? =
    when (getStringExtra(OverlayService.EXTRA_PREVIEW_SIDE)?.uppercase()) {
        "LEFT" -> PanelSide.LEFT
        "RIGHT" -> PanelSide.RIGHT
        "BOTTOM" -> PanelSide.BOTTOM
        "TOP" -> PanelSide.TOP
        else -> null
    }

/** 16 个 float = 左右下上四条边各 4 个角度点；长度不足或数值非法则返回 null（等于清预览）。 */
internal fun FloatArray.toGestureAnglesOrNull(): GestureAngles? {
    if (size < 16) return null
    return runCatching {
        GestureAngles(
            left = GestureAngle(this[0], this[1], this[2], this[3]),
            right = GestureAngle(this[4], this[5], this[6], this[7]),
            bottom = GestureAngle(this[8], this[9], this[10], this[11]),
            top = GestureAngle(this[12], this[13], this[14], this[15]),
        )
    }.getOrNull()
}
