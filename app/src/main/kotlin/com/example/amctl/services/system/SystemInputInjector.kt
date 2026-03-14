@file:Suppress("PrivateApi", "DiscouragedPrivateApi")

package com.example.amctl.services.system

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import javax.inject.Inject

class SystemInputInjector
    @Inject
    constructor() {

        private val inputManager: Any? by lazy {
            try {
                val clazz = Class.forName("android.hardware.input.InputManager")
                clazz.getMethod("getInstance").invoke(null)
            } catch (e: Exception) {
                Log.w(TAG, "InputManager.getInstance() reflection failed", e)
                null
            }
        }

        private val injectMethod: java.lang.reflect.Method? by lazy {
            try {
                inputManager?.javaClass?.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
            } catch (e: Exception) {
                Log.w(TAG, "injectInputEvent method not found", e)
                null
            }
        }

        val isAvailable: Boolean
            get() = inputManager != null && injectMethod != null

        fun tap(x: Float, y: Float): Boolean {
            val downTime = SystemClock.uptimeMillis()
            return injectMotion(downTime, downTime, MotionEvent.ACTION_DOWN, x, y) &&
                injectMotion(downTime, downTime + TAP_DURATION_MS, MotionEvent.ACTION_UP, x, y)
        }

        fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
            val downTime = SystemClock.uptimeMillis()
            if (!injectMotion(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)) return false
            Thread.sleep(durationMs)
            return injectMotion(downTime, downTime + durationMs, MotionEvent.ACTION_UP, x, y)
        }

        fun doubleTap(x: Float, y: Float): Boolean {
            if (!tap(x, y)) return false
            Thread.sleep(DOUBLE_TAP_GAP_MS)
            return tap(x, y)
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val downTime = SystemClock.uptimeMillis()
            val steps = maxOf((durationMs / FRAME_INTERVAL_MS).toInt(), 1)

            if (!injectMotion(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1)) return false

            for (i in 1..steps) {
                val fraction = i.toFloat() / steps
                val x = x1 + (x2 - x1) * fraction
                val y = y1 + (y2 - y1) * fraction
                val eventTime = downTime + durationMs * i / steps
                if (!injectMotion(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y)) return false
                Thread.sleep(FRAME_INTERVAL_MS)
            }

            return injectMotion(downTime, downTime + durationMs, MotionEvent.ACTION_UP, x2, y2)
        }

        fun keyEvent(keyCode: Int): Boolean {
            val im = inputManager ?: return false
            val method = injectMethod ?: return false
            return try {
                val now = SystemClock.uptimeMillis()
                val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
                val up = KeyEvent(now, now + TAP_DURATION_MS, KeyEvent.ACTION_UP, keyCode, 0)
                method.invoke(im, down, INJECT_MODE_ASYNC) as Boolean &&
                    method.invoke(im, up, INJECT_MODE_ASYNC) as Boolean
            } catch (e: Exception) {
                Log.w(TAG, "injectInputEvent keyEvent failed", e)
                false
            }
        }

        private fun injectMotion(
            downTime: Long,
            eventTime: Long,
            action: Int,
            x: Float,
            y: Float,
        ): Boolean {
            val im = inputManager ?: return false
            val method = injectMethod ?: return false
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            return try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                method.invoke(im, event, INJECT_MODE_ASYNC) as Boolean
            } catch (e: Exception) {
                Log.w(TAG, "injectInputEvent motion failed", e)
                false
            } finally {
                event.recycle()
            }
        }

        companion object {
            private const val TAG = "amctl:SysInput"
            private const val INJECT_MODE_ASYNC = 0
            private const val TAP_DURATION_MS = 50L
            private const val DOUBLE_TAP_GAP_MS = 100L
            private const val FRAME_INTERVAL_MS = 16L
        }
    }
