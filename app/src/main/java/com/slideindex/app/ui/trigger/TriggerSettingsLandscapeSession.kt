package com.slideindex.app.ui.trigger

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 触钮设置页是否处于横屏独立布局编辑态（跨子页面导航共享）。
 *
 * 方向还原只在「离开整个触钮编辑流」时发生：
 * - [scheduleReleaseForExit] 延迟执行，真正执行前先看 [activeScreenCount]：只要还有触钮页面在场就放弃；
 * - [onEditorScreenEnter] / [onEditorScreenExit] 由集合页与各子页登记。
 *
 * 这样不管转屏 / 外壳换布局导致的销毁重建顺序如何，都不会把刚锁上的横屏又解开。
 */
object TriggerSettingsLandscapeSession {
    private const val TAG = "TriggerLandscape"
    private const val RELEASE_DELAY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRelease: Runnable? = null

    /** 当前还挂着的触钮页面数量（集合页 + 各子页）。 */
    private var activeScreenCount = 0

    var active: Boolean = false

    /** null = 跟随系统方向；非 null = 用户手动指定展示竖/横屏触钮。 */
    var manualLandscapeDisplay: Boolean? = null

    fun displayLandscape(systemLandscape: Boolean): Boolean =
        manualLandscapeDisplay ?: systemLandscape

    // ---- 页面登记 ----

    fun onEditorScreenEnter() {
        activeScreenCount++
        cancelPendingRelease()
        Log.i(TAG, "enter count=$activeScreenCount active=$active manual=$manualLandscapeDisplay")
    }

    fun onEditorScreenExit(activity: Activity?) {
        activeScreenCount = (activeScreenCount - 1).coerceAtLeast(0)
        Log.i(TAG, "exit count=$activeScreenCount active=$active manual=$manualLandscapeDisplay")
        if (activeScreenCount == 0) {
            scheduleReleaseForExit(activity)
        }
    }

    // ---- 方向 ----

    @SuppressLint("SourceLockedOrientationActivity")
    fun lockLandscapeOrientation(activity: Activity) {
        // 仍在横屏编辑流里：撤销上一次待执行的还原
        cancelPendingRelease()
        Log.i(TAG, "lock landscape count=$activeScreenCount from ${callerHint()}")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun releaseOrientationLock(activity: Activity) {
        Log.i(TAG, "release->UNSPECIFIED count=$activeScreenCount from ${callerHint()}")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** 主动释放（顶部返回按钮）。 */
    fun releaseForExit(activity: Activity?) {
        cancelPendingRelease()
        active = false
        manualLandscapeDisplay = null
        Log.i(TAG, "releaseForExit count=$activeScreenCount from ${callerHint()}")
        activity?.let(::releaseOrientationLock)
    }

    /**
     * 离开触钮编辑流时排一次延迟释放（覆盖手势返回、系统返回等不走 onBack 回调的路径）。
     *
     * 执行前会检查 [activeScreenCount]：转屏 / 换布局把路由销毁重建时新页面已经重新登记，
     * 这次释放就会被放弃。
     */
    fun scheduleReleaseForExit(activity: Activity?) {
        cancelPendingRelease()
        val release = Runnable {
            pendingRelease = null
            if (activeScreenCount > 0) {
                Log.i(TAG, "delayed release skipped: count=$activeScreenCount")
                return@Runnable
            }
            if (!active && manualLandscapeDisplay == null) {
                Log.i(TAG, "delayed release skipped: idle")
                return@Runnable
            }
            Log.i(TAG, "delayed release fired")
            releaseForExit(activity)
        }
        pendingRelease = release
        Log.i(TAG, "schedule release ${RELEASE_DELAY_MS}ms count=$activeScreenCount")
        mainHandler.postDelayed(release, RELEASE_DELAY_MS)
    }

    /** 撤销上一次 [scheduleReleaseForExit] 排下的延迟释放。 */
    fun cancelPendingRelease() {
        pendingRelease?.let { mainHandler.removeCallbacks(it) }
        pendingRelease = null
    }

    fun setDisplayLandscape(landscape: Boolean, activity: Activity?) {
        manualLandscapeDisplay = landscape
        updateActive(landscape, activity)
    }

    fun updateActive(landscape: Boolean, activity: Activity?) {
        // 仍处于横屏编辑态（例如转屏 / 外壳换布局导致路由重建）：撤销上一次待执行的释放
        if (landscape) cancelPendingRelease()
        if (active == landscape) return
        val wasActive = active
        active = landscape
        if (activity == null) return
        when {
            landscape -> lockLandscapeOrientation(activity)
            wasActive -> releaseOrientationLock(activity)
        }
    }

    /** 临时诊断用：打印最近几层调用点，定位是谁改了方向。 */
    private fun callerHint(): String = Throwable().stackTrace
        .drop(1)
        .take(4)
        .joinToString("<") { it.className.substringAfterLast('.') + "." + it.methodName }
}
