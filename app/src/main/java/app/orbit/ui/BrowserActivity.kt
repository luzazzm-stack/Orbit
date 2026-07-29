package app.orbit.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import app.orbit.R
import app.orbit.core.AdBlocker
import app.orbit.core.DohResolver
import app.orbit.core.TabManager
import app.orbit.core.UrlUtils
import app.orbit.core.WebViewFactory
import app.orbit.data.Prefs
import app.orbit.data.Store
import app.orbit.databinding.ActivityBrowserBinding
import app.orbit.input.CursorController
import app.orbit.input.CursorOverlay
import app.orbit.input.SpatialNav
import org.json.JSONArray
import org.json.JSONObject

class BrowserActivity : AppCompatActivity(), PanelController.Callbacks {

    private lateinit var b: ActivityBrowserBinding
    private lateinit var tabs: TabManager
    private lateinit var overlay: CursorOverlay
    private lateinit var cursor: CursorController
    private lateinit var spatial: SpatialNav
    private lateinit var panel: PanelController

    private val ui = Handler(Looper.getMainLooper())

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var centerLongFired = false
    private var lastBackAt = 0L
    private var hadPrivateTabs = false

    private val hideToolbarTask = Runnable { hideToolbar() }
    private val hideToastTask = Runnable { b.toast.visibility = View.GONE }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (res.resultCode == RESULT_OK && !text.isNullOrBlank()) {
            navigate(UrlUtils.toUrlOrSearch(text))
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        spatial = SpatialNav(this)

        overlay = CursorOverlay(this)
        overlay.visibility = View.GONE
        b.cursorHost.addView(
            overlay,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        cursor = CursorController(overlay) { tabs.activeWebView }

        tabs = TabManager(
            ctx = this,
            container = b.webContainer,
            configure = { configureWebView(it) },
            onChanged = { onTabsChanged() }
        )

        panel = PanelController(b.panelHost, tabs, this)

        wireToolbar()
        wireFindBar()

        val initial = intent?.dataString?.takeIf { UrlUtils.isHttp(it) }
        tabs.newTab(initial ?: startUrl())
        // Deliberately unfocused: focusing the address bar at launch drags the
        // leanback keyboard up over the whole screen before the user has asked
        // for it. Focus lands on the page instead, via spatial navigation.
        showToolbar(focus = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { UrlUtils.isHttp(it) }?.let { navigate(it) }
    }

    private fun startUrl(): String =
        if (Prefs.startMode == "url" && Prefs.homeUrl.isNotBlank()) Prefs.homeUrl
        else UrlUtils.HOME_URL

    override fun onPause() {
        super.onPause()
        tabs.saveActiveState()
        tabs.activeWebView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs.activeWebView?.onResume()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        tabs.destroyAll()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 1.7 GB box: shed every tab that is not on screen before the system
        // decides to kill the whole process.
        tabs.closeAllButActive()
        toast("Freed memory — background tabs closed")
    }

    // ------------------------------------------------------------ web client

    private fun configureWebView(wv: WebView) {
        wv.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!Prefs.adBlock && !Prefs.doh) return null
                val url = request.url?.toString() ?: return null
                // Runs on a Chromium worker thread, never the UI thread, so the
                // bounded DoH wait inside shouldBlock cannot jank the interface.
                return if (AdBlocker.shouldBlock(url, request.isForMainFrame)) {
                    AdBlocker.blockedResponse()
                } else null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url?.toString() ?: return false
                if (upgradeToHttps(view, url)) return true
                return handleNonWebScheme(url)
            }

            @Deprecated("Kept for API < 24 devices")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (upgradeToHttps(view, url)) return true
                return handleNonWebScheme(url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                b.progress.visibility = View.VISIBLE
                b.progress.progress = 5
                setOmniboxText(url)
                tabs.active?.url = url
                updateBookmarkIcon(url)
                closeFindBar()
                // Warm the DNS verdict for the page's own host while it loads,
                // so its subresources hit a populated cache.
                if (Prefs.doh) DohResolver.prefetch(UrlUtils.host(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                b.progress.visibility = View.GONE
                spatial.inject(view)
                if (UrlUtils.isHome(url)) {
                    injectHomeData(view)
                    // Give the start page a visible selection straight away so
                    // it never looks inert on launch.
                    if (!cursor.enabled && !focusInChrome()) {
                        view.requestFocus()
                        view.postDelayed({ spatial.enter(view) }, 80)
                    }
                }
                tabs.active?.let { tab ->
                    tab.url = url
                    tab.title = view.title ?: tab.title
                }
                if (!UrlUtils.isHome(url) && tabs.active?.isPrivate != true) {
                    Store.addHistory(url, view.title ?: "")
                }
                setOmniboxText(url)
                updateNavButtons()
                updateBookmarkIcon(url)
                onTabsChanged()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Single-page apps navigate without a page load; re-inject so the
                // spatial engine sees the new DOM.
                tabs.active?.url = url
                setOmniboxText(url)
                updateNavButtons()
                updateBookmarkIcon(url)
                view.postDelayed({ spatial.inject(view) }, 220)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    b.progress.visibility = View.GONE
                    toast("Could not load page")
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.progress.progress = newProgress
                b.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tabs.active?.title = title ?: ""
                onTabsChanged()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val tab = tabs.newBlankTab()
                val target = tab?.webView
                if (target == null) {
                    toast(getString(R.string.tab_limit))
                    return false
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = target
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.all.firstOrNull { it.webView === window }?.let { tabs.close(it) }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Nothing in this browser needs the camera or microphone.
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    /**
     * Hand the start page its shortcuts and current search engine.
     *
     * Done by evaluating JSON rather than with addJavascriptInterface: a JS
     * bridge would be reachable from every page the browser ever loads, which
     * is a needless attack surface for a feature only the start page uses.
     */
    private fun injectHomeData(wv: WebView) {
        val sites = JSONArray()
        val seen = HashSet<String>()

        Store.bookmarks.take(6).forEach { entry ->
            if (seen.add(entry.url)) {
                sites.put(JSONObject().put("title", entry.title).put("url", entry.url))
            }
        }
        Store.topSites(6).forEach { entry ->
            if (sites.length() >= 6) return@forEach
            if (seen.add(entry.url)) {
                sites.put(JSONObject().put("title", entry.title).put("url", entry.url))
            }
        }

        val payload = JSONObject()
            .put("searchUrl", Prefs.searchUrlTemplate)
            .put("sites", sites)

        wv.evaluateJavascript("window.orbitInit && window.orbitInit($payload);", null)
    }

    /** HTTPS-only mode: retry plain http navigations over TLS instead. */
    private fun upgradeToHttps(view: WebView, url: String): Boolean {
        if (!Prefs.httpsOnly || !url.startsWith("http://")) return false
        if (url.startsWith("http://localhost") || url.startsWith("http://127.")) return false
        view.loadUrl("https://" + url.removePrefix("http://"))
        return true
    }

    private fun handleNonWebScheme(url: String): Boolean {
        if (UrlUtils.isHttp(url) || url.startsWith("file:") || url.startsWith("about:")) return false
        return try {
            val intent = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "no handler for $url")
            true // swallow: better than showing a WebView error page
        }
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .setTitle(name)
                .addRequestHeader("User-Agent", userAgent ?: "")
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, name
                )
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Store.addDownload(name, url)
            toast("Downloading $name")
        } catch (t: Throwable) {
            Log.w(TAG, "download failed", t)
            toast("Download failed")
        }
    }

    // -------------------------------------------------------------- fullscreen

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        hideToolbar()
        panel.close()
        cursor.disable()
        b.fullscreenContainer.addView(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        b.fullscreenContainer.visibility = View.VISIBLE
        b.webContainer.visibility = View.GONE
        view.requestFocus()
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        b.fullscreenContainer.removeView(view)
        b.fullscreenContainer.visibility = View.GONE
        b.webContainer.visibility = View.VISIBLE
        fullscreenView = null
        try { fullscreenCallback?.onCustomViewHidden() } catch (_: Throwable) {}
        fullscreenCallback = null
        tabs.activeWebView?.requestFocus()
    }

    // ----------------------------------------------------------------- chrome

    private fun wireToolbar() {
        b.btnBack.setOnClickListener { goBack() }
        b.btnForward.setOnClickListener {
            tabs.activeWebView?.let { if (it.canGoForward()) it.goForward() }
        }
        b.btnReload.setOnClickListener { tabs.activeWebView?.reload() }
        b.btnHome.setOnClickListener { navigate(UrlUtils.HOME_URL) }
        b.btnVoice.setOnClickListener { startVoiceSearch() }
        b.btnCursor.setOnClickListener { toggleCursor() }
        b.btnBookmark.setOnClickListener { toggleBookmark() }
        b.btnTabs.setOnClickListener { openPanel(PanelController.Section.TABS) }
        b.btnMenu.setOnClickListener { openPanel(PanelController.Section.SETTINGS) }

        // Focus alone must not raise the IME: on a TV that traps the user in a
        // keyboard they never asked for. It opens on OK instead.
        b.omnibox.showSoftInputOnFocus = false
        b.omnibox.setOnClickListener { beginEditingUrl() }
        b.omnibox.setOnEditorActionListener { v, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (go) {
                navigate(UrlUtils.toUrlOrSearch(v.text.toString()))
                hideKeyboard()
                hideToolbar()
                true
            } else false
        }
    }

    // ------------------------------------------------------------ find in page

    private fun wireFindBar() {
        b.findClose.setOnClickListener { closeFindBar() }
        b.findNext.setOnClickListener { tabs.activeWebView?.findNext(true) }
        b.findPrev.setOnClickListener { tabs.activeWebView?.findNext(false) }

        b.findInput.showSoftInputOnFocus = false
        b.findInput.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(b.findInput, InputMethodManager.SHOW_IMPLICIT)
        }
        b.findInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString().orEmpty()
                val wv = tabs.activeWebView ?: return
                if (query.isBlank()) {
                    wv.clearMatches()
                    b.findCount.text = ""
                } else {
                    wv.findAllAsync(query)
                }
            }
        })
    }

    private fun openFindBar() {
        val wv = tabs.activeWebView ?: return
        wv.setFindListener { active, count, isDoneCounting ->
            if (isDoneCounting) {
                b.findCount.text = if (count == 0) "0 / 0" else "${active + 1} / $count"
            }
        }
        showToolbar(focus = false)
        b.findBar.visibility = View.VISIBLE
        b.findInput.setText("")
        b.findCount.text = ""
        b.findInput.post {
            b.findInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(b.findInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeFindBar() {
        if (b.findBar.visibility != View.VISIBLE) return
        b.findBar.visibility = View.GONE
        hideKeyboard()
        tabs.activeWebView?.clearMatches()
        tabs.activeWebView?.requestFocus()
    }

    private val findBarVisible: Boolean get() = b.findBar.visibility == View.VISIBLE

    // ------------------------------------------------------------ reader mode

    private fun toggleReaderMode() {
        val wv = tabs.activeWebView ?: return
        if (UrlUtils.isHome(wv.url)) {
            toast("Reader mode works on article pages")
            return
        }
        val js = try {
            assets.open("reader.js").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "reader.js missing", t)
            return
        }
        wv.evaluateJavascript(js) {
            wv.evaluateJavascript("window.__ORBIT_READER_RESULT__") { raw ->
                val r = raw.orEmpty()
                when {
                    r.contains("unavailable") -> toast("No article found on this page")
                    r.contains("\"on\"") || r.contains("\\\"on\\\"") -> toast("Reader mode on")
                    r.contains("\"off\"") || r.contains("\\\"off\\\"") -> toast("Reader mode off")
                }
            }
        }
    }

    private fun beginEditingUrl() {
        b.omnibox.setText(tabs.active?.url?.takeUnless { UrlUtils.isHome(it) } ?: "")
        b.omnibox.setSelection(b.omnibox.text.length)
        b.omnibox.selectAll()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(b.omnibox, InputMethodManager.SHOW_IMPLICIT)
        cancelToolbarAutoHide()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.omnibox.windowToken, 0)
    }

    private fun setOmniboxText(url: String?) {
        // Never overwrite what the user is in the middle of typing.
        if (b.omnibox.hasFocus()) return
        b.omnibox.setText(
            when {
                url == null -> ""
                UrlUtils.isHome(url) -> ""
                else -> url
            }
        )
    }

    private fun showToolbar(focus: Boolean) {
        b.toolbar.visibility = View.VISIBLE
        if (focus) b.toolbar.post { b.omnibox.requestFocus() }
        scheduleToolbarAutoHide()
    }

    private fun hideToolbar() {
        cancelToolbarAutoHide()
        if (b.toolbar.visibility != View.VISIBLE) return
        hideKeyboard()
        b.toolbar.visibility = View.GONE
        tabs.activeWebView?.requestFocus()
    }

    private fun scheduleToolbarAutoHide() {
        cancelToolbarAutoHide()
        // The start page has nothing to read behind the bar, so leave it up.
        if (UrlUtils.isHome(tabs.active?.url)) return
        ui.postDelayed(hideToolbarTask, TOOLBAR_TIMEOUT)
    }

    private fun cancelToolbarAutoHide() = ui.removeCallbacks(hideToolbarTask)

    private val toolbarVisible: Boolean get() = b.toolbar.visibility == View.VISIBLE

    // ----------------------------------------------------------------- actions

    fun navigate(url: String) {
        val wv = tabs.activeWebView ?: return
        cursor.disable()
        tabs.active?.cursorMode = false
        wv.loadUrl(url)
        wv.requestFocus()
    }

    private fun goBack() {
        val wv = tabs.activeWebView ?: return
        if (wv.canGoBack()) wv.goBack() else navigate(UrlUtils.HOME_URL)
    }

    private fun toggleCursor() {
        val on = cursor.toggle(b.cursorHost)
        tabs.active?.cursorMode = on
        b.btnCursor.isSelected = on
        toast(getString(if (on) R.string.cursor_on else R.string.cursor_off))
        if (on) {
            tabs.activeWebView?.let { spatial.clear(it) }
            hideToolbar()
        } else {
            tabs.activeWebView?.requestFocus()
        }
    }

    private fun toggleBookmark() {
        val tab = tabs.active ?: return
        if (UrlUtils.isHome(tab.url)) return
        val added = Store.toggleBookmark(tab.url, tab.displayTitle)
        toast(getString(if (added) R.string.bookmark_added else R.string.bookmark_removed))
        updateBookmarkIcon(tab.url)
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.omnibox_hint))
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.voice_unavailable))
        }
    }

    private fun updateNavButtons() {
        val wv = tabs.activeWebView
        b.btnBack.alpha = if (wv?.canGoBack() == true) 1f else 0.35f
        b.btnForward.alpha = if (wv?.canGoForward() == true) 1f else 0.35f
    }

    private fun updateBookmarkIcon(url: String?) {
        val marked = url != null && !UrlUtils.isHome(url) && Store.isBookmarked(url)
        b.btnBookmark.setImageResource(if (marked) R.drawable.ic_star else R.drawable.ic_star_off)
    }

    private fun onTabsChanged() {
        b.btnTabs.text = tabs.count.toString()

        // Closing the last private tab drops session cookies. WebView shares one
        // cookie jar across tabs, so this is the closest thing to incognito
        // teardown that is actually available.
        if (hadPrivateTabs && !tabs.hasPrivateTabs) {
            CookieManager.getInstance().removeSessionCookies(null)
            CookieManager.getInstance().flush()
        }
        hadPrivateTabs = tabs.hasPrivateTabs

        val tab = tabs.active ?: return
        b.btnTabs.alpha = if (tab.isPrivate) 0.65f else 1f
        setOmniboxText(tab.url)
        updateNavButtons()
        updateBookmarkIcon(tab.url)
        if (tab.cursorMode && !cursor.enabled) cursor.enable(b.cursorHost)
        if (!tab.cursorMode && cursor.enabled) cursor.disable()
        b.btnCursor.isSelected = cursor.enabled
    }

    // ------------------------------------------------------------ key handling

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleGlobalKey(event)) return true
        if (focusInChrome()) {
            if (handleChromeKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (handlePageKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** True when a native control — toolbar or panel — currently owns focus. */
    private fun focusInChrome(): Boolean {
        val f = currentFocus ?: return false
        return isDescendantOf(b.toolbar, f) ||
            isDescendantOf(b.panelHost, f) ||
            isDescendantOf(b.findBar, f)
    }

    private fun isDescendantOf(parent: View, child: View): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    private fun handleGlobalKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN

        if (fullscreenView != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && down) {
                exitFullscreen()
                return true
            }
            return false
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (down) return handleBack()

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS -> {
                if (down) {
                    if (toolbarVisible) hideToolbar() else showToolbar(focus = true)
                }
                return true
            }

            KeyEvent.KEYCODE_SEARCH -> {
                if (down) {
                    showToolbar(focus = true)
                    b.omnibox.post { beginEditingUrl() }
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) tabs.activeWebView?.let { spatial.playPause(it) }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (down) tabs.activeWebView?.let { spatial.seek(it, 15) }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (down) tabs.activeWebView?.let { spatial.seek(it, -15) }
                return true
            }
        }
        return false
    }

    private fun handleBack(): Boolean {
        if (panel.isOpen) { panel.close(); return true }
        if (findBarVisible) { closeFindBar(); return true }
        if (toolbarVisible && !UrlUtils.isHome(tabs.active?.url)) { hideToolbar(); return true }
        if (cursor.enabled) { toggleCursor(); return true }

        val wv = tabs.activeWebView
        if (wv != null && wv.canGoBack()) { wv.goBack(); return true }
        if (tabs.count > 1) { tabs.closeActive(); return true }
        if (!UrlUtils.isHome(tabs.active?.url)) { navigate(UrlUtils.HOME_URL); return true }

        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2500) return false // let the system close the app
        lastBackAt = now
        toast(getString(R.string.press_back_again))
        return true
    }

    /** Keys while the toolbar or panel has focus. */
    private fun handleChromeKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        scheduleToolbarAutoHide()

        val inToolbar = currentFocus?.let { isDescendantOf(b.toolbar, it) } == true
        if (inToolbar && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            hideToolbar()
            tabs.activeWebView?.let { wv ->
                wv.requestFocus()
                if (!cursor.enabled) spatial.enter(wv)
            }
            return true
        }
        return false
    }

    /** Keys while the page has focus. */
    private fun handlePageKey(event: KeyEvent): Boolean {
        val wv = tabs.activeWebView ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN

        if (cursor.enabled) {
            return if (down) cursor.onKeyDown(event.keyCode) else cursor.onKeyUp(event.keyCode)
        }

        SpatialNav.directionOf(event.keyCode)?.let { dir ->
            if (!down) return true
            spatial.move(wv, dir) { outcome ->
                if (outcome == SpatialNav.Outcome.EDGE && dir == "up") {
                    // Nothing above on the page: the toolbar is what is "up".
                    showToolbar(focus = true)
                }
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down) {
                    if (event.repeatCount == 0) {
                        centerLongFired = false
                        ui.postDelayed(longCenterTask, LONG_PRESS_MS)
                    }
                } else {
                    ui.removeCallbacks(longCenterTask)
                    if (!centerLongFired) {
                        spatial.activate(wv) { outcome ->
                            if (outcome == SpatialNav.Outcome.INPUT) {
                                wv.requestFocus()
                                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                                    as InputMethodManager
                                imm.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                    }
                    centerLongFired = false
                }
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) wv.pageDown(false)
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (down) wv.pageUp(false)
                return true
            }

            KeyEvent.KEYCODE_MOVE_HOME -> {
                if (down) wv.pageUp(true)
                return true
            }

            KeyEvent.KEYCODE_MOVE_END -> {
                if (down) wv.pageDown(true)
                return true
            }
        }
        return false
    }

    /** Holding OK on a page switches to the pointer. */
    private val longCenterTask = Runnable {
        centerLongFired = true
        toggleCursor()
    }

    // ------------------------------------------------- PanelController callbacks

    private fun openPanel(section: PanelController.Section) {
        cancelToolbarAutoHide()
        b.scrim.visibility = View.VISIBLE
        panel.open(section)
    }

    override fun openUrl(url: String) = navigate(url)

    override fun onPanelClosed() {
        b.scrim.visibility = View.GONE
        tabs.activeWebView?.requestFocus()
    }

    override fun toast(message: String) {
        b.toast.text = message
        b.toast.visibility = View.VISIBLE
        ui.removeCallbacks(hideToastTask)
        ui.postDelayed(hideToastTask, 2600)
    }

    override fun onSettingsChanged(reload: Boolean) {
        tabs.all.forEach { tab ->
            tab.webView?.let { wv ->
                WebViewFactory.applyUserAgent(wv)
                WebViewFactory.applyContentSettings(wv)
            }
        }
        if (reload) tabs.activeWebView?.reload()
        else tabs.activeWebView?.let { spatial.inject(it) }
    }

    override fun onFindInPage() = openFindBar()

    override fun onReaderMode() = toggleReaderMode()

    override fun onNewPrivateTab() {
        if (tabs.newPrivateTab() == null) {
            toast(getString(R.string.tab_limit))
            return
        }
        toast("Private tab — nothing saved to history")
    }

    override fun onClearCookiesAndCache() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        tabs.all.forEach { it.webView?.clearCache(true) }
        tabs.activeWebView?.clearFormData()
        toast("Cookies and cache cleared")
    }

    override fun onOpenSystemDownloads() {
        try {
            startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            toast("No downloads app on this device")
        }
    }

    override fun isPrivate(): Boolean = tabs.active?.isPrivate == true

    companion object {
        private const val TAG = "OrbitBrowser"
        private const val TOOLBAR_TIMEOUT = 6000L
        private const val LONG_PRESS_MS = 600L
    }
}
