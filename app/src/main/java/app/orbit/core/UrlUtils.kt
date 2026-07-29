package app.orbit.core

import android.net.Uri
import app.orbit.data.Prefs
import java.net.URLEncoder

object UrlUtils {

    const val HOME_URL = "file:///android_asset/home.html"

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /** Hosts that look like a domain: at least one dot and a plausible TLD. */
    private val LOOKS_LIKE_HOST =
        Regex("^[a-zA-Z0-9\\-._~%]+(\\.[a-zA-Z0-9\\-._~%]+)+(:\\d+)?(/.*)?$")

    private val IS_IP =
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")

    /**
     * Turn whatever the user typed into either a URL to load or a search query.
     * Typing on a TV remote is painful, so we lean towards "just make it work":
     * bare hosts get https://, anything with a space becomes a search.
     */
    fun toUrlOrSearch(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return HOME_URL

        if (SCHEME.containsMatchIn(input)) return input
        if (input.startsWith("about:") || input.startsWith("file:")) return input

        val hasSpace = input.contains(' ')
        if (!hasSpace && (LOOKS_LIKE_HOST.matches(input) || IS_IP.matches(input))) {
            return "https://$input"
        }
        if (!hasSpace && input.equals("localhost", ignoreCase = true)) return "http://localhost"

        return Prefs.searchUrlTemplate + URLEncoder.encode(input, "UTF-8")
    }

    fun host(url: String?): String = try {
        Uri.parse(url ?: "").host.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    /** Host without a leading "www.", for compact display in the omnibox. */
    fun prettyHost(url: String?): String {
        val h = host(url)
        return if (h.startsWith("www.")) h.substring(4) else h
    }

    fun isHome(url: String?): Boolean =
        url != null && (url.startsWith(HOME_URL) || url == "about:blank")

    fun isHttp(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://"))

    fun faviconUrl(url: String): String {
        val h = host(url)
        return if (h.isEmpty()) "" else "https://icons.duckduckgo.com/ip3/$h.ico"
    }
}
