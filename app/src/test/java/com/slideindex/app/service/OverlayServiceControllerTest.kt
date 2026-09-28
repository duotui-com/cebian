package com.slideindex.app.service

import androidx.compose.runtime.mutableStateOf
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.settings.testSettingsRepository
import com.slideindex.app.ui.navigation.NavPermissionStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class OverlayServiceControllerTest {

    @Test
    fun layoutPreview_doesNotStartServiceWhenAccessibilityDisabled() {
        val app = RuntimeEnvironment.getApplication()
        val controller = controller(app, accessibilityGranted = false)

        controller.startLayoutPreview()

        val started = Shadows.shadowOf(app).nextStartedService
        assertNull("预览不再经由 Intent 启动服务", started)
    }

    @Test
    fun layoutPreview_staysInProcessWhenAccessibilityGranted() {
        val app = RuntimeEnvironment.getApplication()
        val controller = controller(app, accessibilityGranted = true)

        controller.startLayoutPreview()
        controller.stopLayoutPreview()

        val started = Shadows.shadowOf(app).nextStartedService
        assertNull("预览通道不再启动任何服务", started)
    }

    @Test
    fun refreshPermissionState_reflectsRuntimePermissionSnapshot() {
        val app = RuntimeEnvironment.getApplication()
        val states = NavPermissionStates(
            overlayGranted = mutableStateOf(false),
            notificationGranted = mutableStateOf(false),
            usageAccessGranted = mutableStateOf(false),
            shizukuGranted = mutableStateOf(false),
            accessibilityGranted = mutableStateOf(false),
            batteryOptimizationExempt = mutableStateOf(false),
            writeSecureSettingsGranted = mutableStateOf(false),
            notificationListenerEnabled = mutableStateOf(false),
        )
        val controller = OverlayServiceController(
            context = app,
            permissionStates = states,
            scope = CoroutineScope(Dispatchers.Unconfined),
            settingsRepository = testSettingsRepository(app),
        )

        controller.refreshPermissionState()

        assertTrue(states.notificationGranted.value)
    }

    private fun controller(
        context: android.content.Context,
        accessibilityGranted: Boolean,
    ): OverlayServiceController =
        OverlayServiceController(
            context = context,
            permissionStates = NavPermissionStates(
                overlayGranted = mutableStateOf(false),
                notificationGranted = mutableStateOf(true),
                usageAccessGranted = mutableStateOf(false),
                shizukuGranted = mutableStateOf(false),
                accessibilityGranted = mutableStateOf(accessibilityGranted),
                batteryOptimizationExempt = mutableStateOf(false),
                writeSecureSettingsGranted = mutableStateOf(false),
                notificationListenerEnabled = mutableStateOf(false),
            ),
            scope = CoroutineScope(Dispatchers.Unconfined),
            settingsRepository = testSettingsRepository(context),
        )
}
