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

# OkHttp (used only by DohResolver) references optional TLS providers that are
# not on the classpath. Its own bundled rules cover this, but R8 failing the
# release build over a missing Conscrypt class is not a risk worth taking.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }
