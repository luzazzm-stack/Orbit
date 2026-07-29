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
    // New key on purpose: "default_cursor" was stored as a Boolean in 0.2.x, and
    // reading an existing Boolean with getString throws ClassCastException.
    private const val K_DEFAULT_CURSOR = "pointer_on_sites"
    private const val K_DOH = "doh_enabled"
    private const val K_DOH_URL = "doh_url"
    private const val K_IMAGES = "load_images"
    private const val K_JS = "javascript"
    private const val K_HTTPS_ONLY = "https_only"
    private const val K_3P_COOKIES = "third_party_cookies"
    private const val K_FORCE_DARK = "force_dark"
    private const val K_REMOTE_ICONS = "remote_icons"

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

    /**
     * When the pointer should appear on a website.
     *
     * "auto"   — whenever the page has too little for the D-pad to move between
     *            (canvas apps, embedded players, content inside a frame)
     * "always" — on every site
     * "never"  — link mode only; hold OK to reach the pointer manually
     */
    var pointerOnSites: String
        get() = sp.getString(K_DEFAULT_CURSOR, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_DEFAULT_CURSOR, v).apply()

    val pointerModeLabel: String
        get() = when (pointerOnSites) {
            "always" -> "Always"
            "never" -> "Never"
            else -> "Automatic"
        }

    /**
     * DNS-over-HTTPS filtering. See DohResolver: AdGuard's answer is used as a
     * block/allow verdict rather than as the browser's actual resolver, which
     * WebView does not allow us to replace.
     */
    var doh: Boolean
        get() = sp.getBoolean(K_DOH, true)
        set(v) = sp.edit().putBoolean(K_DOH, v).apply()

    val dohProviders = linkedMapOf(
        "AdGuard" to "https://dns.adguard.com/dns-query",
        "AdGuard Family" to "https://family.adguard-dns.com/dns-query",
        "Cloudflare" to "https://cloudflare-dns.com/dns-query",
        "Quad9" to "https://dns.quad9.net/dns-query"
    )

    var dohUrl: String
        get() = sp.getString(K_DOH_URL, dohProviders.getValue("AdGuard"))
            ?: dohProviders.getValue("AdGuard")
        set(v) = sp.edit().putString(K_DOH_URL, v).apply()

    val dohProviderName: String
        get() = dohProviders.entries.firstOrNull { it.value == dohUrl }?.key ?: "Custom"

    /** Turning images off is a real speed lever on a 1.7 GB box. */
    var loadImages: Boolean
        get() = sp.getBoolean(K_IMAGES, true)
        set(v) = sp.edit().putBoolean(K_IMAGES, v).apply()

    var javaScript: Boolean
        get() = sp.getBoolean(K_JS, true)
        set(v) = sp.edit().putBoolean(K_JS, v).apply()

    var httpsOnly: Boolean
        get() = sp.getBoolean(K_HTTPS_ONLY, false)
        set(v) = sp.edit().putBoolean(K_HTTPS_ONLY, v).apply()

    var thirdPartyCookies: Boolean
        get() = sp.getBoolean(K_3P_COOKIES, false)
        set(v) = sp.edit().putBoolean(K_3P_COOKIES, v).apply()

    /** Force-dark helps in a dark room but mangles sites with their own theme. */
    var forceDark: Boolean
        get() = sp.getBoolean(K_FORCE_DARK, false)
        set(v) = sp.edit().putBoolean(K_FORCE_DARK, v).apply()

    /**
     * Allow the start page to look up icons for sites not visited yet. Icons
     * from pages you actually open are always captured locally; this only
     * controls the third-party lookup for the rest.
     */
    var remoteIcons: Boolean
        get() = sp.getBoolean(K_REMOTE_ICONS, true)
        set(v) = sp.edit().putBoolean(K_REMOTE_ICONS, v).apply()

    /** "home" = the built-in start page, "url" = a fixed page. */
    var startMode: String
        get() = sp.getString(K_START_MODE, "home") ?: "home"
        set(v) = sp.edit().putString(K_START_MODE, v).apply()

    var homeUrl: String
        get() = sp.getString(K_HOME_URL, "") ?: ""
        set(v) = sp.edit().putString(K_HOME_URL, v).apply()
}
