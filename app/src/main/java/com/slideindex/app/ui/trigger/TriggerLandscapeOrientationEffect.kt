package com.slideindex.app.ui.trigger

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** 横屏触钮子页保持横屏方向。 */
@Composable
fun TriggerLandscapeOrientationEffect(landscapeEditing: Boolean) {
    val activity = LocalActivity.current

    // 子页在场登记：只要还有触钮页面挂着，离场时的延迟释放就会被放弃，
    // 因此转屏 / 换外壳布局引起的销毁重建不会把方向解锁。
    DisposableEffect(activity) {
        TriggerSettingsLandscapeSession.onEditorScreenEnter()
        onDispose { TriggerSettingsLandscapeSession.onEditorScreenExit(activity) }
    }

    if (!landscapeEditing) return
    if (activity == null) return

    DisposableEffect(activity) {
        TriggerSettingsLandscapeSession.lockLandscapeOrientation(activity)
        onDispose { }
    }
}
