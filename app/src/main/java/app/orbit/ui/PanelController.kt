package app.orbit.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.orbit.R
import app.orbit.core.TabManager
import app.orbit.core.UrlUtils
import app.orbit.data.Prefs
import app.orbit.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The right-hand panel: tabs, bookmarks, history and settings.
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
    }

    enum class Section { TABS, BOOKMARKS, HISTORY, SETTINGS }

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

        tabButtons.forEach { (sec, btn) ->
            btn.setOnClickListener { show(sec) }
        }
    }

    fun open(sec: Section = section) {
        host.visibility = View.VISIBLE
        show(sec)
        host.post {
            tabButtons[section]?.requestFocus() ?: list.requestFocus()
        }
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
            Section.SETTINGS -> settingsItems()
        }
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        when (sec) {
            Section.TABS -> footer(
                R.string.cd_new_tab to {
                    if (tabs.newTab() == null) cb.toast(str(R.string.tab_limit))
                    else close()
                },
                null
            )
            Section.HISTORY -> footer(
                null,
                R.string.panel_history to {
                    Store.clearHistory()
                    cb.toast(str(R.string.history_cleared))
                    refresh()
                }
            )
            else -> footer(null, null)
        }
    }

    private fun footer(
        first: Pair<Int, () -> Unit>?,
        second: Pair<Int, () -> Unit>?
    ) {
        if (first != null) {
            primary.visibility = View.VISIBLE
            primary.setText(first.first)
            primary.setOnClickListener { first.second() }
        } else primary.visibility = View.GONE

        if (second != null) {
            secondary.visibility = View.VISIBLE
            secondary.text = "Clear " + str(second.first).lowercase(Locale.getDefault())
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
            onClick = {
                tabs.selectTab(tab)
                close()
            },
            onAction = {
                tabs.close(tab)
                refresh()
            }
        )
    }

    private fun bookmarkItems(): List<PanelItem> = Store.bookmarks.map { entry ->
        PanelItem.Row(
            title = entry.title,
            subtitle = UrlUtils.prettyHost(entry.url),
            iconRes = R.drawable.ic_star,
            actionIcon = R.drawable.ic_close,
            onClick = {
                cb.openUrl(entry.url)
                close()
            },
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
            onClick = {
                cb.openUrl(entry.url)
                close()
            }
        )
    }

    private fun settingsItems(): List<PanelItem> {
        val out = mutableListOf<PanelItem>()

        out += PanelItem.Header("Page")
        out += PanelItem.Row(
            title = "Search engine",
            iconRes = R.drawable.ic_search,
            value = Prefs.searchEngine.replaceFirstChar { it.uppercase() },
            onClick = {
                val keys = Prefs.searchEngines.keys.toList()
                val next = (keys.indexOf(Prefs.searchEngine) + 1) % keys.size
                Prefs.searchEngine = keys[next]
                refresh()
            }
        )
        out += PanelItem.Row(
            title = "Desktop site",
            subtitle = "TV screens fit desktop layouts better than phone ones",
            iconRes = R.drawable.ic_desktop,
            value = onOff(Prefs.desktopUa),
            onClick = {
                Prefs.desktopUa = !Prefs.desktopUa
                refresh()
                cb.onSettingsChanged(true)
            }
        )
        out += PanelItem.Row(
            title = "Text size",
            iconRes = R.drawable.ic_text_size,
            value = "${Prefs.textZoom}%",
            onClick = {
                val steps = listOf(90, 100, 110, 125, 150, 175)
                val next = steps[(steps.indexOf(Prefs.textZoom).coerceAtLeast(0) + 1) % steps.size]
                Prefs.textZoom = next
                refresh()
                cb.onSettingsChanged(false)
            }
        )
        out += PanelItem.Row(
            title = "Block ads and trackers",
            subtitle = "Also makes heavy pages usable on this box",
            iconRes = R.drawable.ic_shield,
            value = onOff(Prefs.adBlock),
            onClick = {
                Prefs.adBlock = !Prefs.adBlock
                refresh()
            }
        )

        out += PanelItem.Header("Remote")
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
            subtitle = "Otherwise pages open in link mode",
            iconRes = R.drawable.ic_cursor,
            value = onOff(Prefs.defaultCursorMode),
            onClick = {
                Prefs.defaultCursorMode = !Prefs.defaultCursorMode
                refresh()
            }
        )
        out += PanelItem.Row(
            title = "Smooth scrolling",
            iconRes = R.drawable.ic_reload,
            value = onOff(Prefs.smoothScroll),
            onClick = {
                Prefs.smoothScroll = !Prefs.smoothScroll
                refresh()
                cb.onSettingsChanged(false)
            }
        )

        out += PanelItem.Header("Data")
        out += PanelItem.Row(
            title = "Clear history",
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
