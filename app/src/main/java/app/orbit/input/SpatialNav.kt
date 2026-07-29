package app.orbit.input

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.webkit.WebView
import app.orbit.data.Prefs
import org.json.JSONObject

/**
 * Kotlin side of the injected spatial-navigation engine.
 *
 * All the geometry lives in assets/spatialnav.js; this class just injects it,
 * forwards D-pad presses and reports back what happened so the activity can
 * react (show the keyboard for a text field, leave the page at an edge, ...).
 */
class SpatialNav(private val ctx: Context) {

    companion object {
        private const val TAG = "OrbitSpatialNav"

        fun directionOf(keyCode: Int): String? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }
    }

    /** What the page did with the key. */
    enum class Outcome { MOVED, ENTERED, SCROLLED, EDGE, CLICKED, INPUT, NONE, ERROR }

    private val script: String by lazy {
        try {
            ctx.assets.open("spatialnav.js").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.e(TAG, "spatialnav.js missing", t)
            ""
        }
    }

    /** Call from onPageFinished and after in-page navigations. */
    fun inject(wv: WebView) {
        if (script.isEmpty()) return
        wv.evaluateJavascript("window.__ORBIT_SMOOTH__=${Prefs.smoothScroll};", null)
        wv.evaluateJavascript(script, null)
    }

    fun move(wv: WebView, direction: String, cb: (Outcome) -> Unit) {
        eval(wv, "window.__ORBIT__ && window.__ORBIT__.move('$direction')", cb)
    }

    fun activate(wv: WebView, cb: (Outcome) -> Unit) {
        eval(wv, "window.__ORBIT__ && window.__ORBIT__.activate()", cb)
    }

    fun enter(wv: WebView, cb: (Outcome) -> Unit = {}) {
        eval(wv, "window.__ORBIT__ && window.__ORBIT__.enter()", cb)
    }

    fun clear(wv: WebView) {
        wv.evaluateJavascript("window.__ORBIT__ && window.__ORBIT__.clear()", null)
    }

    fun playPause(wv: WebView, cb: (Outcome) -> Unit = {}) {
        eval(wv, "window.__ORBIT__ && window.__ORBIT__.playPause()", cb)
    }

    fun seek(wv: WebView, seconds: Int, cb: (Outcome) -> Unit = {}) {
        eval(wv, "window.__ORBIT__ && window.__ORBIT__.seek($seconds)", cb)
    }

    /** True when a text field is currently highlighted, so BACK should dismiss the IME. */
    fun isOnTextInput(wv: WebView, cb: (Boolean) -> Unit) {
        wv.evaluateJavascript("window.__ORBIT__ && window.__ORBIT__.info()") { raw ->
            cb(parse(raw)?.optBoolean("isInput", false) ?: false)
        }
    }

    private fun eval(wv: WebView, js: String, cb: (Outcome) -> Unit) {
        wv.evaluateJavascript(js) { raw ->
            val o = parse(raw)
            if (o == null) {
                cb(Outcome.ERROR)
                return@evaluateJavascript
            }
            cb(
                when (o.optString("result")) {
                    "moved" -> Outcome.MOVED
                    "entered" -> Outcome.ENTERED
                    "scrolled" -> Outcome.SCROLLED
                    "edge" -> Outcome.EDGE
                    "clicked" -> Outcome.CLICKED
                    "input" -> Outcome.INPUT
                    "playing", "paused", "seeked" -> Outcome.CLICKED
                    "none" -> Outcome.NONE
                    else -> Outcome.ERROR
                }
            )
        }
    }

    /**
     * evaluateJavascript hands back a JSON-encoded *string* ("\"{...}\""), so the
     * payload has to be unquoted and unescaped before it can be parsed.
     */
    private fun parse(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val inner = if (raw.startsWith("\"")) {
                raw.substring(1, raw.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            } else raw
            JSONObject(inner)
        } catch (t: Throwable) {
            null
        }
    }
}
