package com.slideindex.app.util

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.isLandscapeConfiguration
import com.slideindex.app.settings.resolvedFreeWindowLayout
import com.slideindex.app.settings.resolvedFreeWindowMode
import com.slideindex.app.settings.usesNubiaFreeformIdentifier

object FreeWindowLauncher {
    private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
    private const val NUBIA_FREEFORM_INTENT_IDENTIFIER = "_WindowReply"

    fun launch(context: Context, intent: Intent, settings: AppSettings, fullscreen: Boolean) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!fullscreen && settings.freeWindowEnabled) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (fullscreen || !settings.freeWindowEnabled) {
            context.startActivity(intent)
            return
        }

        val mode = settings.resolvedFreeWindowMode()
        if (mode.usesNubiaFreeformIdentifier()) {
            launchNubiaFreeform(context, intent)
            return
        }

        val bundle = launchOptionsBundle(context, settings) ?: Bundle()
        runCatching {
            context.startActivity(intent, bundle)
        }.onFailure { error ->
            android.util.Log.e("FreeWindowLauncher", "startActivity failed", error)
        }
    }

    private fun launchNubiaFreeform(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intent.identifier = NUBIA_FREEFORM_INTENT_IDENTIFIER
        }
        runCatching {
            context.startActivity(intent, Bundle())
        }.onFailure { error ->
            android.util.Log.e("FreeWindowLauncher", "nubia freeform startActivity failed", error)
        }
    }

    fun launchOptionsBundle(context: Context, settings: AppSettings): Bundle? {
        if (!settings.freeWindowEnabled) return null
        val options = ActivityOptions.makeBasic()
        val mode = settings.resolvedFreeWindowMode().windowingMode
        applyWindowingMode(options, mode)
        options.setLaunchBounds(launchBounds(context, settings))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val backgroundStartMode = when {
                Build.VERSION.SDK_INT >= 36 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                else -> @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            options.pendingIntentBackgroundActivityStartMode = backgroundStartMode
        }
        val bundle = options.toBundle() ?: Bundle()
        if (bundle.getInt(KEY_WINDOWING_MODE, -1) == -1) {
            bundle.putInt(KEY_WINDOWING_MODE, mode)
        }
        return bundle
    }

    fun launchBounds(context: Context, settings: AppSettings): Rect {
        val metrics = context.resources.displayMetrics
        // 竖屏 / 横屏各有一套预置，这里按当前显示方向取用，避免横屏沿用竖屏比例导致窗口过大。
        val layout = settings.resolvedFreeWindowLayout(context.isLandscapeConfiguration())
        val widthPx = (metrics.widthPixels * layout.widthFraction).toInt()
            .coerceAtLeast(1)
        val heightPx = (metrics.heightPixels * layout.heightFraction).toInt()
            .coerceAtLeast(1)
        val leftPx = (metrics.widthPixels * layout.leftFraction).toInt()
            .coerceIn(0, (metrics.widthPixels - widthPx).coerceAtLeast(0))
        val topPx = (metrics.heightPixels * layout.topFraction).toInt()
            .coerceIn(0, (metrics.heightPixels - heightPx).coerceAtLeast(0))
        return Rect(leftPx, topPx, leftPx + widthPx, topPx + heightPx)
    }

    private fun applyWindowingMode(options: ActivityOptions, mode: Int) {
        try {
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType,
            )
            method.invoke(options, mode)
        } catch (_: Exception) {
            // Hidden API unavailable; bundle fallback applied in launch().
        }
    }
}
