package app.orbit.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/**
 * Draws the virtual pointer. Purely visual — it never takes focus or touch, so
 * it can sit on top of the WebView without interfering with it.
 */
class CursorOverlay(ctx: Context) : View(ctx) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0C10")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeJoin = Paint.Join.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#556AA6FF")
        style = Paint.Style.FILL
    }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6AA6FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val arrow = Path()

    var cursorX = 0f
    var cursorY = 0f

    private var clickAt = 0L
    private val rippleMs = 260L

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        setWillNotDraw(false)
        buildArrow()
    }

    private fun buildArrow() {
        // A classic arrow pointer, scaled up for a 1080p screen viewed from a sofa.
        val s = 1.9f
        arrow.reset()
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, 17f * s)
        arrow.lineTo(4.2f * s, 13.2f * s)
        arrow.lineTo(7.1f * s, 19.6f * s)
        arrow.lineTo(10.2f * s, 18.2f * s)
        arrow.lineTo(7.4f * s, 11.9f * s)
        arrow.lineTo(12.6f * s, 11.6f * s)
        arrow.close()
    }

    fun moveTo(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        invalidate()
    }

    fun flashClick() {
        clickAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return

        val elapsed = SystemClock.uptimeMillis() - clickAt
        if (clickAt > 0L && elapsed < rippleMs) {
            val t = elapsed / rippleMs.toFloat()
            ripple.alpha = ((1f - t) * 200).toInt().coerceIn(0, 255)
            canvas.drawCircle(cursorX, cursorY, 10f + 34f * t, ripple)
            invalidate()
        }

        canvas.drawCircle(cursorX, cursorY, 13f, halo)

        canvas.save()
        canvas.translate(cursorX, cursorY)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }
}
