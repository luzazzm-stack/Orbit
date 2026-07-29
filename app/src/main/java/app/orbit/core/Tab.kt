package app.orbit.core

import android.os.Bundle
import android.webkit.WebView

/**
 * One browser tab.
 *
 * A tab is not always backed by a live WebView. On a 1.7 GB device three or
 * four live Chromium renderers is enough to start thrashing, so TabManager
 * freezes the least recently used tabs: their navigation state is saved into
 * [savedState] and the WebView is destroyed. Waking a tab restores it.
 */
class Tab(val id: Int) {

    var webView: WebView? = null
    var savedState: Bundle? = null

    var url: String = UrlUtils.HOME_URL
    var title: String = "New tab"

    /**
     * URL to load the first time this tab gets a WebView. Left null for tabs
     * created by window.open(), where Chromium supplies the navigation itself
     * through a WebViewTransport and loading anything here would clobber it.
     */
    var pendingLoad: String? = UrlUtils.HOME_URL

    /** Cursor mode is per-tab: a video site wants a pointer, an article does not. */
    var cursorMode: Boolean = false

    var lastUsed: Long = System.currentTimeMillis()

    val isLive: Boolean get() = webView != null

    val displayTitle: String
        get() = when {
            title.isNotBlank() && title != "about:blank" -> title
            UrlUtils.isHome(url) -> "Start"
            else -> UrlUtils.prettyHost(url).ifBlank { "New tab" }
        }
}
