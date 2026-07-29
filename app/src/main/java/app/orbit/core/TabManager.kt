package app.orbit.core

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Owns every tab and, more importantly, owns how much memory they are allowed
 * to use. Each live WebView costs a Chromium renderer process; this box has
 * 1.7 GB total, so we keep at most [MAX_LIVE] of them and freeze the rest.
 */
class TabManager(
    private val ctx: Context,
    private val container: FrameLayout,
    private val configure: (WebView) -> Unit,
    private val onChanged: () -> Unit
) {

    companion object {
        private const val TAG = "OrbitTabs"
        private const val MAX_LIVE = 3
        const val MAX_TABS = 8
    }

    private val tabs = mutableListOf<Tab>()
    private var nextId = 1

    var activeIndex: Int = -1
        private set

    val all: List<Tab> get() = tabs
    val count: Int get() = tabs.size
    val active: Tab? get() = tabs.getOrNull(activeIndex)
    val activeWebView: WebView? get() = active?.webView

    fun newTab(url: String? = null, select: Boolean = true): Tab? {
        if (tabs.size >= MAX_TABS) return null
        val tab = Tab(nextId++)
        tab.url = url ?: UrlUtils.HOME_URL
        tab.pendingLoad = tab.url
        tabs.add(tab)
        if (select) select(tabs.size - 1) else onChanged()
        return tab
    }

    fun newPrivateTab(url: String? = null): Tab? {
        val tab = newTab(url) ?: return null
        tab.isPrivate = true
        onChanged()
        return tab
    }

    val hasPrivateTabs: Boolean get() = tabs.any { it.isPrivate }

    /**
     * A tab that comes up empty, for window.open(): Chromium drives the first
     * navigation itself, so loading a start page here would race it.
     */
    fun newBlankTab(inheritPrivacyFrom: Tab? = null): Tab? {
        if (tabs.size >= MAX_TABS) return null
        val tab = Tab(nextId++)
        tab.url = "about:blank"
        tab.title = "New tab"
        tab.pendingLoad = null
        // A window.open() popup inherits the opener's privacy, otherwise a
        // target=_blank link would silently drop the user out of private mode.
        tab.isPrivate = inheritPrivacyFrom?.isPrivate == true
        tabs.add(tab)
        select(tabs.size - 1)
        return tab
    }

    fun select(index: Int) {
        if (index !in tabs.indices) return
        if (index == activeIndex && tabs[index].isLive) {
            tabs[index].lastUsed = System.currentTimeMillis()
            return
        }

        // Detach whatever is on screen, but leave it alive so going back to it
        // is instant and keeps scroll position.
        active?.webView?.let { detach(it) }

        activeIndex = index
        val tab = tabs[index]
        tab.lastUsed = System.currentTimeMillis()

        val wv = tab.webView ?: wake(tab)
        attach(wv)
        trimLive()
        onChanged()
    }

    fun selectTab(tab: Tab) = select(tabs.indexOf(tab))

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tab.webView?.let { destroy(it) }
        tab.webView = null
        tab.savedState = null
        tabs.removeAt(idx)

        if (tabs.isEmpty()) {
            activeIndex = -1
            newTab()
            return
        }
        activeIndex = when {
            idx < activeIndex -> activeIndex - 1
            idx == activeIndex -> idx.coerceAtMost(tabs.size - 1)
            else -> activeIndex
        }
        val target = activeIndex.coerceIn(0, tabs.size - 1)
        activeIndex = -1
        select(target)
    }

    fun closeActive() { active?.let { close(it) } }

    /**
     * Reclaim the memory of every background tab **without losing them**.
     *
     * This is what a low-memory warning should do: the renderer processes go
     * away, but each tab keeps its saved state and reappears in the tab list,
     * so pressure on a 1.7 GB box never silently eats the user's open pages.
     */
    fun freezeAllButActive(): Int {
        val victims = tabs.filter { it !== active && it.isLive }
        victims.forEach { freeze(it) }
        if (victims.isNotEmpty()) onChanged()
        return victims.size
    }

    /** Persist the live tab's scroll/navigation state before the app is backgrounded. */
    fun saveActiveState() {
        val tab = active ?: return
        val wv = tab.webView ?: return
        val b = Bundle()
        if (wv.saveState(b) != null) {
            tab.savedState = b
            tab.url = wv.url ?: tab.url
        }
    }

    fun destroyAll() {
        tabs.forEach { t ->
            t.webView?.let { destroy(it) }
            t.webView = null
        }
        tabs.clear()
        activeIndex = -1
    }

    fun notifyChanged() = onChanged()

    // ---------------------------------------------------------------- private

    private fun wake(tab: Tab): WebView {
        val wv = WebViewFactory.create(ctx)
        configure(wv)
        tab.webView = wv
        val state = tab.savedState
        if (state != null && wv.restoreState(state) != null) {
            Log.i(TAG, "restored tab ${tab.id}")
        } else {
            // Fall back to the tab's URL, not just pendingLoad: pendingLoad is
            // cleared after the first wake, so a tab whose saved state failed to
            // restore would otherwise come back as a permanently blank screen
            // that only retyping the address by remote could recover.
            val url = tab.pendingLoad ?: tab.url.takeUnless { it == "about:blank" }
            url?.let { wv.loadUrl(it) }
        }
        tab.pendingLoad = null
        return wv
    }

    private fun attach(wv: WebView) {
        if (wv.parent == null) {
            container.addView(
                wv, 0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        wv.onResume()
        @Suppress("DEPRECATION")
        wv.resumeTimers()
        wv.visibility = WebView.VISIBLE
        wv.requestFocus()
    }

    private fun detach(wv: WebView) {
        wv.onPause()
        (wv.parent as? ViewGroup)?.removeView(wv)
    }

    private fun destroy(wv: WebView) {
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.removeAllViews()
            wv.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "destroy failed", t)
        }
    }

    /** Freeze the least recently used live tabs until only MAX_LIVE remain. */
    private fun trimLive() {
        val live = tabs.filter { it.isLive }
        if (live.size <= MAX_LIVE) return
        live.sortedBy { it.lastUsed }
            .take(live.size - MAX_LIVE)
            .filter { it !== active }
            .forEach { freeze(it) }
    }

    private fun freeze(tab: Tab) {
        val wv = tab.webView ?: return
        // Capture the URL before destroying the WebView, so the tab is
        // reloadable even when there is no usable saved state.
        tab.url = wv.url ?: tab.url
        val b = Bundle()
        // saveState returns null (leaving the Bundle empty) when the history is
        // too large or absent. Keeping that Bundle would make restoreState fail
        // later with nothing to fall back on, so only keep a real one.
        tab.savedState = if (wv.saveState(b) != null) b else null
        destroy(wv)
        tab.webView = null
        Log.i(TAG, "froze tab ${tab.id} (${tab.displayTitle})")
    }
}
