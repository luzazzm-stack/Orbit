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
    enum class Outcome { MOVED, ENTERED, SCROLLED, EDGE, CLICKED, INPUT, IFRAME, NONE, ERROR }

    /** Result of asking the page how navigable it is with a D-pad. */
    data class Probe(val focusableInView: Int, val bigFrames: Int) {
        /**
         * True for canvas apps, embedded players and pages whose content lives
         * in a cross-origin frame — nothing for the D-pad to move between.
         */
        val needsPointer: Boolean get() = bigFrames > 0 || focusableInView < 6
    }

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

    /**
     * @param cb receives the outcome plus the raw payload, which carries the
     *           frame's centre (as a fraction of the viewport) for IFRAME.
     */
    fun activate(wv: WebView, cb: (Outcome, JSONObject?) -> Unit) {
        wv.evaluateJavascript("window.__ORBIT__ && window.__ORBIT__.activate()") { raw ->
            val o = parse(raw)
            cb(outcomeOf(o), o)
        }
    }

    /** Ask the page whether the D-pad has anything to work with. */
    fun probe(wv: WebView, cb: (Probe?) -> Unit) {
        wv.evaluateJavascript("window.__ORBIT__ && window.__ORBIT__.probe()") { raw ->
            val o = parse(raw)
            cb(
                if (o == null) null
                else Probe(o.optInt("count", 0), o.optInt("big", 0))
            )
        }
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
        wv.evaluateJavascript(js) { raw -> cb(outcomeOf(parse(raw))) }
    }

    private fun outcomeOf(o: JSONObject?): Outcome {
        if (o == null) return Outcome.ERROR
        return when (o.optString("result")) {
            "moved" -> Outcome.MOVED
            "entered" -> Outcome.ENTERED
            "scrolled" -> Outcome.SCROLLED
            "edge" -> Outcome.EDGE
            "clicked" -> Outcome.CLICKED
            "input" -> Outcome.INPUT
            "iframe" -> Outcome.IFRAME
            "playing", "paused", "seeked" -> Outcome.CLICKED
            "none" -> Outcome.NONE
            else -> Outcome.ERROR
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
