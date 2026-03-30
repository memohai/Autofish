package com.memohai.autofish.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.memohai.autofish.services.accessibility.AutoFishAccessibilityService
import com.memohai.autofish.services.accessibility.BoundsData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class OverlayMark(
    val index: Int,
    val label: String,
    val interactive: Boolean,
    val bounds: BoundsData,
    val nodeId: String,
    val className: String?,
    val text: String?,
    val desc: String?,
    val resId: String?,
)

data class OverlayState(
    val available: Boolean,
    val enabled: Boolean,
    val markCount: Int,
)

class OverlayManager {
    @Volatile
    private var enabled = false
    private var overlayView: MarkOverlayView? = null
    private var lastMarks: List<OverlayMark> = emptyList()
    private var offsetX: Int = 0
    private var offsetY: Int = 0

    fun state(): OverlayState = OverlayState(
        available = AutoFishAccessibilityService.instance != null,
        enabled = enabled,
        markCount = lastMarks.size,
    )

    fun currentMarks(): List<OverlayMark> = lastMarks

    @Synchronized
    fun updateMarks(marks: List<OverlayMark>) {
        lastMarks = marks
        val view = overlayView ?: return
        runOnMainAndWait {
            view.setMarks(marks)
            view.invalidate()
        }
    }

    @Synchronized
    fun setEnabled(
        target: Boolean,
        marks: List<OverlayMark>? = null,
        offsetX: Int? = null,
        offsetY: Int? = null,
    ): Result<Unit> {
        if (marks != null) {
            lastMarks = marks
        }
        if (offsetX != null) {
            this.offsetX = offsetX
        }
        if (offsetY != null) {
            this.offsetY = offsetY
        }
        if (target == enabled) {
            if (target) {
                val view = overlayView
                if (view != null) {
                    runOnMainAndWait {
                        view.setRenderOffset(this.offsetX.toFloat(), this.offsetY.toFloat())
                    }
                }
                updateMarks(lastMarks)
            }
            return Result.success(Unit)
        }
        val service = AutoFishAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility service not available"))
        val wm = service.getSystemService(WindowManager::class.java)
            ?: return Result.failure(IllegalStateException("WindowManager not available"))

        return if (target) {
            val view = MarkOverlayView(service).apply {
                setMarks(lastMarks)
                setRenderOffset(this@OverlayManager.offsetX.toFloat(), this@OverlayManager.offsetY.toFloat())
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            )
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            runOnMainAndWait {
                wm.addView(view, params)
                overlayView = view
                enabled = true
            }
            Result.success(Unit)
        } else {
            val view = overlayView
            if (view != null) {
                runOnMainAndWait {
                    wm.removeView(view)
                    overlayView = null
                    enabled = false
                }
            } else {
                enabled = false
            }
            Result.success(Unit)
        }
    }

    private fun runOnMainAndWait(block: () -> Unit) {
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            block()
            return
        }
        val service = AutoFishAccessibilityService.instance ?: return
        val done = CountDownLatch(1)
        service.mainExecutor.execute {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await(300, TimeUnit.MILLISECONDS)
    }
}

private class MarkOverlayView(
    service: AccessibilityService,
) : View(service) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC222222")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 25f
        style = Paint.Style.FILL
    }
    private val textBounds = Rect()
    private val occupiedLabels = ArrayList<RectF>(64)
    private var marks: List<OverlayMark> = emptyList()
    private var renderOffsetX = 0f
    private var renderOffsetY = 0f

    fun setMarks(nextMarks: List<OverlayMark>) {
        marks = nextMarks
    }

    fun setRenderOffset(x: Float, y: Float) {
        renderOffsetX = x
        renderOffsetY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(renderOffsetX, renderOffsetY)
        occupiedLabels.clear()
        for (mark in marks) {
            val b = mark.bounds
            if (b.right <= b.left || b.bottom <= b.top) {
                continue
            }
            val palette = paletteFor(mark)
            val strokeColor = palette.stroke
            val fillColor = withAlpha(strokeColor, FILL_ALPHA)
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = if (mark.interactive) 4f else 2f
            fillPaint.color = fillColor
            canvas.drawRect(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                fillPaint,
            )
            canvas.drawRect(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                strokePaint,
            )

            val text = mark.label
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            val padding = 8f
            val labelWidth = textBounds.width() + padding * 2
            val labelHeight = textBounds.height() + padding * 2

            val preferred = listOf(
                RectF(
                    b.left.toFloat(),
                    (b.top - labelHeight).coerceAtLeast(0f),
                    b.left + labelWidth,
                    b.top.toFloat().coerceAtLeast(labelHeight),
                ),
                RectF(
                    b.left.toFloat(),
                    b.top.toFloat(),
                    b.left + labelWidth,
                    b.top + labelHeight,
                ),
                RectF(
                    (b.right - labelWidth).coerceAtLeast(0f),
                    (b.top - labelHeight).coerceAtLeast(0f),
                    b.right.toFloat(),
                    b.top.toFloat().coerceAtLeast(labelHeight),
                ),
                RectF(
                    (b.right - labelWidth).coerceAtLeast(0f),
                    b.top.toFloat(),
                    b.right.toFloat(),
                    b.top + labelHeight,
                ),
            )

            val rect = preferred.firstOrNull { !overlapsExisting(it) }
                ?: preferred.first()
            occupiedLabels.add(rect)
            bgPaint.color = withAlpha(strokeColor, LABEL_BG_ALPHA)
            canvas.drawRoundRect(rect, 6f, 6f, bgPaint)
            canvas.drawText(text, rect.left + padding, rect.bottom - padding, textPaint)
        }
        canvas.restore()
    }

    private fun overlapsExisting(target: RectF): Boolean = occupiedLabels.any { RectF.intersects(it, target) }

    private fun paletteFor(mark: OverlayMark): Palette {
        val className = mark.className ?: ""
        return when {
            mark.interactive && (className.contains("EditText", ignoreCase = true) || className.contains("TextField", ignoreCase = true)) ->
                Palette(Color.parseColor("#00BCD4"))
            mark.interactive && (className.contains("Button", ignoreCase = true) || className.contains("ImageButton", ignoreCase = true)) ->
                Palette(Color.parseColor("#FF9800"))
            mark.interactive && (
                className.contains("CheckBox", ignoreCase = true) ||
                    className.contains("Switch", ignoreCase = true) ||
                    className.contains("RadioButton", ignoreCase = true)
            ) ->
                Palette(Color.parseColor("#FFEB3B"))
            mark.interactive && (
                className.contains("RecyclerView", ignoreCase = true) ||
                    className.contains("ListView", ignoreCase = true) ||
                    className.contains("ScrollView", ignoreCase = true)
            ) ->
                Palette(Color.parseColor("#E91E63"))
            mark.interactive ->
                Palette(Color.parseColor("#4CAF50"))
            !mark.text.isNullOrBlank() || !mark.desc.isNullOrBlank() ->
                Palette(Color.parseColor("#42A5F5"))
            else ->
                Palette(Color.parseColor("#9E9E9E"))
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private data class Palette(val stroke: Int)

    companion object {
        private const val FILL_ALPHA = 26
        private const val LABEL_BG_ALPHA = 196
    }
}
