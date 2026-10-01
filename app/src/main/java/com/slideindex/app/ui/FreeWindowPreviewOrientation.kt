package com.slideindex.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper

/**
 * 小窗编辑期间的方向会话。
 *
 * 为什么不用「进页记原值、onDispose 还原」那种写法：我们的 App 外壳会随方向换布局
 * （竖屏底栏 / 横屏侧栏，见 `mainAppPrefersNavigationRail`），转屏会让组合树重建，
 * 那种写法在重建时会把「还原」当成新指令下发，于是方向来回跳、返回时又闪一次横屏。
 *
 * 这里改成：只在目标方向真的变化时下发一次（幂等），关闭编辑层时才延迟还原；
 * 延迟期内编辑层若重新挂上，这次还原会被取消。
 */
object FreeWindowPreviewOrientationSession {
    private const val RELEASE_DELAY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** null = 编辑层未打开；true = 正在编辑横屏预置；false = 正在编辑竖屏预置。 */
    private var editingLandscape: Boolean? = null
    private var pendingRelease: Runnable? = null

    @SuppressLint("SourceLockedOrientationActivity")
    fun apply(landscape: Boolean, activity: Activity?) {
        cancelPendingRelease()
        if (editingLandscape == landscape) return
        editingLandscape = landscape
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    /** 编辑层关闭后延迟还原方向；延迟内重新打开编辑层会取消它。 */
    fun scheduleRelease(activity: Activity?) {
        cancelPendingRelease()
        val release = Runnable {
            pendingRelease = null
            if (editingLandscape == null) return@Runnable
            editingLandscape = null
            if (activity?.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        pendingRelease = release
        mainHandler.postDelayed(release, RELEASE_DELAY_MS)
    }

    private fun cancelPendingRelease() {
        pendingRelease?.let { mainHandler.removeCallbacks(it) }
        pendingRelease = null
    }
}
