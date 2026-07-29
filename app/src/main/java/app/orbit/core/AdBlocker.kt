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

    fun shouldBlock(url: String?): Boolean {
        if (hosts.isEmpty()) return false
        var h = UrlUtils.host(url)
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
}
