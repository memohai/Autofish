package com.example.amctl.services.system

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import com.example.amctl.data.model.ScreenshotData
import com.example.amctl.services.accessibility.AccessibilityServiceProvider
import com.example.amctl.services.accessibility.ActionExecutor
import com.example.amctl.services.accessibility.ScreenInfo
import com.example.amctl.services.screencapture.ScreenCaptureProvider
import com.example.amctl.services.screencapture.ScreenshotEncoder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRouter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val shizukuProvider: ShizukuProvider,
        private val systemScreenCapture: SystemScreenCapture,
        private val shellScreenCapture: ShellScreenCapture,
        private val systemInputInjector: SystemInputInjector,
        private val shellInputInjector: ShellInputInjector,
        private val appControllerImpl: AppControllerImpl,
        private val actionExecutor: ActionExecutor,
        private val screenCaptureProvider: ScreenCaptureProvider,
        private val accessibilityProvider: AccessibilityServiceProvider,
        private val screenshotEncoder: ScreenshotEncoder,
    ) {
        enum class Mode { SYSTEM_API, SHELL_CMD, ACCESSIBILITY }

        val currentMode: Mode
            get() = when {
                shizukuProvider.isAvailable() && systemInputInjector.isAvailable -> Mode.SYSTEM_API
                shizukuProvider.isAvailable() -> Mode.SHELL_CMD
                else -> Mode.ACCESSIBILITY
            }

        val isV2Available: Boolean
            get() = shizukuProvider.isAvailable()

        val appController: AppController
            get() = appControllerImpl

        suspend fun captureScreen(
            quality: Int = ScreenCaptureProvider.DEFAULT_QUALITY,
            maxWidth: Int? = null,
            maxHeight: Int? = null,
        ): Result<ScreenshotData> {
            if (shizukuProvider.isAvailable()) {
                val bitmap = systemScreenCapture.capture(
                    maxWidth = maxWidth ?: 0,
                    maxHeight = maxHeight ?: 0,
                )
                if (bitmap != null) {
                    return encodeBitmap(bitmap, quality, maxWidth, maxHeight)
                }

                val shellBitmap = shellScreenCapture.capture()
                if (shellBitmap != null) {
                    return encodeBitmap(shellBitmap, quality, maxWidth, maxHeight)
                }
                Log.w(TAG, "v2 screen capture failed, falling back to Accessibility")
            }

            return screenCaptureProvider.captureScreenshot(quality, maxWidth, maxHeight)
        }

        suspend fun tap(x: Float, y: Float): Result<Unit> {
            if (shizukuProvider.isAvailable()) {
                if (systemInputInjector.isAvailable && systemInputInjector.tap(x, y)) {
                    return Result.success(Unit)
                }
                if (shellInputInjector.tap(x, y)) {
                    return Result.success(Unit)
                }
            }
            return actionExecutor.tap(x, y)
        }

        suspend fun longPress(x: Float, y: Float, durationMs: Long): Result<Unit> {
            if (shizukuProvider.isAvailable()) {
                if (systemInputInjector.isAvailable && systemInputInjector.longPress(x, y, durationMs)) {
                    return Result.success(Unit)
                }
                if (shellInputInjector.longPress(x, y, durationMs)) {
                    return Result.success(Unit)
                }
            }
            return actionExecutor.longPress(x, y, durationMs)
        }

        suspend fun doubleTap(x: Float, y: Float): Result<Unit> {
            if (shizukuProvider.isAvailable()) {
                if (systemInputInjector.isAvailable && systemInputInjector.doubleTap(x, y)) {
                    return Result.success(Unit)
                }
                if (shellInputInjector.doubleTap(x, y)) {
                    return Result.success(Unit)
                }
            }
            return actionExecutor.doubleTap(x, y)
        }

        suspend fun swipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
        ): Result<Unit> {
            if (shizukuProvider.isAvailable()) {
                if (systemInputInjector.isAvailable &&
                    systemInputInjector.swipe(x1, y1, x2, y2, durationMs)
                ) {
                    return Result.success(Unit)
                }
                if (shellInputInjector.swipe(x1, y1, x2, y2, durationMs)) {
                    return Result.success(Unit)
                }
            }
            return actionExecutor.swipe(x1, y1, x2, y2, durationMs)
        }

        suspend fun pressBack(): Result<Unit> {
            if (shizukuProvider.isAvailable()) {
                if (systemInputInjector.isAvailable &&
                    systemInputInjector.keyEvent(KeyEvent.KEYCODE_BACK)
                ) {
                    return Result.success(Unit)
                }
                if (shellInputInjector.keyEvent(KeyEvent.KEYCODE_BACK)) {
                    return Result.success(Unit)
                }
            }
            return actionExecutor.pressBack()
        }

        suspend fun pressHome(): Result<Unit> {
            if (shizukuProvider.isAvailable()) {
                if (systemInputInjector.isAvailable &&
                    systemInputInjector.keyEvent(KeyEvent.KEYCODE_HOME)
                ) {
                    return Result.success(Unit)
                }
                if (shellInputInjector.keyEvent(KeyEvent.KEYCODE_HOME)) {
                    return Result.success(Unit)
                }
            }
            return actionExecutor.pressHome()
        }

        suspend fun pressKey(keyCode: Int): Result<Unit> {
            if (shizukuProvider.isAvailable()) {
                if (systemInputInjector.isAvailable && systemInputInjector.keyEvent(keyCode)) {
                    return Result.success(Unit)
                }
                if (shellInputInjector.keyEvent(keyCode)) {
                    return Result.success(Unit)
                }
            }
            return Result.failure(IllegalStateException("Key injection requires Shizuku or Accessibility"))
        }

        suspend fun scroll(
            direction: com.example.amctl.services.accessibility.ScrollDirection,
            amount: com.example.amctl.services.accessibility.ScrollAmount,
        ): Result<Unit> {
            if (shizukuProvider.isAvailable()) {
                val screenInfo = try {
                    getScreenInfo()
                } catch (e: Exception) {
                    null
                }
                if (screenInfo != null) {
                    val w = screenInfo.width.toFloat()
                    val h = screenInfo.height.toFloat()
                    val cx = w / 2f
                    val cy = h / 2f
                    val dist = when (direction) {
                        com.example.amctl.services.accessibility.ScrollDirection.UP,
                        com.example.amctl.services.accessibility.ScrollDirection.DOWN,
                        -> h * amount.screenPercentage
                        com.example.amctl.services.accessibility.ScrollDirection.LEFT,
                        com.example.amctl.services.accessibility.ScrollDirection.RIGHT,
                        -> w * amount.screenPercentage
                    }
                    val half = dist / 2f
                    val result = when (direction) {
                        com.example.amctl.services.accessibility.ScrollDirection.UP ->
                            swipe(cx, cy - half, cx, cy + half, SCROLL_DURATION_MS)
                        com.example.amctl.services.accessibility.ScrollDirection.DOWN ->
                            swipe(cx, cy + half, cx, cy - half, SCROLL_DURATION_MS)
                        com.example.amctl.services.accessibility.ScrollDirection.LEFT ->
                            swipe(cx - half, cy, cx + half, cy, SCROLL_DURATION_MS)
                        com.example.amctl.services.accessibility.ScrollDirection.RIGHT ->
                            swipe(cx + half, cy, cx - half, cy, SCROLL_DURATION_MS)
                    }
                    if (result.isSuccess) return result
                }
            }
            return actionExecutor.scroll(direction, amount)
        }

        fun getScreenInfo(): ScreenInfo {
            if (accessibilityProvider.isReady()) {
                return accessibilityProvider.getScreenInfo()
            }
            if (shizukuProvider.isAvailable()) {
                return getScreenInfoViaShell()
            }
            return getScreenInfoFromContext()
        }

        private fun getScreenInfoViaShell(): ScreenInfo = try {
            val sizeOutput = shizukuProvider.exec("wm size").trim()
            val densityOutput = shizukuProvider.exec("wm density").trim()

            val sizeMatch = Regex("""(\d+)x(\d+)""").find(sizeOutput)
            val width = sizeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1080
            val height = sizeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1920

            val densityMatch = Regex("""(\d+)""").find(densityOutput)
            val density = densityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 420

            ScreenInfo(width = width, height = height, densityDpi = density, orientation = if (width < height) "portrait" else "landscape")
        } catch (e: Exception) {
            Log.w(TAG, "getScreenInfoViaShell failed", e)
            getScreenInfoFromContext()
        }

        @Suppress("DEPRECATION")
        private fun getScreenInfoFromContext(): ScreenInfo {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = wm.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            return ScreenInfo(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                orientation = if (metrics.widthPixels < metrics.heightPixels) "portrait" else "landscape",
            )
        }

        fun inputText(text: String): Boolean {
            if (shizukuProvider.isAvailable()) {
                return shellInputInjector.text(text)
            }
            return false
        }

        private fun encodeBitmap(
            bitmap: Bitmap,
            quality: Int,
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<ScreenshotData> {
            var resized: Bitmap? = null
            return try {
                resized = screenshotEncoder.resizeBitmapProportional(bitmap, maxWidth, maxHeight)
                val data = screenshotEncoder.bitmapToScreenshotData(resized, quality)
                Result.success(data)
            } finally {
                if (resized != null && resized !== bitmap) resized.recycle()
                bitmap.recycle()
            }
        }

        companion object {
            private const val TAG = "amctl:ToolRouter"
            private const val SCROLL_DURATION_MS = 300L
        }
    }
