package app.orbit.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SiteEntry(
    val url: String,
    val title: String,
    val time: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("title", title)
        .put("time", time)

    companion object {
        fun from(o: JSONObject) = SiteEntry(
            url = o.optString("url"),
            title = o.optString("title"),
            time = o.optLong("time", 0L)
        )
    }
}

/**
 * Bookmarks and history, persisted as JSON in filesDir.
 *
 * A database would be overkill: this device has 1.7 GB of RAM and the lists are
 * capped, so keeping them in memory and writing atomically on change is both
 * simpler and cheaper than pulling in Room.
 */
object Store {

    private const val TAG = "OrbitStore"
    private const val HISTORY_CAP = 400

    private const val DOWNLOAD_CAP = 60

    private lateinit var bookmarksFile: File
    private lateinit var historyFile: File
    private lateinit var downloadsFile: File

    private val _bookmarks = mutableListOf<SiteEntry>()
    private val _history = mutableListOf<SiteEntry>()
    private val _downloads = mutableListOf<SiteEntry>()

    val bookmarks: List<SiteEntry> get() = _bookmarks
    val history: List<SiteEntry> get() = _history
    val downloads: List<SiteEntry> get() = _downloads

    @Synchronized
    fun init(ctx: Context) {
        val dir = ctx.applicationContext.filesDir
        bookmarksFile = File(dir, "bookmarks.json")
        historyFile = File(dir, "history.json")
        downloadsFile = File(dir, "downloads.json")
        _bookmarks.clear()
        _bookmarks.addAll(read(bookmarksFile))
        _history.clear()
        _history.addAll(read(historyFile))
        _downloads.clear()
        _downloads.addAll(read(downloadsFile))
        if (_bookmarks.isEmpty()) {
            _bookmarks.addAll(defaultBookmarks())
            write(bookmarksFile, _bookmarks)
        }
    }

    private fun defaultBookmarks() = listOf(
        SiteEntry("https://www.youtube.com/tv", "YouTube TV"),
        SiteEntry("https://www.google.com", "Google"),
        SiteEntry("https://en.wikipedia.org", "Wikipedia"),
        SiteEntry("https://news.ycombinator.com", "Hacker News"),
        SiteEntry("https://www.bbc.com/news", "BBC News"),
        SiteEntry("https://archive.org", "Internet Archive")
    )

    @Synchronized
    fun isBookmarked(url: String): Boolean = _bookmarks.any { it.url == url }

    /** @return true if the page is bookmarked after the call. */
    @Synchronized
    fun toggleBookmark(url: String, title: String): Boolean {
        val existing = _bookmarks.indexOfFirst { it.url == url }
        val added: Boolean
        if (existing >= 0) {
            _bookmarks.removeAt(existing)
            added = false
        } else {
            _bookmarks.add(0, SiteEntry(url, title.ifBlank { url }))
            added = true
        }
        write(bookmarksFile, _bookmarks)
        return added
    }

    @Synchronized
    fun removeBookmark(url: String) {
        if (_bookmarks.removeAll { it.url == url }) write(bookmarksFile, _bookmarks)
    }

    @Synchronized
    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("file:///android_asset")) return
        _history.removeAll { it.url == url }
        _history.add(0, SiteEntry(url, title.ifBlank { url }))
        while (_history.size > HISTORY_CAP) _history.removeAt(_history.size - 1)
        write(historyFile, _history)
    }

    @Synchronized
    fun clearHistory() {
        _history.clear()
        write(historyFile, _history)
    }

    @Synchronized
    fun addDownload(fileName: String, url: String) {
        _downloads.add(0, SiteEntry(url, fileName))
        while (_downloads.size > DOWNLOAD_CAP) _downloads.removeAt(_downloads.size - 1)
        write(downloadsFile, _downloads)
    }

    @Synchronized
    fun clearDownloads() {
        _downloads.clear()
        write(downloadsFile, _downloads)
    }

    /**
     * Most-visited style ranking for the start page: recent history wins, but a
     * host that shows up repeatedly outranks a one-off visit.
     */
    @Synchronized
    fun topSites(limit: Int): List<SiteEntry> {
        val byHost = LinkedHashMap<String, Pair<SiteEntry, Int>>()
        for (e in _history) {
            val host = runCatching { android.net.Uri.parse(e.url).host }.getOrNull() ?: continue
            val cur = byHost[host]
            if (cur == null) byHost[host] = e to 1
            else byHost[host] = cur.first to (cur.second + 1)
        }
        return byHost.values
            .sortedWith(compareByDescending<Pair<SiteEntry, Int>> { it.second }
                .thenByDescending { it.first.time })
            .map { it.first }
            .take(limit)
    }

    private fun read(f: File): List<SiteEntry> = try {
        if (!f.exists()) emptyList()
        else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { SiteEntry.from(it) }
            }.filter { it.url.isNotBlank() }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "failed reading ${f.name}", t)
        emptyList()
    }

    /** Write to a temp file first so a crash mid-write cannot truncate the real one. */
    private fun write(f: File, list: List<SiteEntry>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed writing ${f.name}", t)
        }
    }
}
