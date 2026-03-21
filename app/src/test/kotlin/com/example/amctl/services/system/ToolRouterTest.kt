package com.example.amctl.services.system

import android.content.Context
import com.example.amctl.data.model.ScreenshotData
import com.example.amctl.services.accessibility.AccessibilityServiceProvider
import com.example.amctl.services.accessibility.ActionExecutor
import com.example.amctl.services.accessibility.ScreenInfo
import com.example.amctl.services.screencapture.ScreenCaptureProvider
import com.example.amctl.services.screencapture.ScreenshotEncoder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolRouterTest {
    private val context = mockk<Context>(relaxed = true)
    private val shizukuProvider = mockk<ShizukuProvider>(relaxed = true)
    private val systemScreenCapture = mockk<SystemScreenCapture>(relaxed = true)
    private val shellScreenCapture = mockk<ShellScreenCapture>(relaxed = true)
    private val systemInputInjector = mockk<SystemInputInjector>(relaxed = true)
    private val shellInputInjector = mockk<ShellInputInjector>(relaxed = true)
    private val appControllerImpl = AppControllerImpl(shizukuProvider)
    private val actionExecutor = mockk<ActionExecutor>(relaxed = true)
    private val screenCaptureProvider = mockk<ScreenCaptureProvider>(relaxed = true)
    private val accessibilityProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
    private val screenshotEncoder = mockk<ScreenshotEncoder>(relaxed = true)

    private fun buildRouter(): ToolRouter {
        every { accessibilityProvider.isReady() } returns true
        every { accessibilityProvider.getScreenInfo() } returns
            ScreenInfo(width = 1080, height = 1920, densityDpi = 420, orientation = "portrait")
        coEvery { screenCaptureProvider.captureScreenshot(any(), any(), any()) } returns
            Result.success(ScreenshotData(data = "", width = 1, height = 1))
        return ToolRouter(
            context = context,
            shizukuProvider = shizukuProvider,
            systemScreenCapture = systemScreenCapture,
            shellScreenCapture = shellScreenCapture,
            systemInputInjector = systemInputInjector,
            shellInputInjector = shellInputInjector,
            appControllerImpl = appControllerImpl,
            actionExecutor = actionExecutor,
            screenCaptureProvider = screenCaptureProvider,
            accessibilityProvider = accessibilityProvider,
            screenshotEncoder = screenshotEncoder,
        )
    }

    @Test
    fun `currentMode should be SYSTEM_API when shizuku and system injector are available`() {
        every { shizukuProvider.isAvailable() } returns true
        every { systemInputInjector.isAvailable } returns true

        val router = buildRouter()

        assertEquals(ToolRouter.Mode.SYSTEM_API, router.currentMode)
    }

    @Test
    fun `tap should fallback to shell input when system injector is unavailable`() = runTest {
        every { shizukuProvider.isAvailable() } returns true
        every { systemInputInjector.isAvailable } returns false
        every { shellInputInjector.tap(100f, 200f) } returns true

        val router = buildRouter()
        val result = router.tap(100f, 200f)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { shellInputInjector.tap(100f, 200f) }
        coVerify(exactly = 0) { actionExecutor.tap(any(), any()) }
    }

    @Test
    fun `tap should fallback to accessibility when shizuku is unavailable`() = runTest {
        every { shizukuProvider.isAvailable() } returns false
        coEvery { actionExecutor.tap(10f, 20f) } returns Result.success(Unit)

        val router = buildRouter()
        val result = router.tap(10f, 20f)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { actionExecutor.tap(10f, 20f) }
        verify(exactly = 0) { shellInputInjector.tap(any(), any()) }
    }

    @Test
    fun `tap should fallback to accessibility when v2 injection fails`() = runTest {
        every { shizukuProvider.isAvailable() } returns true
        every { systemInputInjector.isAvailable } returns false
        every { shellInputInjector.tap(30f, 40f) } returns false
        coEvery { actionExecutor.tap(30f, 40f) } returns Result.success(Unit)

        val router = buildRouter()
        val result = router.tap(30f, 40f)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { shellInputInjector.tap(30f, 40f) }
        coVerify(exactly = 1) { actionExecutor.tap(30f, 40f) }
    }
}
