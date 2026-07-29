# Orbit — Android TV browser
#
# There is no JavaScript-to-Kotlin bridge in this app: the start page and the
# spatial-navigation engine are driven with evaluateJavascript only, so no
# @JavascriptInterface keep rules are required.

# WebView clients are instantiated as anonymous objects and only ever called by
# the framework; keep their callback signatures intact.
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}

# Silence warnings for the optional androidx.webkit boundary interfaces.
-dontwarn org.chromium.**
