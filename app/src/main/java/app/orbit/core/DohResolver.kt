package app.orbit.core

import android.content.Context
import android.util.Log
import app.orbit.data.Prefs
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AdGuard DNS filtering, inside the browser.
 *
 * Android's WebView gives no hook to change Chromium's DNS resolver, so the
 * usual "point the browser at DoH" approach is not available. Instead this
 * resolves each host against AdGuard's DoH endpoint and uses the *answer* as a
 * verdict: AdGuard replies 0.0.0.0 (or NXDOMAIN) for ad, tracker and malware
 * domains, so those requests get dropped in shouldInterceptRequest while
 * everything else continues to load over WebView's own network stack.
 *
 * Proxying every request through OkHttp instead was rejected deliberately:
 * shouldInterceptRequest never exposes the request body, so POSTs and uploads
 * would break, and hand-rolled responses break media range requests.
 *
 * Verdicts are cached and lookups fail open — a DNS problem must never make the
 * web stop loading.
 */
object DohResolver {

    private const val TAG = "OrbitDoH"
    private const val TTL_MS = 30 * 60 * 1000L
    private const val NEGATIVE_TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 2048

    enum class Verdict { ALLOW, BLOCK, UNKNOWN }

    private class Entry(val verdict: Verdict, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    @Volatile private var dns: DnsOverHttps? = null
    @Volatile private var configuredUrl: String = ""

    private val pool: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "orbit-doh").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private lateinit var cacheDir: File

    fun init(ctx: Context) {
        cacheDir = File(ctx.applicationContext.cacheDir, "doh")
    }

    private fun resolver(): DnsOverHttps? {
        val url = Prefs.dohUrl
        val existing = dns
        if (existing != null && configuredUrl == url) return existing
        return synchronized(this) {
            if (dns != null && configuredUrl == url) return@synchronized dns
            val built = try {
                val client = OkHttpClient.Builder()
                    .cache(Cache(cacheDir, 4L * 1024 * 1024))
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
                DnsOverHttps.Builder()
                    .client(client)
                    .url(url.toHttpUrl())
                    // Literal IPs, so resolving the resolver never needs system DNS.
                    .bootstrapDnsHosts(
                        InetAddress.getByName("94.140.14.14"),
                        InetAddress.getByName("94.140.15.15")
                    )
                    .includeIPv6(false)
                    .post(false)
                    .build()
            } catch (t: Throwable) {
                Log.w(TAG, "could not build DoH resolver for $url", t)
                null
            }
            dns = built
            configuredUrl = url
            cache.clear()
            built
        }
    }

    /** Drop the cached verdicts, e.g. after the user changes the DoH endpoint. */
    fun reset() {
        synchronized(this) {
            dns = null
            configuredUrl = ""
            cache.clear()
        }
    }

    /**
     * Blocking check with a short ceiling. On timeout the request is allowed and
     * the lookup keeps running in the background, so the verdict is ready the
     * next time the same host appears — which, on a real page, is immediately.
     */
    fun check(host: String, timeoutMs: Long): Verdict {
        if (host.isEmpty()) return Verdict.UNKNOWN
        cached(host)?.let { return it }
        val d = resolver() ?: return Verdict.UNKNOWN

        // Explicit Callable: submit(Runnable) and submit(Callable) would
        // otherwise be an ambiguous overload for a bare lambda.
        val task = Callable { lookup(d, host) }
        val future = try {
            pool.submit(task)
        } catch (t: Throwable) {
            return Verdict.UNKNOWN
        }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Verdict.UNKNOWN // fail open; the task still populates the cache
        }
    }

    /** Non-blocking: warm the cache for a host we expect to see again. */
    fun prefetch(host: String) {
        if (host.isEmpty() || cached(host) != null) return
        val d = resolver() ?: return
        if (inFlight.putIfAbsent(host, true) != null) return
        try {
            pool.execute { lookup(d, host) }
        } catch (_: Throwable) {
            inFlight.remove(host)
        }
    }

    private fun cached(host: String): Verdict? {
        val e = cache[host] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            cache.remove(host)
            return null
        }
        return e.verdict
    }

    private fun lookup(d: DnsOverHttps, host: String): Verdict {
        cached(host)?.let { return it }
        val verdict = try {
            val addresses = d.lookup(host)
            when {
                addresses.isEmpty() -> Verdict.BLOCK
                // AdGuard answers 0.0.0.0 / :: for a filtered domain.
                addresses.all { it.isAnyLocalAddress } -> Verdict.BLOCK
                else -> Verdict.ALLOW
            }
        } catch (e: java.net.UnknownHostException) {
            // NXDOMAIN is also how some upstreams signal a filtered domain, but
            // it is equally what a genuine typo produces. Treating it as BLOCK
            // is harmless: the request would have failed to resolve anyway.
            Verdict.BLOCK
        } catch (t: Throwable) {
            Log.d(TAG, "lookup failed for $host: ${t.javaClass.simpleName}")
            Verdict.UNKNOWN
        }

        if (verdict != Verdict.UNKNOWN) {
            if (cache.size > MAX_ENTRIES) cache.clear()
            val ttl = if (verdict == Verdict.BLOCK) TTL_MS else NEGATIVE_TTL_MS
            cache[host] = Entry(verdict, System.currentTimeMillis() + ttl)
        }
        inFlight.remove(host)
        return verdict
    }

    fun blockedCount(): Int = cache.count { it.value.verdict == Verdict.BLOCK }
}
