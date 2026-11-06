# Jembatan unduhan (BrowserActivity.DownloadBridge) dipanggil dari JavaScript lewat refleksi,
# jadi metode bertanda @JavascriptInterface dan atributnya tidak boleh dihapus/diubah namanya R8.
-keepattributes *Annotation*,SourceFile,LineNumberTable
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
