package app.orbit.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.orbit.R
import app.orbit.core.AdBlocker
import app.orbit.core.TabManager
import app.orbit.core.UrlUtils
import app.orbit.data.Prefs
import app.orbit.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The right-hand panel: tabs, bookmarks, history, downloads and settings.
 *
 * Everything is rendered through one row type so the D-pad behaves identically
 * in every section — one list, one focus model, no surprises.
 */
class PanelController(
    private val host: FrameLayout,
    private val tabs: TabManager,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun openUrl(url: String)
        fun onPanelClosed()
        fun toast(message: String)
        fun onSettingsChanged(reload: Boolean)
        fun onFindInPage()
        fun onReaderMode()
        fun onNewPrivateTab()
        fun onClearCookiesAndCache()
        fun onOpenSystemDownloads()
        fun isPrivate(): Boolean
    }

    enum class Section { TABS, BOOKMARKS, HISTORY, SETTINGS, DOWNLOADS }

    private val root: View =
        LayoutInflater.from(host.context).inflate(R.layout.view_panel, host, false)

    private val list: RecyclerView = root.findViewById(R.id.panelList)
    private val empty: TextView = root.findViewById(R.id.panelEmpty)
    private val primary: TextView = root.findViewById(R.id.btnPanelPrimary)
    private val secondary: TextView = root.findViewById(R.id.btnPanelSecondary)

    private val tabButtons: Map<Section, TextView> = mapOf(
        Section.TABS to root.findViewById<TextView>(R.id.tabTabs),
        Section.BOOKMARKS to root.findViewById<TextView>(R.id.tabBookmarks),
        Section.HISTORY to root.findViewById<TextView>(R.id.tabHistory),
        Section.SETTINGS to root.findViewById<TextView>(R.id.tabSettings)
    )

    private val adapter = PanelAdapter()
    private var section = Section.TABS

    val isOpen: Boolean get() = host.visibility == View.VISIBLE

    private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    init {
        host.addView(root)
        list.layoutManager = LinearLayoutManager(host.context)
        list.adapter = adapter
        list.itemAnimator = null
        tabButtons.forEach { (sec, btn) -> btn.setOnClickListener { show(sec) } }
    }

    fun open(sec: Section = section) {
        host.visibility = View.VISIBLE
        show(sec)
        host.post { tabButtons[section]?.requestFocus() ?: list.requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        host.visibility = View.GONE
        cb.onPanelClosed()
    }

    fun refresh() {
        if (isOpen) show(section)
    }

    private fun show(sec: Section) {
        section = sec
        tabButtons.forEach { (s, btn) -> btn.isSelected = s == sec }

        val items = when (sec) {
            Section.TABS -> tabItems()
            Section.BOOKMARKS -> bookmarkItems()
            Section.HISTORY -> historyItems()
            Section.DOWNLOADS -> downloadItems()
            Section.SETTINGS -> settingsItems()
        }
        adapter.submit(items)
        list.scrollToPosition(0)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        when (sec) {
            Section.TABS -> footer(
                "New tab" to {
                    if (tabs.newTab() == null) cb.toast(str(R.string.tab_limit)) else close()
                },
                "Private tab" to { cb.onNewPrivateTab(); close() }
            )
            Section.HISTORY -> footer(
                null,
                "Clear history" to {
                    Store.clearHistory()
                    cb.toast(str(R.string.history_cleared))
                    refresh()
                }
            )
            Section.DOWNLOADS -> footer(
                "System downloads" to { cb.onOpenSystemDownloads() },
                "Clear list" to { Store.clearDownloads(); refresh() }
            )
            else -> footer(null, null)
        }
    }

    private fun footer(first: Pair<String, () -> Unit>?, second: Pair<String, () -> Unit>?) {
        if (first != null) {
            primary.visibility = View.VISIBLE
            primary.text = first.first
            primary.setOnClickListener { first.second() }
        } else primary.visibility = View.GONE

        if (second != null) {
            secondary.visibility = View.VISIBLE
            secondary.text = second.first
            secondary.setOnClickListener { second.second() }
        } else secondary.visibility = View.GONE
    }

    // ---------------------------------------------------------------- content

    private fun tabItems(): List<PanelItem> = tabs.all.mapIndexed { index, tab ->
        PanelItem.Row(
            title = tab.displayTitle,
            subtitle = if (UrlUtils.isHome(tab.url)) "Start page"
            else UrlUtils.prettyHost(tab.url).ifBlank { tab.url },
            iconRes = if (index == tabs.activeIndex) R.drawable.ic_cursor else R.drawable.ic_globe,
            value = if (tab.isLive) "" else "sleeping",
            actionIcon = R.drawable.ic_close,
            onClick = { tabs.selectTab(tab); close() },
            onAction = { tabs.close(tab); refresh() }
        )
    }

    private fun bookmarkItems(): List<PanelItem> = Store.bookmarks.map { entry ->
        PanelItem.Row(
            title = entry.title,
            subtitle = UrlUtils.prettyHost(entry.url),
            iconRes = R.drawable.ic_star,
            actionIcon = R.drawable.ic_close,
            onClick = { cb.openUrl(entry.url); close() },
            onAction = {
                Store.removeBookmark(entry.url)
                cb.toast(str(R.string.bookmark_removed))
                refresh()
            }
        )
    }

    private fun historyItems(): List<PanelItem> = Store.history.take(120).map { entry ->
        PanelItem.Row(
            title = entry.title,
            subtitle = UrlUtils.prettyHost(entry.url) + "  ·  " + dateFmt.format(Date(entry.time)),
            iconRes = R.drawable.ic_history,
            onClick = { cb.openUrl(entry.url); close() }
        )
    }

    private fun downloadItems(): List<PanelItem> = Store.downloads.map { entry ->
        PanelItem.Row(
            title = entry.title,
            subtitle = UrlUtils.prettyHost(entry.url) + "  ·  " + dateFmt.format(Date(entry.time)),
            iconRes = R.drawable.ic_download,
            onClick = { cb.onOpenSystemDownloads() }
        )
    }

    private fun settingsItems(): List<PanelItem> {
        val out = mutableListOf<PanelItem>()

        // ---- actions on the current page -----------------------------------
        out += PanelItem.Header("This page")
        out += PanelItem.Row(
            title = "Find in page",
            iconRes = R.drawable.ic_search,
            onClick = { close(); cb.onFindInPage() }
        )
        out += PanelItem.Row(
            title = "Reader mode",
            subtitle = "Strip the page down to the article",
            iconRes = R.drawable.ic_reader,
            onClick = { close(); cb.onReaderMode() }
        )
        out += PanelItem.Row(
            title = "Desktop site",
            subtitle = "TV screens fit desktop layouts better than phone ones",
            iconRes = R.drawable.ic_desktop,
            value = onOff(Prefs.desktopUa),
            onClick = { Prefs.desktopUa = !Prefs.desktopUa; refresh(); cb.onSettingsChanged(true) }
        )

        // ---- privacy and filtering -----------------------------------------
        out += PanelItem.Header("Privacy and filtering")
        out += PanelItem.Row(
            title = "AdGuard DNS filtering",
            subtitle = "Blocks ads and trackers over encrypted DNS",
            iconRes = R.drawable.ic_dns,
            value = onOff(Prefs.doh),
            onClick = { Prefs.doh = !Prefs.doh; refresh() }
        )
        out += PanelItem.Row(
            title = "DNS provider",
            subtitle = Prefs.dohUrl,
            iconRes = R.drawable.ic_lock,
            value = Prefs.dohProviderName,
            onClick = {
                val keys = Prefs.dohProviders.keys.toList()
                val idx = keys.indexOf(Prefs.dohProviderName).coerceAtLeast(0)
                Prefs.dohUrl = Prefs.dohProviders.getValue(keys[(idx + 1) % keys.size])
                app.orbit.core.DohResolver.reset()
                refresh()
            }
        )
        out += PanelItem.Row(
            title = "Built-in blocklist",
            subtitle = "${AdBlocker.localListSize()} hosts, applied instantly",
            iconRes = R.drawable.ic_shield,
            value = onOff(Prefs.adBlock),
            onClick = { Prefs.adBlock = !Prefs.adBlock; refresh() }
        )
        out += PanelItem.Row(
            title = "Third-party cookies",
            iconRes = R.drawable.ic_cookie,
            value = if (Prefs.thirdPartyCookies) "Allowed" else "Blocked",
            onClick = {
                Prefs.thirdPartyCookies = !Prefs.thirdPartyCookies
                refresh()
                cb.onSettingsChanged(false)
            }
        )
        out += PanelItem.Row(
            title = "HTTPS only",
            subtitle = "Refuse plain http pages",
            iconRes = R.drawable.ic_lock,
            value = onOff(Prefs.httpsOnly),
            onClick = { Prefs.httpsOnly = !Prefs.httpsOnly; refresh() }
        )
        out += PanelItem.Row(
            title = "New private tab",
            subtitle = "No history, cookies cleared on close",
            iconRes = R.drawable.ic_incognito,
            onClick = { close(); cb.onNewPrivateTab() }
        )

        // ---- display ---------------------------------------------------------
        out += PanelItem.Header("Display")
        out += PanelItem.Row(
            title = "Text size",
            iconRes = R.drawable.ic_text_size,
            value = "${Prefs.textZoom}%",
            onClick = {
                val steps = listOf(90, 100, 110, 125, 150, 175, 200)
                val cur = steps.indexOf(Prefs.textZoom)
                Prefs.textZoom = steps[(if (cur < 0) 0 else cur + 1) % steps.size]
                refresh()
                cb.onSettingsChanged(false)
            }
        )
        out += PanelItem.Row(
            title = "Load images",
            subtitle = "Turning this off makes heavy sites much faster here",
            iconRes = R.drawable.ic_image,
            value = onOff(Prefs.loadImages),
            onClick = { Prefs.loadImages = !Prefs.loadImages; refresh(); cb.onSettingsChanged(false) }
        )
        out += PanelItem.Row(
            title = "Dark web pages",
            subtitle = "Force a dark theme on sites that have none",
            iconRes = R.drawable.ic_moon,
            value = onOff(Prefs.forceDark),
            onClick = { Prefs.forceDark = !Prefs.forceDark; refresh(); cb.onSettingsChanged(false) }
        )
        out += PanelItem.Row(
            title = "JavaScript",
            iconRes = R.drawable.ic_globe,
            value = onOff(Prefs.javaScript),
            onClick = { Prefs.javaScript = !Prefs.javaScript; refresh(); cb.onSettingsChanged(true) }
        )

        // ---- search and remote ----------------------------------------------
        out += PanelItem.Header("Search and remote")
        out += PanelItem.Row(
            title = "Search engine",
            iconRes = R.drawable.ic_search,
            value = Prefs.searchEngine.replaceFirstChar { it.uppercase() },
            onClick = {
                val keys = Prefs.searchEngines.keys.toList()
                Prefs.searchEngine = keys[(keys.indexOf(Prefs.searchEngine) + 1) % keys.size]
                refresh()
            }
        )
        out += PanelItem.Row(
            title = "Pointer speed",
            iconRes = R.drawable.ic_cursor,
            value = Prefs.cursorSpeed.toString(),
            onClick = {
                Prefs.cursorSpeed = if (Prefs.cursorSpeed >= 5) 1 else Prefs.cursorSpeed + 1
                refresh()
            }
        )
        out += PanelItem.Row(
            title = "Start pages in pointer mode",
            iconRes = R.drawable.ic_cursor,
            value = onOff(Prefs.defaultCursorMode),
            onClick = { Prefs.defaultCursorMode = !Prefs.defaultCursorMode; refresh() }
        )
        out += PanelItem.Row(
            title = "Smooth scrolling",
            iconRes = R.drawable.ic_reload,
            value = onOff(Prefs.smoothScroll),
            onClick = { Prefs.smoothScroll = !Prefs.smoothScroll; refresh(); cb.onSettingsChanged(false) }
        )

        // ---- data -------------------------------------------------------------
        out += PanelItem.Header("Data")
        out += PanelItem.Row(
            title = "Downloads",
            subtitle = "${Store.downloads.size} files",
            iconRes = R.drawable.ic_download,
            onClick = { show(Section.DOWNLOADS) }
        )
        out += PanelItem.Row(
            title = "Look up missing site icons",
            subtitle = "Icons from pages you open are always saved locally",
            iconRes = R.drawable.ic_image,
            value = onOff(Prefs.remoteIcons),
            onClick = { Prefs.remoteIcons = !Prefs.remoteIcons; refresh() }
        )
        out += PanelItem.Row(
            title = "Clear site icons",
            iconRes = R.drawable.ic_trash,
            onClick = {
                app.orbit.core.FaviconStore.clear()
                cb.toast("Site icons cleared")
            }
        )
        out += PanelItem.Row(
            title = "Clear cookies and cache",
            iconRes = R.drawable.ic_cookie,
            onClick = { cb.onClearCookiesAndCache(); refresh() }
        )
        out += PanelItem.Row(
            title = "Clear history",
            subtitle = "${Store.history.size} entries",
            iconRes = R.drawable.ic_trash,
            onClick = {
                Store.clearHistory()
                cb.toast(str(R.string.history_cleared))
                refresh()
            }
        )
        return out
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun str(res: Int) = host.context.getString(res)
}
