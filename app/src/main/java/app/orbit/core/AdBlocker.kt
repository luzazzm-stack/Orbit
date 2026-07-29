package app.orbit.core

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-based request blocker.
 *
 * This matters more on a TV than on a phone: the Skyworth has 1.7 GB of RAM and
 * a weak GPU, and ad/tracker iframes are what actually push a page into swap.
 * Blocking is done by host suffix so `a.b.doubleclick.net` matches
 * `doubleclick.net` without needing a wildcard list.
 */
object AdBlocker {

    private const val TAG = "OrbitAdBlock"
    private val loaded = AtomicBoolean(false)
    private var hosts: Set<String> = emptySet()

    private val EMPTY_RESPONSE: WebResourceResponse
        get() = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )

    /** Call off the main thread. Safe to call more than once. */
    fun load(ctx: Context) {
        if (!loaded.compareAndSet(false, true)) return
        hosts = try {
            ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toHashSet()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "blocklist unavailable", t)
            emptySet()
        }
        Log.i(TAG, "loaded ${hosts.size} blocked hosts")
    }

    /**
     * Combined verdict: the bundled list first (instant), then AdGuard DNS.
     *
     * Main-frame navigations are never blocked by DNS — a wrong verdict there
     * would look like the browser refusing to open a page the user explicitly
     * asked for, whereas a wrongly blocked subresource is invisible.
     */
    fun shouldBlock(url: String?, isMainFrame: Boolean): Boolean {
        val host = UrlUtils.host(url)
        if (host.isEmpty()) return false

        if (app.orbit.data.Prefs.adBlock && inLocalList(host)) return true

        if (app.orbit.data.Prefs.doh && !isMainFrame) {
            return DohResolver.check(host, DOH_TIMEOUT_MS) == DohResolver.Verdict.BLOCK
        }
        return false
    }

    private fun inLocalList(startHost: String): Boolean {
        if (hosts.isEmpty()) return false
        var h = startHost
        if (h.isEmpty()) return false
        // Walk up the domain: ads.foo.example.com -> foo.example.com -> example.com
        while (true) {
            if (hosts.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    fun blockedResponse(): WebResourceResponse = EMPTY_RESPONSE

    fun localListSize(): Int = hosts.size

    /**
     * Short enough that a slow DNS answer cannot stall page rendering. On
     * timeout the request is allowed and the lookup completes in the
     * background, so repeat requests to the same host are filtered.
     */
    private const val DOH_TIMEOUT_MS = 700L
}
