package app.orbit

import android.app.Application
import android.webkit.WebView
import app.orbit.core.AdBlocker
import app.orbit.core.DohResolver
import app.orbit.core.FaviconStore
import app.orbit.data.Prefs
import app.orbit.data.Store
import kotlin.concurrent.thread

class OrbitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        DohResolver.init(this)
        FaviconStore.init(this)
        // Sideloaded personal build: leaving remote debugging on means the page
        // can be inspected from a laptop over adb with chrome://inspect.
        WebView.setWebContentsDebuggingEnabled(true)
        thread(name = "orbit-blocklist") { AdBlocker.load(this) }
    }
}
