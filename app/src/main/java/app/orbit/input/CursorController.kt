package app.orbit.input

import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import app.orbit.data.Prefs

/**
 * Virtual pointer driven by the D-pad.
 *
 * Spatial navigation handles most pages, but it can only see elements it can
 * reach from the DOM — cross-origin iframes, canvas apps and map widgets are
 * invisible to it. The cursor is the universal fallback: it synthesises real
 * mouse events, so anything a mouse can drive, the remote can drive.
 *
 * Events are dispatched with SOURCE_MOUSE rather than SOURCE_TOUCHSCREEN so
 * that Chromium produces hover states and wheel scrolling, which is what
 * desktop layouts (our default UA) actually expect.
 */
class CursorController(
    private val overlay: CursorOverlay,
    private val webViewProvider: () -> WebView?
) {

    private val pressed = HashSet<Int>()
    private var running = false
    private var lastFrameNs = 0L
    private var pressStartMs = 0L
    private var downTime = 0L
    private var hovering = false

    var enabled = false
        private set

    private val choreographer = Choreographer.getInstance()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step(frameTimeNanos)
            if (running) choreographer.postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------- lifecycle

    fun enable(centreOn: View) {
        if (enabled) return
        enabled = true
        if (overlay.cursorX <= 0f || overlay.cursorY <= 0f) {
            if (centreOn.width > 0 && centreOn.height > 0) {
                overlay.moveTo(centreOn.width / 2f, centreOn.height / 2f)
            } else {
                // Enabled before the first layout pass; centre once we have bounds.
                centreOn.post { overlay.moveTo(centreOn.width / 2f, centreOn.height / 2f) }
            }
        }
        overlay.visibility = View.VISIBLE
        overlay.invalidate()
        sendHover(MotionEvent.ACTION_HOVER_ENTER)
        hovering = true
    }

    fun disable() {
        if (!enabled) return
        stopLoop()
        pressed.clear()
        if (hovering) {
            sendHover(MotionEvent.ACTION_HOVER_EXIT)
            hovering = false
        }
        overlay.visibility = View.GONE
        enabled = false
    }

    fun toggle(centreOn: View): Boolean {
        if (enabled) disable() else enable(centreOn)
        return enabled
    }

    /**
     * Park the pointer on a specific point and let the page know it is hovering
     * there, so whatever is underneath lights up immediately. Used when a
     * cross-origin frame (a captcha, typically) needs a real pointer.
     */
    fun placeAt(x: Float, y: Float) {
        if (!enabled) return
        val w = overlay.width.toFloat()
        val h = overlay.height.toFloat()
        if (w <= 0f || h <= 0f) {
            overlay.post { placeAt(x, y) }
            return
        }
        overlay.moveTo(x.coerceIn(2f, w - 2f), y.coerceIn(2f, h - 2f))
        sendHover(MotionEvent.ACTION_HOVER_MOVE)
    }

    // ------------------------------------------------------------ key events

    /** @return true when the event was consumed. */
    fun onKeyDown(keyCode: Int): Boolean {
        if (!enabled) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pressed.isEmpty()) pressStartMs = SystemClock.uptimeMillis()
                if (pressed.add(keyCode)) {
                    // A fixed step on press. The frame loop measures elapsed time,
                    // so its first frame necessarily moves nothing — without this
                    // a quick tap could travel zero pixels, making it impossible
                    // to line the pointer up on something small like a checkbox.
                    nudge(keyCode)
                    startLoop()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                sendPress()
                true
            }
            else -> false
        }
    }

    fun onKeyUp(keyCode: Int): Boolean {
        if (!enabled) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                pressed.remove(keyCode)
                if (pressed.isEmpty()) {
                    stopLoop()
                    pressStartMs = 0L
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                sendRelease()
                true
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------- motion

    private fun nudge(keyCode: Int) {
        val w = overlay.width.toFloat()
        val h = overlay.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dx = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -NUDGE_PX
            KeyEvent.KEYCODE_DPAD_RIGHT -> NUDGE_PX
            else -> 0f
        }
        val dy = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -NUDGE_PX
            KeyEvent.KEYCODE_DPAD_DOWN -> NUDGE_PX
            else -> 0f
        }
        overlay.moveTo(
            (overlay.cursorX + dx).coerceIn(2f, w - 2f),
            (overlay.cursorY + dy).coerceIn(2f, h - 2f)
        )
        sendHover(MotionEvent.ACTION_HOVER_MOVE)
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(frame)
    }

    private fun stopLoop() {
        running = false
        choreographer.removeFrameCallback(frame)
    }

    private fun step(nowNs: Long) {
        val wv = webViewProvider()
        if (wv == null || pressed.isEmpty()) { stopLoop(); return }

        if (lastFrameNs == 0L) { lastFrameNs = nowNs; return }
        val dt = ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
        lastFrameNs = nowNs

        // Ramp from a slow, precise start to a fast traverse so short taps nudge
        // by a few pixels and a held key crosses the screen quickly.
        val held = (SystemClock.uptimeMillis() - pressStartMs).coerceAtLeast(0L)
        val ramp = (held / RAMP_MS).coerceIn(0f, 1f)
        val scale = 0.55f + 0.225f * Prefs.cursorSpeed
        val speed = (MIN_SPEED + (MAX_SPEED - MIN_SPEED) * ramp * ramp) * scale

        var dx = 0f
        var dy = 0f
        if (pressed.contains(KeyEvent.KEYCODE_DPAD_LEFT)) dx -= 1f
        if (pressed.contains(KeyEvent.KEYCODE_DPAD_RIGHT)) dx += 1f
        if (pressed.contains(KeyEvent.KEYCODE_DPAD_UP)) dy -= 1f
        if (pressed.contains(KeyEvent.KEYCODE_DPAD_DOWN)) dy += 1f
        if (dx != 0f && dy != 0f) { dx *= 0.7071f; dy *= 0.7071f }

        val w = overlay.width.toFloat()
        val h = overlay.height.toFloat()
        if (w <= 0f || h <= 0f) return

        var nx = overlay.cursorX + dx * speed * dt
        var ny = overlay.cursorY + dy * speed * dt

        // At the top/bottom edge the pointer stops and the page scrolls instead,
        // so a single held key can traverse a whole article.
        var scroll = 0f
        if (ny < EDGE_BAND && dy < 0f) { scroll = SCROLL_STEP; ny = EDGE_BAND }
        if (ny > h - EDGE_BAND && dy > 0f) { scroll = -SCROLL_STEP; ny = h - EDGE_BAND }

        nx = nx.coerceIn(2f, w - 2f)
        ny = ny.coerceIn(2f, h - 2f)
        overlay.moveTo(nx, ny)

        if (scroll != 0f) sendScroll(scroll * (0.5f + 0.5f * ramp))
        sendHover(MotionEvent.ACTION_HOVER_MOVE)
    }

    // -------------------------------------------------------------- dispatch

    private fun sendPress() {
        downTime = SystemClock.uptimeMillis()
        overlay.flashClick()
        dispatchPointer(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)
    }

    private fun sendRelease() {
        if (downTime == 0L) return
        dispatchPointer(MotionEvent.ACTION_UP, 0)
        downTime = 0L
    }

    private fun dispatchPointer(action: Int, buttonState: Int) {
        val wv = webViewProvider() ?: return
        val ev = build(action, buttonState, 0f) ?: return
        try {
            wv.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun sendHover(action: Int) {
        val wv = webViewProvider() ?: return
        val ev = build(action, 0, 0f) ?: return
        try {
            wv.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun sendScroll(amount: Float) {
        val wv = webViewProvider() ?: return
        val ev = build(MotionEvent.ACTION_SCROLL, 0, amount) ?: return
        try {
            wv.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    /** Coordinates are overlay-relative; the overlay is laid out over the WebView. */
    private fun build(action: Int, buttonState: Int, vscroll: Float): MotionEvent? {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = overlay.cursorX
            y = overlay.cursorY
            pressure = 1f
            size = 1f
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        }
        return try {
            MotionEvent.obtain(
                if (downTime != 0L) downTime else now,
                now,
                action,
                1,
                arrayOf(props),
                arrayOf(coords),
                0,
                buttonState,
                1f, 1f,
                0, 0,
                InputDevice.SOURCE_MOUSE,
                0
            )
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        private const val MIN_SPEED = 320f      // px/s at the moment of press
        private const val MAX_SPEED = 2400f     // px/s once fully ramped
        private const val RAMP_MS = 750f
        private const val EDGE_BAND = 90f       // px from the edge where scrolling takes over
        private const val SCROLL_STEP = 1.15f   // wheel notches per frame
        private const val NUDGE_PX = 16f        // guaranteed travel for a single tap
    }
}
