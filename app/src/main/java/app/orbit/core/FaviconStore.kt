package app.orbit.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import app.orbit.data.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Site icons for the start page tiles.
 *
 * Three sources, in descending order of quality, because no single one is
 * reliable on its own:
 *
 *  1. `WebChromeClient.onReceivedIcon` — Chromium hands us the real decoded
 *     favicon for any page actually visited. Free, exact, no third party, and
 *     it handles genuine .ico files that BitmapFactory cannot.
 *  2. The site's own `apple-touch-icon.png` — typically 180px, so it stays
 *     crisp at TV sizes, and fetched straight from the site itself.
 *  3. DuckDuckGo's icon service — the fallback for sites that ship neither,
 *     which is most of them. Can be turned off in Settings.
 *
 * Anything still missing falls back to the lettered monogram in home.html.
 */
object FaviconStore {

    private const val TAG = "OrbitFavicon"
    private const val MAX_PX = 96
    private const val MIN_ACCEPTED_PX = 20

    private lateinit var dir: File

    /** Hosts we already tried and failed on, so we do not refetch every time. */
    private val failed = ConcurrentHashMap<String, Boolean>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "orbit-favicon").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun init(ctx: Context) {
        dir = File(ctx.applicationContext.filesDir, "favicons")
        if (!dir.exists()) dir.mkdirs()
    }

    private fun fileFor(host: String) = File(dir, host.replace('/', '_') + ".png")

    fun has(host: String): Boolean = host.isNotEmpty() && fileFor(host).exists()

    /** Base64 data URI for the start page, or null when we have no icon. */
    fun dataUri(host: String): String? {
        if (host.isEmpty()) return null
        val f = fileFor(host)
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            if (bytes.isEmpty()) null
            else "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }

    /** Source 1: whatever Chromium decoded for a page the user actually opened. */
    fun saveFromWebView(host: String, bitmap: Bitmap?) {
        if (host.isEmpty() || bitmap == null || bitmap.width < MIN_ACCEPTED_PX) return
        if (has(host)) return
        pool.execute {
            try {
                write(host, bitmap)
                failed.remove(host)
            } catch (t: Throwable) {
                Log.d(TAG, "could not store icon for $host")
            }
        }
    }

    /**
     * Sources 2 and 3, in the background. [onDone] fires whether or not an icon
     * was found, so the caller can refresh the tiles once the batch settles.
     */
    fun ensure(host: String, onDone: () -> Unit) {
        if (host.isEmpty() || has(host) || failed.containsKey(host)) {
            onDone()
            return
        }
        if (inFlight.putIfAbsent(host, true) != null) {
            onDone()
            return
        }
        pool.execute {
            try {
                val ok = tryFetch(host)
                if (!ok) failed[host] = true
            } catch (t: Throwable) {
                failed[host] = true
            } finally {
                inFlight.remove(host)
                onDone()
            }
        }
    }

    private fun tryFetch(host: String): Boolean {
        val candidates = mutableListOf(
            "https://$host/apple-touch-icon.png",
            "https://$host/apple-touch-icon-precomposed.png"
        )
        if (Prefs.remoteIcons) {
            // Returns a PNG despite the .ico extension, and covers the many sites
            // that ship no large icon of their own.
            candidates += "https://icons.duckduckgo.com/ip3/$host.ico"
        }

        for (url in candidates) {
            val bmp = download(url) ?: continue
            if (bmp.width < MIN_ACCEPTED_PX) continue
            return try {
                write(host, bmp)
                true
            } catch (t: Throwable) {
                false
            }
        }
        return false
    }

    private fun download(url: String): Bitmap? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android TV) Orbit")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) null
            else res.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    } catch (t: Throwable) {
        null
    }

    private fun write(host: String, source: Bitmap) {
        val scaled = if (source.width > MAX_PX || source.height > MAX_PX) {
            val ratio = minOf(MAX_PX / source.width.toFloat(), MAX_PX / source.height.toFloat())
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else source

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        val tmp = File(dir, "$host.tmp")
        tmp.writeBytes(out.toByteArray())
        if (!tmp.renameTo(fileFor(host))) {
            fileFor(host).writeBytes(out.toByteArray())
            tmp.delete()
        }
        if (scaled !== source) scaled.recycle()
    }

    fun clear() {
        pool.execute {
            try {
                dir.listFiles()?.forEach { it.delete() }
                failed.clear()
            } catch (_: Throwable) {
            }
        }
    }
}
