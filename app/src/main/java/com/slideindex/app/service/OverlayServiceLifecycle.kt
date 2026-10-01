package com.slideindex.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.SystemClock
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.overlay.OverlayStatePort
import com.slideindex.app.util.AppProcess
import com.slideindex.app.util.PermissionHelper
import com.slideindex.app.util.SecureSettingsHelper
import com.slideindex.app.util.ServiceEnabledStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** 根据 [serviceEnabled] 与权限状态启停 [OverlayService]（磁贴、快捷方式、开机恢复等场景复用）。 */
object OverlayServiceLifecycle {
    /**
     * 进程最早期（`Application.onCreate`、首帧之前）就把常驻服务拉起来并前台化。
     *
     * 覆盖安装 / 被系统重启后，AMS 会在进程起来约 1 秒时就要求 OverlayService 在 **5 秒内**
     * 调 `startForeground()`；而这一刻主线程正被 MainActivity 的首次组合 / 图标装载占着
     * （冷启动、dex 还没优化时尤甚），服务消息要排队到 5 秒之后才轮到——真机打点：进程 +0.05s 起、
     * 服务 onCreate 拖到 +5.1~5.3s，正好压在超时线上，AMS 随即抛
     * `ForegroundServiceDidNotStartInTimeException` 杀进程。
     *
     * 所以这里不读 settings Flow、不做任何重活，只用最便宜的镜像 + 权限检查，抢在首帧之前让它先
     * 前台化；服务前台上线后计时器即被清除，之后系统再重启它也不会超时。
     */
    fun warmStartEarly(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val mirrorEnabled = ServiceEnabledStore.read(appContext)
            val notificationGranted = PermissionHelper.hasNotificationPermission(appContext)
            // 不查无障碍开关：覆盖安装后系统会在进程刚起来时把无障碍条目临时摘掉（实测 t+81ms
            // 读到 a11y=false，而系统设置里是开的），而 OverlayService.onCreate 自己就会走
            // recoverAccessibilityBinding 重新绑定——这里卡 a11y 等于在最需要抢先的那一瞬间放弃。
            if (!mirrorEnabled) return
            if (!notificationGranted) return
            appContext.startForegroundService(Intent(appContext, OverlayService::class.java))
        }.onFailure { Log.w(TAG, "warmStartEarly failed", it) }
    }

    /**
     * 唤醒常驻交互进程：`OverlayService` 跑在 `:overlay`，把它拉起来就等于把整个
     * 无障碍 + 浮层宿主拉起来（其 onCreate 会自己走一遍恢复与状态上报）。
     *
     * 后台启动前台服务在部分机型上会被限制（`ForegroundServiceStartNotAllowedException`）；
     * 这里吞掉异常只记日志，真正的兜底是随后改写系统无障碍条目强制系统重绑。
     */
    fun wakeOverlayService(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, OverlayService::class.java),
            )
        }.onFailure { Log.w(TAG, "wakeOverlayService failed", it) }
    }

    suspend fun syncFromSettings(
        context: Context,
        settingsRepository: SettingsRepository,
        accessibilityRecoverRetries: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val settings = settingsRepository.settings.first()
        // 先把常驻服务拉起来/停掉：这一步直接决定"球多久出现"，不能排在可能带等待与重试的
        // 无障碍恢复之后（真机回归：安装后要等近一分钟球才出现，以前 5 秒内）。
        //
        // 注意：启动条件与停止条件是**不等价**的。覆盖安装后系统会短暂把无障碍条目摘掉
        // （实测进程起来 t+81ms 时 isAccessibilityServiceEnabled()=false，而系统设置里是开的），
        // 如果这时候用同一个条件去 stopService，就会把"刚被拉起、还在等 startForeground 的服务"
        // 直接打下去——AMS 会把这当成前台服务违规，抛
        // ForegroundServiceDidNotStartInTimeException 把进程杀掉（实测安装后连环崩，一次 16 秒里
        // 能崩 28 次）。
        // 所以：启动条件仍要求无障碍在位；停止只认"用户真的关了这个服务 / 通知权限没了"这类
        // 稳定条件，无障碍那点瞬时抖动交给服务自己 recoverAccessibilityBinding 去救。
        val notificationGranted = PermissionHelper.hasNotificationPermission(appContext)
        val shouldRun = settings.serviceEnabled &&
            PermissionHelper.isAccessibilityServiceEnabled(appContext) &&
            notificationGranted
        val shouldStop = !settings.serviceEnabled || !notificationGranted
        val serviceIntent = Intent(appContext, OverlayService::class.java)
        if (shouldRun) {
            runCatching { appContext.startForegroundService(serviceIntent) }
                .onFailure { Log.w(TAG, "start OverlayService failed", it) }
        } else if (shouldStop) {
            runCatching { appContext.stopService(serviceIntent) }
        }
        val outcome = if (accessibilityRecoverRetries) {
            recoverAccessibilityBindingWithRetries(appContext, settings)
        } else {
            recoverAccessibilityBinding(appContext, settings)
        }
        if (accessibilityRecoverRetries) {
            AccessibilityRecoverNotifier.maybeShowReopenHint(appContext, outcome, settings)
        }
    }

    /**
     * 恢复无障碍绑定与静默授权。
     * 若开启了「辅助功能防被杀」且拥有（或可通过 Shizuku 获取）WRITE_SECURE_SETTINGS，
     * 在任何门禁判定前优先自动写回系统设置，确保冷启动与强停后能自动秒开授权；
     * 只要手势总开关开着且系统里已开启无障碍但实际未连接，就尝试 nudge 抖动重绑。
     */
    suspend fun recoverAccessibilityBinding(context: Context, settings: AppSettings): AccessibilityRecoverOutcome {
        val appContextForState = context.applicationContext
        if (!settings.serviceEnabled) return AccessibilityRecoverOutcome.NotNeeded
        val appContext = context.applicationContext

        // 1. 覆盖安装 / 被系统杀过之后，AMS 通常会**自己**把无障碍服务绑回来（几秒级）——
        //    先给系统这个时间，期间不动系统设置。
        //    （教训：之前这里"乐观地"立刻改写无障碍设置，等于强制解绑再重绑，
        //     把系统那条快路径换成了带退避的慢路径 —— 真机实测安装后要等近一分钟球才出现，
        //     改回"先等系统"之前是 5 秒内出现。）
        if (!OverlayStatePort.isServiceConnected()) {
            awaitAccessibilityConnected(timeoutMs = SYSTEM_REBIND_GRACE_MS)
        }

        // 2. 只有"系统确实没绑上"，且用户开了「辅助功能防被杀」时，才主动写回设置兜底重绑。
        //    开关关着就不插手，避免无谓地把系统自己的重绑拖慢。
        if (settings.accessibilityKeepAliveEnabled && !OverlayStatePort.isServiceConnected()) {
            if (!SecureSettingsHelper.hasWriteSecureSettings(appContext)) {
                SecureSettingsHelper.grantViaShizuku(appContext)
            }
            if (SecureSettingsHelper.hasWriteSecureSettings(appContext)) {
                SecureSettingsHelper.ensureAccessibilityEnabled(appContext)
                // ensureAccessibilityEnabled 只保证「列表里有我」。覆盖安装或被系统杀过之后，
                // 列表里仍然有我、但系统已拒绝重绑（AccessibilityManagerService 记成 crashed），
                // 这种状态必须重写条目才会重新绑定，否则防被杀等于没生效。
                if (!OverlayStatePort.isServiceConnected()) {
                    tryNudgeAccessibilityRebindThrottled(appContext)
                    // 系统 bind 是异步的：等几秒看是否真的连上，否则会把刚发起的重绑误判成失败。
                    awaitAccessibilityConnected()
                }
            }
        }

        // 2. 检查系统是否处于启用状态
        if (!PermissionHelper.isAccessibilityServiceEnabled(appContext)) {
            return AccessibilityRecoverOutcome.NotNeeded
        }

        // 3. 检查当前实例是否已处于活跃连接状态
        if (OverlayStatePort.isServiceConnected()) {
            return AccessibilityRecoverOutcome.Connected
        }

        // 4. 已配置但未连接（假死或未绑定），尝试 nudge 抖动重绑
        if (tryNudgeAccessibilityRebindThrottled(appContext)) {
            Log.i(TAG, "recoverAccessibilityBinding: nudged accessibility rebind")
        } else {
            Log.w(
                TAG,
                "recoverAccessibilityBinding: enabled in settings but not connected; " +
                    "WRITE_SECURE_SETTINGS required for silent rebind",
            )
        }
        awaitAccessibilityConnected()
        return if (OverlayStatePort.isServiceConnected()) {
            AccessibilityRecoverOutcome.Connected
        } else {
            AccessibilityRecoverOutcome.Failed
        }
    }

    /** 覆盖安装或打开 App 后系统 bind 可能延迟，多试几次。 */
    suspend fun recoverAccessibilityBindingWithRetries(
        context: Context,
        settings: AppSettings,
        retryDelaysMs: LongArray = APP_LAUNCH_REBIND_DELAYS_MS,
    ): AccessibilityRecoverOutcome {
        var outcome = recoverAccessibilityBinding(context, settings)
        if (outcome != AccessibilityRecoverOutcome.Failed) return outcome
        for (delayMs in retryDelaysMs) {
            if (OverlayStatePort.isServiceConnected()) {
                return AccessibilityRecoverOutcome.Connected
            }
            delay(delayMs)
            outcome = recoverAccessibilityBinding(context, settings)
            if (outcome != AccessibilityRecoverOutcome.Failed) return outcome
        }
        return if (OverlayStatePort.isServiceConnected()) {
            AccessibilityRecoverOutcome.Connected
        } else {
            AccessibilityRecoverOutcome.Failed
        }
    }

    private suspend fun tryNudgeAccessibilityRebind(context: Context): Boolean {
        if (OverlayStatePort.isServiceConnected()) return true
        if (!PermissionHelper.isAccessibilityServiceEnabled(context)) return false
        if (!SecureSettingsHelper.hasWriteSecureSettings(context)) {
            SecureSettingsHelper.grantViaShizuku(context)
        }
        if (!SecureSettingsHelper.hasWriteSecureSettings(context)) return false
        return SecureSettingsHelper.nudgeAccessibilityRebind(context)
    }

    private val nudgeThrottle = NudgeThrottle()

    private suspend fun tryNudgeAccessibilityRebindThrottled(context: Context): Boolean {
        if (!nudgeThrottle.beginIfDue(SystemClock.elapsedRealtime())) return false
        return tryNudgeAccessibilityRebind(context)
    }

    /** 重绑写入之后等连接真正建立（系统 bind 是异步的，最多等 [timeoutMs]）。 */
    private suspend fun awaitAccessibilityConnected(
        timeoutMs: Long = REBIND_VERIFY_TIMEOUT_MS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (OverlayStatePort.isServiceConnected()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(REBIND_VERIFY_POLL_MS)
        }
    }

    private val APP_LAUNCH_REBIND_DELAYS_MS = longArrayOf(800L, 2_000L, 5_000L)

    private const val REBIND_VERIFY_TIMEOUT_MS = 5_000L
    /** 覆盖安装/被杀之后，先给系统这么多时间自己重绑无障碍（期间不动系统设置），避免把快路径换成慢路径。 */
    private const val SYSTEM_REBIND_GRACE_MS = 5_000L
    private const val REBIND_VERIFY_POLL_MS = 250L

    private const val TAG = "OverlayServiceLifecycle"
}
