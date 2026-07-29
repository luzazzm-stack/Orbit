package app.orbit.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Small typed wrapper over SharedPreferences. Everything the user can change
 * from the Settings panel lives here.
 */
object Prefs {

    private const val FILE = "orbit_prefs"

    private const val K_SEARCH = "search_engine"
    private const val K_DESKTOP_UA = "desktop_ua"
    private const val K_ADBLOCK = "adblock"
    private const val K_CURSOR_SPEED = "cursor_speed"
    private const val K_TEXT_ZOOM = "text_zoom"
    private const val K_START_MODE = "start_mode"
    private const val K_HOME_URL = "home_url"
    private const val K_SMOOTH_SCROLL = "smooth_scroll"
    private const val K_DEFAULT_CURSOR = "default_cursor"

    lateinit var sp: SharedPreferences
        private set

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** Search engines, keyed by the value stored in prefs. */
    val searchEngines = linkedMapOf(
        "google" to "https://www.google.com/search?q=",
        "duckduckgo" to "https://duckduckgo.com/?q=",
        "bing" to "https://www.bing.com/search?q=",
        "youtube" to "https://www.youtube.com/results?search_query="
    )

    var searchEngine: String
        get() = sp.getString(K_SEARCH, "google") ?: "google"
        set(v) = sp.edit().putString(K_SEARCH, v).apply()

    val searchUrlTemplate: String
        get() = searchEngines[searchEngine] ?: searchEngines.getValue("google")

    /**
     * TVs are large screens; most sites serve a phone layout to Android UAs,
     * which looks broken from three metres away. Desktop UA is the better default.
     */
    var desktopUa: Boolean
        get() = sp.getBoolean(K_DESKTOP_UA, true)
        set(v) = sp.edit().putBoolean(K_DESKTOP_UA, v).apply()

    var adBlock: Boolean
        get() = sp.getBoolean(K_ADBLOCK, true)
        set(v) = sp.edit().putBoolean(K_ADBLOCK, v).apply()

    /** 1..5, maps to a pixels-per-second ceiling in CursorController. */
    var cursorSpeed: Int
        get() = sp.getInt(K_CURSOR_SPEED, 3).coerceIn(1, 5)
        set(v) = sp.edit().putInt(K_CURSOR_SPEED, v.coerceIn(1, 5)).apply()

    /** WebView text zoom percent. 100 is the browser default; TVs want more. */
    var textZoom: Int
        get() = sp.getInt(K_TEXT_ZOOM, 110).coerceIn(80, 200)
        set(v) = sp.edit().putInt(K_TEXT_ZOOM, v.coerceIn(80, 200)).apply()

    var smoothScroll: Boolean
        get() = sp.getBoolean(K_SMOOTH_SCROLL, true)
        set(v) = sp.edit().putBoolean(K_SMOOTH_SCROLL, v).apply()

    /** When true a new tab starts in cursor mode instead of spatial-nav mode. */
    var defaultCursorMode: Boolean
        get() = sp.getBoolean(K_DEFAULT_CURSOR, false)
        set(v) = sp.edit().putBoolean(K_DEFAULT_CURSOR, v).apply()

    /** "home" = the built-in start page, "url" = a fixed page. */
    var startMode: String
        get() = sp.getString(K_START_MODE, "home") ?: "home"
        set(v) = sp.edit().putString(K_START_MODE, v).apply()

    var homeUrl: String
        get() = sp.getString(K_HOME_URL, "") ?: ""
        set(v) = sp.edit().putString(K_HOME_URL, v).apply()
}
