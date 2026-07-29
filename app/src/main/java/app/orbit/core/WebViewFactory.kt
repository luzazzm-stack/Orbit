package app.orbit.core

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.orbit.data.Prefs

object WebViewFactory {

    /**
     * Derived from the WebView's own UA so the Chrome version we claim always
     * matches the engine actually rendering the page. Hard-coding a version
     * string is how TV browsers end up being served 2019 fallback markup.
     */
    private var cachedDesktopUa: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context): WebView {
        val wv = WebView(ctx)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.setBackgroundColor(0xFF0A0C10.toInt())
        wv.overScrollMode = WebView.OVER_SCROLL_NEVER
        wv.isScrollbarFadingEnabled = true

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.textZoom = Prefs.textZoom
        // No local file browsing: nothing in the app needs it and it is the
        // classic WebView exfiltration path.
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.saveFormData = false

        applyUserAgent(wv)
        applyContentSettings(wv)

        CookieManager.getInstance().setAcceptCookie(true)

        return wv
    }

    /** Everything the user can flip at runtime, applied to a live WebView. */
    fun applyContentSettings(wv: WebView) {
        val s = wv.settings
        s.textZoom = Prefs.textZoom
        s.javaScriptEnabled = Prefs.javaScript
        s.loadsImagesAutomatically = Prefs.loadImages
        s.blockNetworkImage = !Prefs.loadImages
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, Prefs.thirdPartyCookies)
        applyForceDark(wv, Prefs.forceDark)
    }

    fun applyUserAgent(wv: WebView) {
        val s = wv.settings
        if (Prefs.desktopUa) {
            s.userAgentString = desktopUa(s.userAgentString)
        } else {
            s.userAgentString = null // restore the platform default
        }
    }


    /**
     * Force-dark makes light-themed sites bearable on a TV in a dark room, but
     * it mangles sites that already ship a dark theme, so it stays opt-in.
     */
    fun applyForceDark(wv: WebView, on: Boolean) {
        @Suppress("DEPRECATION")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                wv.settings,
                if (on) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
    }

    private fun desktopUa(current: String?): String {
        cachedDesktopUa?.let { return it }
        val major = Regex("Chrome/(\\d+)").find(current ?: "")?.groupValues?.get(1) ?: "133"
        val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
        cachedDesktopUa = ua
        return ua
    }
}
