# Keep the JavaScript bridge surface: its methods are called by name from JS.
-keepclassmembers class com.nogamt.showroom.bridge.NogaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.nogamt.showroom.bridge.NogaBridge { *; }

# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# WebView JS interface (belt and braces)
-keepattributes JavascriptInterface
-keepattributes *Annotation*
