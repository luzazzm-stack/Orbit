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
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AdGuard DNS filtering, inside the browser.
 *
 * Android's WebView gives no hook to change Chromium's DNS resolver, so the
 * usual "point the browser at DoH" approach is not available. Instead this
 * resolves each host against AdGuard's DoH endpoint and uses the *answer* as a
 * verdict: AdGuard replies 0.0.0.0 (or ::) for ad, tracker and malware domains,
 * so those requests get dropped in shouldInterceptRequest while everything else
 * continues to load over WebView's own network stack.
 *
 * Proxying every request through OkHttp instead was rejected deliberately:
 * shouldInterceptRequest never exposes the request body, so POSTs and uploads
 * would break, and hand-rolled responses break media range requests.
 *
 * **Failing open is the load-bearing invariant here** and it is enforced in one
 * place: only an explicit "this address is 0.0.0.0" answer ever produces BLOCK.
 * Every error path — timeout, TLS failure, no network, NXDOMAIN — resolves to
 * UNKNOWN, which allows the request.
 */
object DohResolver {

    private const val TAG = "OrbitDoH"
    private const val TTL_MS = 30 * 60 * 1000L
    private const val ALLOW_TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 2048

    /** Consecutive transport failures before DoH is paused entirely. */
    private const val FAILURE_THRESHOLD = 4
    private const val BREAKER_MS = 60_000L

    enum class Verdict { ALLOW, BLOCK, UNKNOWN }

    private class Entry(val verdict: Verdict, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * One shared task per host. Both check() and prefetch() go through this, so
     * the twenty concurrent subresource requests a page makes for the same host
     * wait on a single lookup instead of queueing twenty behind a small pool.
     */
    private val inFlight = ConcurrentHashMap<String, FutureTask<Verdict>>()

    private val consecutiveFailures = AtomicInteger(0)

    @Volatile private var breakerUntil = 0L
    @Volatile private var dns: DnsOverHttps? = null
    @Volatile private var configuredUrl: String = ""

    private val pool: ExecutorService = Executors.newFixedThreadPool(4) { r ->
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

    /** Drop cached verdicts, e.g. after the user changes the DoH endpoint. */
    fun reset() {
        synchronized(this) {
            dns = null
            configuredUrl = ""
            cache.clear()
            inFlight.clear()
            consecutiveFailures.set(0)
            breakerUntil = 0L
        }
    }

    /**
     * True while DoH is paused after repeated transport failures. Without this,
     * a dead resolver would cost every request its full timeout, turning a
     * network blip into a browser-wide stall.
     */
    private fun breakerOpen(): Boolean = System.currentTimeMillis() < breakerUntil

    /**
     * Blocking check with a short ceiling. On timeout the request is allowed and
     * the shared lookup keeps running, so the verdict is ready the next time the
     * same host appears — which, on a real page, is immediately.
     */
    fun check(host: String, timeoutMs: Long): Verdict {
        if (host.isEmpty()) return Verdict.UNKNOWN
        cached(host)?.let { return it }
        if (breakerOpen()) return Verdict.UNKNOWN
        val d = resolver() ?: return Verdict.UNKNOWN
        val task = submit(d, host) ?: return Verdict.UNKNOWN
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Verdict.UNKNOWN // fail open; the task still populates the cache
        }
    }

    /** Non-blocking: warm the cache for a host we expect to see again. */
    fun prefetch(host: String) {
        if (host.isEmpty() || cached(host) != null || breakerOpen()) return
        val d = resolver() ?: return
        submit(d, host)
    }

    /** @return the task resolving [host], creating it only if none is running. */
    private fun submit(d: DnsOverHttps, host: String): Future<Verdict>? {
        inFlight[host]?.let { return it }
        // Explicit Callable: FutureTask(Callable) and FutureTask(Runnable, V)
        // are otherwise an ambiguous overload for a bare lambda.
        val task = FutureTask(Callable { lookup(d, host) })
        val running = inFlight.putIfAbsent(host, task)
        if (running != null) return running
        return try {
            pool.execute(task)
            task
        } catch (t: Throwable) {
            inFlight.remove(host)
            null
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
        try {
            cached(host)?.let { return it }

            val verdict = try {
                val addresses = d.lookup(host)
                when {
                    // AdGuard answers 0.0.0.0 / :: for a filtered domain. That
                    // positive signal is the ONLY thing that may produce BLOCK.
                    addresses.isNotEmpty() && addresses.all { it.isAnyLocalAddress } ->
                        Verdict.BLOCK
                    addresses.isNotEmpty() -> Verdict.ALLOW
                    // An empty answer is ambiguous — an IPv6-only host looks
                    // exactly like this once includeIPv6(false) drops AAAA.
                    else -> Verdict.UNKNOWN
                }
            } catch (t: Throwable) {
                // OkHttp's DnsOverHttps funnels EVERY failure - connect, TLS,
                // timeout, no network - into UnknownHostException, so it cannot
                // be read as "this domain does not exist", let alone as a block.
                // Treating it as BLOCK once turned a brief Wi-Fi drop into a
                // half-hour of empty responses for every subresource.
                Log.d(TAG, "lookup failed for $host: ${t.javaClass.simpleName}")
                Verdict.UNKNOWN
            }

            if (verdict == Verdict.UNKNOWN) {
                if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
                    breakerUntil = System.currentTimeMillis() + BREAKER_MS
                    consecutiveFailures.set(0)
                    Log.i(TAG, "DoH paused for ${BREAKER_MS / 1000}s after repeated failures")
                }
            } else {
                consecutiveFailures.set(0)
                if (cache.size > MAX_ENTRIES) cache.clear()
                val ttl = if (verdict == Verdict.BLOCK) TTL_MS else ALLOW_TTL_MS
                cache[host] = Entry(verdict, System.currentTimeMillis() + ttl)
            }
            return verdict
        } finally {
            inFlight.remove(host)
        }
    }

    fun blockedCount(): Int = cache.count { it.value.verdict == Verdict.BLOCK }
}
