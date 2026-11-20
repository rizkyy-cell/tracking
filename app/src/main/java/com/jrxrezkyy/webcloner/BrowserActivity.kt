package com.jrxrezkyy.webcloner

import android.Manifest
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class BrowserActivity : GlassActivity() {

    private lateinit var binding: BrowserUi
    private lateinit var webView: WebView
    private lateinit var site: Site
    private lateinit var startUrl: String
    private lateinit var host: String
    private lateinit var defaultUserAgent: String
    private lateinit var cookieManager: CookieManager

    private var desktopScript: ScriptHandler? = null

    // Tampilan perangkat (bingkai HP/tablet/laptop/monitor); null = tanpa bingkai.
    private var device: DevicePreset? = null
    private var deviceLandscape = false

    // Instal aplikasi (PWA): true bila dibuka dari ikon hasil Instal.
    private var appMode = false
    private var pwa: PwaInfo? = null
    private var manifestUrl: String? = null
    private var pageTitle: String? = null
    private var installHintShown = false

    /** true jika WebView ini terikat ke profil milik akun (login terpisah). */
    private var usingProfile = false

    /**
     * Dipasang di setiap halaman: (1) mencatat nama file dari atribut `download` pada tautan,
     * karena alamat blob: tidak membawa nama; (2) menunda revokeObjectURL 60 detik supaya
     * blob belum hilang saat aplikasi sempat membacanya.
     */
    private val hookJs = """
        (function() {
          if (window.__wcHook) return;
          window.__wcHook = true;
          var rec = function(a) { try { if (a && a.download) window.__wcName = a.download; } catch (e) {} };
          var origClick = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function() { rec(this); return origClick.apply(this, arguments); };
          document.addEventListener('click', function(e) {
            var t = e.target;
            while (t && t.tagName !== 'A') t = t.parentElement;
            rec(t);
          }, true);
          window.__wcBlobs = window.__wcBlobs || {};
          window.__wcBlobOrder = window.__wcBlobOrder || [];
          var origCreate = URL.createObjectURL;
          URL.createObjectURL = function(o) {
            var u = origCreate.apply(URL, arguments);
            try {
              if (o && typeof o.size === 'number') {
                window.__wcBlobs[u] = o;
                window.__wcBlobOrder.push(u);
                while (window.__wcBlobOrder.length > 8) delete window.__wcBlobs[window.__wcBlobOrder.shift()];
              }
            } catch (e) {}
            return u;
          };
          var origRevoke = URL.revokeObjectURL;
          URL.revokeObjectURL = function(u) {
            setTimeout(function() { try { origRevoke.call(URL, u); } catch (e) {} }, 60000);
          };
        })();
    """.trimIndent()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var permissionCallback: ((Map<String, Boolean>) -> Unit)? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cb = permissionCallback
            permissionCallback = null
            cb?.invoke(grants)
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_SITE_ID) ?: intent.data?.lastPathSegment
        val found = if (id != null) SiteStore.get(this, id) else null
        if (found == null) {
            // Akun sudah dihapus (mis. kartu lama di Recents).
            finish()
            return
        }
        site = found
        host = site.host
        appMode = intent.data?.host == "app"
        pwa = if (appMode) Pwa.get(this, found.id) else null
        if (pwa == null) appMode = false
        startUrl = pwa?.startUrl ?: site.url

        binding = BrowserUi(this)
        setGlassContent(binding.root)
        rootFrame.addView(
            binding.fullscreenContainer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        webView = binding.webView
        device = if (appMode) null else Devices.forSite(this, site.id)
        deviceLandscape = SiteStore.deviceLandscape(this, site.id)
        binding.stage.setDevice(device, deviceLandscape)
        binding.stage.onScreenChanged = { _, _ ->
            // Ukuran layar perangkat berubah (mis. keyboard): skala ikut disesuaikan tanpa muat ulang.
            if (device != null) applyViewport()
        }

        if (appMode) applyStandaloneUi()
        updateTaskDescription(SiteStore.favicon(this, host))

        setupToolbar()
        setupWebView()
        setupBackHandling()

        binding.retryButton.setOnClickListener {
            binding.errorGroup.visibility = View.GONE
            webView.reload()
        }

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            if (::cookieManager.isInitialized) cookieManager.flush()
        }
        super.onPause()
    }

    override fun onDestroy() {
        for (stream in blobStreams.values) stream.fail("Halaman ditutup")
        blobStreams.clear()
        blobPending.clear()
        blobWaiting.clear()
        saveQueue.clear()
        saveActive = null
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun setupToolbar() {
        binding.titleView.text = site.name
        binding.subtitleView.text = host
        binding.subtitleView.visibility = if (site.name != host) View.VISIBLE else View.GONE
        binding.closeButton.setOnClickListener { finishAndRemoveTask() }
        binding.refreshButton.setOnClickListener {
            // Ikon berputar sekali sebagai umpan balik bahwa halaman dimuat ulang.
            binding.refreshButton.animate().rotationBy(360f)
                .setDuration(550L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
            webView.reload()
        }
        binding.moreButton.setOnClickListener { showMenu() }
    }

    private fun showMenu() {
        GlassDialog(this, sheet = true)
            .title(site.name)
            .subtitle(webView.url ?: startUrl)
            .item(R.drawable.ic_refresh, R.string.refresh) { webView.reload() }
            .toggle(R.drawable.ic_desktop, getString(R.string.desktop_mode),
                SiteStore.isDesktop(this, site.id), true) { on ->
                SiteStore.setDesktop(this, site.id, on)
                applyDesktopMode(reload = true)
            }
            .toggle(R.drawable.ic_refresh, getString(R.string.pull_refresh),
                SiteStore.pullRefresh(this), false) { on ->
                SiteStore.setPullRefresh(this, on)
                binding.swipe.isEnabled = on
            }
            .item(R.drawable.ic_desktop, R.string.device_title) { showDeviceDialog() }
            .item(R.drawable.ic_lock, R.string.zoom_title) { showZoomDialog() }
            .item(R.drawable.ic_install, R.string.install_app) { startInstall() }
            .item(R.drawable.ic_open, R.string.dl_title) {
                startActivity(Intent(this, DownloadsActivity::class.java))
            }
            .item(R.drawable.ic_folder, R.string.dl_location) {
                DownloadLocation.choose(this, site, false) {}
            }
            .item(R.drawable.ic_pin, R.string.add_shortcut) { ShortcutHelper.pin(this, site) }
            .item(R.drawable.ic_open, R.string.open_external) {
                openExternal(Uri.parse(webView.url ?: startUrl))
            }
            .item(R.drawable.ic_trash, R.string.clear_data, danger = true) { confirmClearData() }
            .show()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    webView.canGoBack() -> webView.goBack()
                    else -> finishAndRemoveTask()
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun updateTaskDescription(favicon: Bitmap?) {
        val info = pwa
        if (appMode && info != null) {
            val icon = Pwa.icon(this, site.id) ?: IconFactory.forSite(this, site, 128, favicon)
            setTaskDescription(ActivityManager.TaskDescription(info.name, icon))
            return
        }
        setTaskDescription(
            ActivityManager.TaskDescription(
                site.name,
                IconFactory.forSite(this, site, 128, favicon)
            )
        )
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Wajib paling awal, sebelum WebView menyentuh jaringan: ikat ke profil akun ini.
        usingProfile = Profiles.apply(webView, site)
        if (!usingProfile && SiteStore.countForHost(this, host) > 1) {
            Toast.makeText(this, R.string.multi_profile_unsupported, Toast.LENGTH_LONG).show()
        }

        defaultUserAgent = WebSettings.getDefaultUserAgent(this)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setGeolocationEnabled(true)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        applyDesktopMode(reload = false)

        // Cookie milik profil akun ini (persisten) + cookie pihak ketiga untuk alur SSO/OAuth.
        cookieManager = Profiles.cookieManager(site, usingProfile)
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        binding.swipe.isEnabled = SiteStore.pullRefresh(this)
        binding.swipe.setOnRefreshListener { webView.reload() }
        // Hanya izinkan tarik-untuk-muat-ulang jika WebView ada di posisi paling atas.
        binding.swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })
        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createChromeClient()
        webView.addJavascriptInterface(DownloadBridge(), BRIDGE_NAME)
        webView.addJavascriptInterface(PwaBridge(), PWA_BRIDGE)
    }

    // ------------------------------------------------------------------ zoom & viewport

    private fun screenDp(): Float {
        val dm = resources.displayMetrics
        val px = if (::webView.isInitialized && webView.width > 0) webView.width else dm.widthPixels
        return px / dm.density
    }

    /** Lebar viewport (px CSS) yang dipaksakan; 0 = biarkan situs menentukan sendiri. */
    private fun viewportWidth(): Int {
        val dev = device
        if (dev != null) return if (deviceLandscape && dev.rotatable) dev.h else dev.w
        val desktop = SiteStore.isDesktop(this, site.id)
        val zoom = SiteStore.zoom(this, site.id)
        if (!desktop && zoom == 100) return 0
        val base = if (desktop) 1280f else screenDp()
        return Math.round(base * 100f / zoom).coerceIn(200, 4000)
    }

    /** Skrip viewport dengan nilai terkini; diisi lewat penanda %W% %S% %LOCK%. */
    private fun viewportJs(): String {
        val w = viewportWidth()
        val scale = if (w > 0) (screenDp() / w).coerceIn(0.1f, 5f) else 1f
        val lock = SiteStore.lockZoom(this, site.id)
        return VIEWPORT_JS
            .replace("%W%", w.toString())
            .replace("%S%", String.format(java.util.Locale.US, "%.4f", scale))
            .replace("%LOCK%", lock.toString())
            .replace("%MIN%", (device != null).toString())
    }

    /** true bila halaman harus tampil sebagai desktop (mode desktop atau perangkat laptop/monitor). */
    private fun effDesktop(): Boolean {
        val dev = device
        return if (dev != null) dev.os == DeviceOs.DESKTOP else SiteStore.isDesktop(this, site.id)
    }

    /** Skrip penyamar identitas navigator sesuai perangkat; kosong bila tidak perlu. */
    private fun platformJs(): String {
        val dev = device
        return when {
            dev != null && dev.os == DeviceOs.IOS ->
                IOS_JS.replace("%P%", if (dev.kind == DeviceKind.TABLET) "iPad" else "iPhone")
            effDesktop() -> DESKTOP_JS
            else -> ""
        }
    }

    /**
     * Menerapkan zoom/lebar halaman dan kunci zoom. Kunci zoom mematikan pinch-zoom DAN
     * auto-zoom ke kolom ketik (WebView hanya melakukan zoom-ke-kolom bila zoom diizinkan),
     * jadi pandangan di editor kode tidak lagi melompat saat mengetik.
     */
    private fun applyViewport() {
        val s = webView.settings
        val lock = SiteStore.lockZoom(this, site.id)
        s.setSupportZoom(!lock)
        s.builtInZoomControls = !lock
        s.displayZoomControls = false

        val w = viewportWidth()
        if (w > 0) {
            val dm = resources.displayMetrics
            val widthPx = if (webView.width > 0) webView.width else dm.widthPixels
            webView.setInitialScale(((widthPx / dm.density) / w * 100f).toInt().coerceIn(1, 500))
        } else {
            webView.setInitialScale(0)
        }

        try {
            desktopScript?.remove()
        } catch (e: Exception) {
            // abaikan
        }
        desktopScript = null
        val platform = platformJs()
        if (platform.isNotEmpty() || w > 0 || lock) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    desktopScript = WebViewCompat.addDocumentStartJavaScript(
                        webView, platform + "\n" + viewportJs(), setOf("*")
                    )
                }
            } catch (e: Exception) {
                desktopScript = null
            }
        }
        // Halaman yang sedang terbuka ikut diperbarui tanpa menunggu muat ulang.
        try {
            webView.evaluateJavascript(viewportJs(), null)
        } catch (e: Exception) {
            // abaikan
        }
    }

    private fun showZoomDialog() {
        fun widthFor(percent: Int): Int {
            val base = if (effDesktop()) 1280f else screenDp()
            return Math.round(base * 100f / percent)
        }
        val d = GlassDialog(this, sheet = true)
            .title(R.string.zoom_title)
            .subtitle(site.name)
        if (device != null) {
            d.message(R.string.zoom_device_note)
        } else {
            d.message(R.string.zoom_explain)
                .slider(50, 250, SiteStore.zoom(this, site.id),
                    { v -> getString(R.string.zoom_label, v, widthFor(v)) }) { v ->
                    SiteStore.setZoom(this, site.id, v)
                    applyViewport()
                    webView.reload()
                }
        }
        d.toggle(R.drawable.ic_lock, getString(R.string.zoom_lock),
            SiteStore.lockZoom(this, site.id), false) { on ->
            SiteStore.setLockZoom(this, site.id, on)
            applyViewport()
            Toast.makeText(
                this,
                if (on) R.string.zoom_lock_on else R.string.zoom_lock_off,
                Toast.LENGTH_SHORT
            ).show()
        }
        if (device == null) {
            d.button(R.string.zoom_reset) {
                SiteStore.setZoom(this, site.id, 100)
                applyViewport()
                webView.reload()
            }
        }
        d.button(R.string.close).show()
    }

    // ------------------------------------------------------------------ tampilan perangkat

    private fun deviceLabel(dev: DevicePreset): String =
        dev.name + " (" + dev.w + "\u00D7" + dev.h + ")"

    /** Terapkan pilihan perangkat: bingkai, User-Agent, lebar layar, lalu muat ulang tanpa cache. */
    private fun commitDevice() {
        device = if (appMode) null else Devices.forSite(this, site.id)
        deviceLandscape = SiteStore.deviceLandscape(this, site.id)
        binding.stage.setDevice(device, deviceLandscape)
        applyDesktopMode(reload = false)
        // Muat ulang setelah ukuran layar baru terpasang, supaya skala awal benar.
        val vto = binding.stage.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val o = binding.stage.viewTreeObserver
                if (o.isAlive) o.removeOnGlobalLayoutListener(this)
                if (isFinishing || isDestroyed) return
                applyViewport()
                webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webView.loadUrl(webView.url ?: startUrl)
            }
        })
    }

    private fun chooseDevice(id: String?) {
        SiteStore.setDevice(this, site.id, id)
        commitDevice()
    }

    private fun showDeviceDialog() {
        val current = device
        val d = GlassDialog(this, sheet = true)
            .title(R.string.device_title)
            .subtitle(if (current != null) deviceLabel(current) else getString(R.string.device_none))
            .message(R.string.device_explain)
            .choice(getString(R.string.device_none), current == null) { chooseDevice(null) }
            .item(R.drawable.ic_info, R.string.device_group_android) { showDeviceGroup(Devices.GROUP_ANDROID) }
            .item(R.drawable.ic_info, R.string.device_group_ios) { showDeviceGroup(Devices.GROUP_IOS) }
            .item(R.drawable.ic_desktop, R.string.device_group_desktop) { showDeviceGroup(Devices.GROUP_DESKTOP) }
            .item(R.drawable.ic_edit, R.string.device_custom) { showCustomDevice() }
        if (current == null || current.rotatable) {
            d.toggle(R.drawable.ic_refresh, getString(R.string.device_rotate),
                deviceLandscape, false) { on ->
                SiteStore.setDeviceLandscape(this, site.id, on)
                if (SiteStore.device(this, site.id) != null) commitDevice()
            }
        }
        d.button(R.string.close).show()
    }

    private fun showDeviceGroup(group: Int) {
        val title = when (group) {
            Devices.GROUP_ANDROID -> R.string.device_group_android
            Devices.GROUP_IOS -> R.string.device_group_ios
            else -> R.string.device_group_desktop
        }
        val currentId = SiteStore.device(this, site.id)
        val d = GlassDialog(this, sheet = true).title(title).beginScroll()
        for (p in Devices.inGroup(group)) {
            d.choice(deviceLabel(p), currentId == p.id) { chooseDevice(p.id) }
        }
        d.endScroll()
            .button(R.string.back) { showDeviceDialog() }
            .show()
    }

    private fun showCustomDevice() {
        var w = SiteStore.customW(this, site.id)
        var h = SiteStore.customH(this, site.id)
        GlassDialog(this, sheet = true)
            .title(R.string.device_custom)
            .message(R.string.device_custom_hint)
            .slider(240, 3840, w, { v -> getString(R.string.device_width, v) }) { v -> w = v }
            .slider(320, 2160, h, { v -> getString(R.string.device_height, v) }) { v -> h = v }
            .button(R.string.cancel)
            .button(R.string.device_apply, GlassDialog.Kind.ACCENT) {
                SiteStore.setCustomSize(this, site.id, w, h)
                chooseDevice(Devices.CUSTOM_ID)
            }
            .show()
    }

    /**
     * Mode desktop. Dulu hanya User-Agent yang diganti lalu halaman dimuat ulang, padahal
     * sebagian besar situs tetap menampilkan tata letak seluler karena:
     *  1. tag <meta name="viewport" content="width=device-width"> memaksa lebar layar HP,
     *  2. halaman lama masih diambil dari cache, dan
     *  3. skrip situs mendeteksi perangkat lewat navigator.platform / userAgentData.
     * Sekarang ketiganya ditangani: viewport dipaksa selebar 1280 px (sebelum skrip situs
     * berjalan, lewat document-start script), cache dilewati saat berpindah mode, dan
     * identitas navigator disamarkan sebagai Linux desktop.
     */
    private fun applyDesktopMode(reload: Boolean) {
        val dev = device
        val desktop = effDesktop()
        val s = webView.settings

        // Hapus "; wv" dan "Version/x.y" agar situs menganggapnya Chrome seluler biasa.
        val mobileUa = defaultUserAgent
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)*\\s"), "")
        val chrome = Regex("Chrome/([\\d.]+)").find(defaultUserAgent)?.groupValues?.get(1)
            ?: "120.0.0.0"
        s.userAgentString = when {
            dev != null && dev.os == DeviceOs.IOS && dev.kind == DeviceKind.TABLET -> IPAD_UA
            dev != null && dev.os == DeviceOs.IOS -> IPHONE_UA
            desktop ->
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chrome Safari/537.36"
            dev != null && dev.kind == DeviceKind.TABLET -> mobileUa.replace(" Mobile", "")
            else -> mobileUa
        }
        s.useWideViewPort = true
        s.loadWithOverviewMode = desktop || dev != null

        applyViewport()

        if (reload) {
            Toast.makeText(
                this,
                if (SiteStore.isDesktop(this, site.id)) "Mode desktop aktif" else "Mode desktop nonaktif",
                Toast.LENGTH_SHORT
            ).show()
            s.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.loadUrl(webView.url ?: startUrl)
        }
    }

    // ------------------------------------------------------------------ Instal aplikasi (PWA)

    /**
     * Mode aplikasi: tanpa bilah Web Cloner, latar polos warna situs, seperti PWA di Chrome.
     * Tombol Kembali tetap bekerja (mundur, lalu menutup).
     */
    private fun applyStandaloneUi() {
        val info = pwa ?: return
        if (!info.standalone) return
        val bg = info.backgroundColor ?: info.themeColor ?: Color.WHITE
        val bar = info.themeColor ?: bg
        rootFrame.setBackgroundColor(bar)
        rootFrame.getChildAt(0)?.visibility = View.GONE   // latar kaca
        binding.bar.visibility = View.GONE
        binding.progress.visibility = View.GONE
        val light = ColorUtils.calculateLuminance(bar) > 0.5
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    /** Mencari <link rel="manifest"> di halaman; jika ada, menu menampilkan "Instal aplikasi". */
    private fun detectManifest(view: WebView) {
        if (appMode) return
        view.evaluateJavascript(
            "(function(){var l=document.querySelector('link[rel~=\"manifest\"]');return l?l.href:'';})()"
        ) { raw ->
            val url = try {
                org.json.JSONTokener(raw ?: "").nextValue() as? String
            } catch (e: Exception) {
                null
            }
            val found = url?.takeIf { it.startsWith("http") }
            if (found != manifestUrl) manifestUrl = found
            if (found != null && !installHintShown && Pwa.get(this, site.id) == null) {
                installHintShown = true
                Toast.makeText(this, R.string.install_available, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- Pengambilan lewat halaman sendiri (memakai cookie/identitas WebView, lolos WAF) ----

    private class PwaWait {
        val latch = java.util.concurrent.CountDownLatch(1)
        @Volatile var data: ByteArray? = null
        @Volatile var error: String? = null
    }

    private val pwaWaits = java.util.concurrent.ConcurrentHashMap<String, PwaWait>()

    private inner class PwaBridge {
        @JavascriptInterface
        fun ok(token: String, b64: String) {
            val w = pwaWaits[token] ?: return
            try {
                w.data = Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Exception) {
                w.error = "base64"
            }
            w.latch.countDown()
        }

        @JavascriptInterface
        fun err(token: String, msg: String) {
            val w = pwaWaits[token] ?: return
            w.error = msg
            w.latch.countDown()
        }
    }

    /** BLOKIR: panggil dari thread latar. Mengambil [url] lewat fetch() di halaman. */
    private fun pageFetch(url: String): ByteArray? {
        val token = UUID.randomUUID().toString()
        val w = PwaWait()
        pwaWaits[token] = w
        val js = "(function(){var B=window." + PWA_BRIDGE + ",T=" + JSONObject.quote(token) + ";" +
            "try{fetch(" + JSONObject.quote(url) + ",{credentials:'include'}).then(function(r){" +
            "if(!r.ok)throw new Error('HTTP '+r.status);return r.blob();}).then(function(b){" +
            "var f=new FileReader();f.onload=function(){var s=String(f.result);" +
            "B.ok(T,s.substring(s.indexOf(',')+1));};f.onerror=function(){B.err(T,'FileReader');};" +
            "f.readAsDataURL(b);}).catch(function(e){B.err(T,String(e));});}catch(e){B.err(T,String(e));}})();"
        runOnUiThread {
            try {
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                w.error = Diag.err(e)
                w.latch.countDown()
            }
        }
        try {
            w.latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // lanjut
        }
        pwaWaits.remove(token)
        if (w.data == null) Diag.log("pwa", "fetch halaman gagal: " + url.take(100) + " : " + (w.error ?: "timeout"))
        return w.data
    }

    private fun startInstall() {
        val manifest = manifestUrl
        val pageUrl = webView.url ?: startUrl
        val title = pageTitle
        val cookie = try { cookieManager.getCookie(manifest ?: pageUrl) } catch (e: Exception) { null }
        val ua = webView.settings.userAgentString
        Toast.makeText(this, R.string.install_preparing, Toast.LENGTH_SHORT).show()
        Thread {
            var result: Pwa.Result? = null
            if (manifest != null) {
                try {
                    result = Pwa.fetch(manifest, pageUrl, title) { u ->
                        pageFetch(u) ?: Pwa.nativeGet(u, cookie, ua)
                    }
                } catch (e: Throwable) {
                    Diag.log("pwa", "fetch error: " + Diag.err(e))
                }
            } else {
                Diag.log("pwa", "halaman tanpa manifest, pakai nama dan ikon situs")
            }
            val finalResult = result ?: Pwa.fallback(this, pageUrl, title)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                confirmInstall(finalResult)
            }
        }.start()
    }

    private fun confirmInstall(result: Pwa.Result) {
        val info = result.info
        GlassDialog(this)
            .title(getString(R.string.install_title, info.shortName))
            .subtitle(UrlUtils.host(info.startUrl))
            .message(if (result.fromManifest) R.string.install_message else R.string.install_message_basic)
            .button(R.string.cancel)
            .button(R.string.install_confirm, GlassDialog.Kind.ACCENT) {
                Pwa.save(this, site.id, info, result.icon)
                ShortcutHelper.pinApp(this, site, info)
            }
            .show()
    }

    private fun injectPageScripts(view: WebView) {
        val platform = platformJs()
        if (platform.isNotEmpty()) view.evaluateJavascript(platform, null)
        view.evaluateJavascript(viewportJs(), null)
    }

    private fun createWebViewClient() = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return handleUrl(request.url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.errorGroup.visibility = View.GONE
            binding.progress.visibility = View.VISIBLE
            view.evaluateJavascript(hookJs, null)
            injectPageScripts(view)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            injectPageScripts(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.progress.visibility = View.GONE
            binding.swipe.isRefreshing = false
            cookieManager.flush()
            view.evaluateJavascript(hookJs, null)
            detectManifest(view)
            injectPageScripts(view)
            // Setelah pemuatan tanpa cache (saat ganti mode), kembali ke cache normal.
            if (view.settings.cacheMode != WebSettings.LOAD_DEFAULT) {
                view.settings.cacheMode = WebSettings.LOAD_DEFAULT
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                binding.swipe.isRefreshing = false
                binding.progress.visibility = View.GONE
                binding.errorText.text = getString(R.string.load_error, error.description)
                binding.errorGroup.visibility = View.VISIBLE
            }
        }
    }

    private fun createChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.setProgress(newProgress, true)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            pageTitle = title
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            if (icon == null) return
            // Simpan hanya jika ikon berasal dari situs utama (bukan halaman SSO pihak lain).
            if (UrlUtils.host(view.url ?: "") == host) {
                SiteStore.saveFavicon(this@BrowserActivity, host, icon)
                updateTaskDescription(icon)
            }
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            // Pemilih dokumen sistem selalu mendukung pilih banyak file sekaligus (GitHub, dll.).
            // ACTION_GET_CONTENT bawaan WebView sering membuka galeri/aplikasi lain yang hanya
            // mengembalikan satu file, itu sebabnya dulu hanya bisa satu per satu.
            val primary = if (fileChooserParams.isCaptureEnabled) {
                fileChooserParams.createIntent()
            } else {
                buildPickerIntent(fileChooserParams)
            }
            return try {
                fileChooserLauncher.launch(primary)
                true
            } catch (e: Exception) {
                try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e2: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            binding.fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            binding.fullscreenContainer.visibility = View.VISIBLE
            binding.mainGroup.visibility = View.GONE
            setImmersiveMode(true)
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread { handleWebPermission(request) }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            askAllow(getString(R.string.ask_location, origin)) { allowed ->
                if (!allowed) {
                    callback.invoke(origin, false, false)
                } else {
                    ensurePermissions(
                        listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    ) { granted ->
                        callback.invoke(origin, granted.values.any { it }, false)
                    }
                }
            }
        }
    }

    /** Intent pemilih dokumen: multi-pilih bila input file memakai atribut `multiple`. */
    private fun buildPickerIntent(params: WebChromeClient.FileChooserParams): Intent {
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val types = LinkedHashSet<String>()
        var unresolved = false
        val map = android.webkit.MimeTypeMap.getSingleton()
        for (raw in params.acceptTypes ?: emptyArray()) {
            for (part in raw.split(',')) {
                val t = part.trim().lowercase()
                if (t.isEmpty()) continue
                if (t.startsWith(".")) {
                    val mime = map.getMimeTypeFromExtension(t.substring(1))
                    if (mime != null) types.add(mime) else unresolved = true
                } else if (t.contains('/')) {
                    types.add(t)
                } else {
                    unresolved = true
                }
            }
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            if (types.isEmpty() || unresolved) {
                type = "*/*"
            } else if (types.size == 1) {
                type = types.first()
            } else {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            }
        }
    }

    // ------------------------------------------------------------------ navigation

    private fun handleUrl(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https", "about", "blob", "data", "javascript" -> false
            "intent" -> {
                openIntentUri(uri.toString())
                true
            }
            else -> {
                openExternal(uri)
                true
            }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            toast(R.string.no_app)
        }
    }

    private fun openIntentUri(raw: String) {
        try {
            val intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = try {
                Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            } catch (e2: Exception) {
                null
            }
            if (fallback != null && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
                webView.loadUrl(fallback)
            } else {
                toast(R.string.no_app)
            }
        }
    }

    // ------------------------------------------------------------------ fullscreen

    private fun exitFullscreen() {
        val view = customView ?: return
        val callback = customViewCallback
        customView = null
        customViewCallback = null

        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        binding.mainGroup.visibility = View.VISIBLE
        setImmersiveMode(false)
        callback?.onCustomViewHidden()
    }

    // Nama sengaja bukan "setImmersive": itu sudah dipakai Activity.setImmersive milik Android.
    private fun setImmersiveMode(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun ensurePermissions(
        permissions: List<String>,
        onResult: (Map<String, Boolean>) -> Unit
    ) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onResult(permissions.associateWith { true })
            return
        }
        permissionCallback?.invoke(emptyMap())
        permissionCallback = { granted ->
            onResult(permissions.associateWith { p ->
                granted[p] ?: (ContextCompat.checkSelfPermission(this, p) ==
                    PackageManager.PERMISSION_GRANTED)
            })
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun askAllow(message: String, onAnswer: (Boolean) -> Unit) {
        var answered = false
        val dialog = GlassDialog(this)
            .message(message)
            .button(R.string.deny) {
                answered = true
                onAnswer(false)
            }
            .button(R.string.allow, GlassDialog.Kind.ACCENT) {
                answered = true
                onAnswer(true)
            }
        dialog.setOnDismissListener {
            if (!answered) {
                answered = true
                onAnswer(false)
            }
        }
        dialog.show()
    }

    private fun handleWebPermission(request: PermissionRequest) {
        val mapping = mapOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to Manifest.permission.CAMERA,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE to Manifest.permission.RECORD_AUDIO
        )
        val wanted = request.resources.filter { mapping.containsKey(it) }
        if (wanted.isEmpty()) {
            request.deny()
            return
        }
        val originHost = request.origin.host ?: host
        askAllow(getString(R.string.ask_camera_mic, originHost)) { allowed ->
            if (!allowed) {
                request.deny()
            } else {
                ensurePermissions(wanted.map { mapping.getValue(it) }) { granted ->
                    val ok = wanted.filter { granted[mapping.getValue(it)] == true }
                    if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                }
            }
        }
    }

    // ------------------------------------------------------------------ unduhan

    /*
     * Semua unduhan lewat mesin [Downloads]: HTTP diambil sendiri (cookie, Referer, pengalihan,
     * nama dan format dari header respons), data: ditulis langsung, blob: dikirim bertahap oleh
     * skrip halaman. Tiap unduhan blob memakai token acak sekali pakai, jadi banyak unduhan bisa
     * berjalan bersamaan dan panggilan tanpa token yang cocok (iklan, iframe) diabaikan.
     */

    private val blobPending = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val blobStreams = java.util.concurrent.ConcurrentHashMap<String, Downloads.Stream>()

    /** Unduhan blob yang menunggu pengguna memilih lokasi simpan. */
    private class BlobWait(val name: String, val mime: String, val size: Long)

    private val blobWaiting = java.util.concurrent.ConcurrentHashMap<String, BlobWait>()

    // ---- Antrean "Simpan sebagai" (satu picker pada satu waktu) ----

    private class SaveRequest(val name: String, val mime: String, val done: (Uri?) -> Unit)

    private val saveQueue = java.util.ArrayDeque<SaveRequest>()
    private var saveActive: SaveRequest? = null

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val req = saveActive
            saveActive = null
            val uri = if (r.resultCode == RESULT_OK) r.data?.data else null
            req?.done?.invoke(uri)
            pumpSave()
        }

    private fun requestSave(name: String, mime: String, done: (Uri?) -> Unit) {
        saveQueue.add(SaveRequest(name, mime, done))
        pumpSave()
    }

    private fun pumpSave() {
        if (saveActive != null) return
        val req = saveQueue.poll() ?: return
        saveActive = req
        try {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(req.mime.ifBlank { "application/octet-stream" })
                .putExtra(Intent.EXTRA_TITLE, req.name)
            saveLauncher.launch(i)
        } catch (e: Exception) {
            Diag.log("unduh", "picker simpan gagal: " + Diag.err(e))
            saveActive = null
            toast(R.string.dl_picker_missing)
            req.done(null)
            pumpSave()
        }
    }

    /**
     * Menentukan tujuan sesuai pengaturan situs ini. Harus di thread utama. [go] menerima null
     * bila pengguna membatalkan pemilihan lokasi.
     */
    private fun resolveDest(name: String, mime: String, go: (Dest?) -> Unit) {
        var mode = SiteStore.dlMode(this, site.id)
        if (mode == SiteStore.DL_FOLDER) {
            val tree = SiteStore.dlTree(this, site.id)
            val valid = tree != null && contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isWritePermission
            }
            if (valid) {
                go(Dest.Tree(tree!!))
                return
            }
            Diag.log("unduh", "folder tetap tidak valid, tanya lokasi")
            mode = SiteStore.DL_ASK
        }
        if (mode == SiteStore.DL_DEFAULT) {
            go(Dest.Default)
            return
        }
        requestSave(name, mime) { uri -> go(uri?.let { Dest.Doc(it) }) }
    }

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        Diag.log("unduh", "diminta: " + url.take(12) + " mime=" + mimeType)
        when {
            url.startsWith("blob:") -> withDownloadPermissions { runBlobScript(url, mimeType) }
            url.startsWith("data:") -> withDownloadPermissions { handleDataUrl(url, mimeType) }
            url.startsWith("http://") || url.startsWith("https://") -> withDownloadPermissions {
                readNameHint { hint ->
                    val req = HttpReq(
                        url,
                        userAgent?.takeIf { it.isNotBlank() } ?: webView.settings.userAgentString,
                        webView.url,
                        cookieManager.getCookie(url),
                        contentDisposition,
                        mimeType,
                        hint
                    )
                    val (name, mime) = Downloads.guessName(req)
                    resolveDest(name, mime) { dest ->
                        if (dest == null) {
                            toast(R.string.dl_canceled)
                        } else {
                            Downloads.enqueueHttp(this, req, dest)
                            toast(R.string.download_queued)
                        }
                    }
                }
            }
            else -> toast(R.string.download_unsupported)
        }
    }

    /** Izin yang berguna untuk unduhan; unduhan tetap jalan walau ditolak (ada cadangan). */
    private fun withDownloadPermissions(go: () -> Unit) {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            SiteStore.dlMode(this, site.id) == SiteStore.DL_DEFAULT
        ) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33 && !Downloads.askedNotificationPermission(this)) {
            Downloads.markAskedNotificationPermission(this)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) go() else ensurePermissions(need) { go() }
    }

    /** Nama dari atribut `download` pada tautan yang baru diklik (dicatat oleh hookJs). */
    private fun readNameHint(done: (String?) -> Unit) {
        try {
            webView.evaluateJavascript(
                "(function(){var n=window.__wcName||'';window.__wcName='';return n;})()"
            ) { raw ->
                val n = try {
                    org.json.JSONTokener(raw ?: "").nextValue() as? String
                } catch (e: Exception) {
                    null
                }
                done(n?.takeIf { it.isNotBlank() })
            }
        } catch (e: Exception) {
            done(null)
        }
    }

    private fun handleDataUrl(url: String, mimeHint: String?) {
        val comma = url.indexOf(',')
        if (comma < 0) {
            toast(R.string.download_failed)
            return
        }
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val isBase64 = meta.endsWith(";base64", ignoreCase = true)
        val mime = meta.substringBefore(';').ifBlank { mimeHint ?: "text/plain" }
        val bytes = try {
            if (isBase64) {
                Base64.decode(Uri.decode(payload), Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            toast(R.string.download_failed)
            return
        }
        readNameHint { hint ->
            val (name, finalMime) = FileNames.resolve(hint, mime)
            resolveDest(name, finalMime) { dest ->
                if (dest == null) {
                    toast(R.string.dl_canceled)
                } else {
                    Downloads.enqueueBytes(this, hint, mime, bytes, dest)
                    toast(R.string.download_queued)
                }
            }
        }
    }

    /**
     * Blob: skrip halaman memakai objek Blob asli yang disimpan hookJs (kebal CSP dan
     * revokeObjectURL); bila tidak ada, jatuh ke XMLHttpRequest. Sebelum mengirim data, skrip
     * bertanya ke aplikasi lewat B.begin: "go" (lanjut), "wait" (menunggu pengguna memilih
     * lokasi; aplikasi memanggil window.__wcWait[token]) atau "fail".
     */
    private fun runBlobScript(url: String, mimeType: String?) {
        val token = UUID.randomUUID().toString()
        blobPending[token] = mimeType ?: ""
        Diag.log("unduh", "blob mulai")
        val js = """
            (function() {
              var B = window.${BRIDGE_NAME}, T = ${JSONObject.quote(token)}, U = ${JSONObject.quote(url)};
              var name = window.__wcName || '';
              window.__wcName = '';
              if (!B) return;
              var bad = function(why) { try { B.fail(T, String(why || '')); } catch (e) {} };
              var send = function(blob) {
                var size = blob.size, step = 786432, pos = 0;
                (function next() {
                  if (pos >= size) { B.finish(T); return; }
                  var end = Math.min(pos + step, size);
                  var r = new FileReader();
                  r.onload = function() {
                    var s = r.result;
                    B.chunk(T, s.substring(s.indexOf(',') + 1));
                    pos = end;
                    setTimeout(next, 0);
                  };
                  r.onerror = function() { bad('FileReader'); };
                  r.readAsDataURL(blob.slice(pos, end));
                })();
              };
              var start = function(blob) {
                var r;
                try { r = B.begin(T, name, blob.type || '', String(blob.size)); } catch (e) { bad('begin: ' + e); return; }
                if (r === 'go') { send(blob); }
                else if (r === 'wait') {
                  window.__wcWait = window.__wcWait || {};
                  window.__wcWait[T] = function(ok) {
                    delete window.__wcWait[T];
                    if (ok) send(blob);
                  };
                }
              };
              var stored = window.__wcBlobs && window.__wcBlobs[U];
              if (stored) { start(stored); return; }
              try {
                var x = new XMLHttpRequest();
                x.open('GET', U, true);
                x.responseType = 'blob';
                x.onerror = function() { bad('XHR ditolak (CSP atau blob sudah dicabut)'); };
                x.onload = function() {
                  if (!x.response) { bad('blob kosong'); return; }
                  start(x.response);
                };
                x.send();
              } catch (e) { bad('XHR: ' + e); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun jsResume(token: String, ok: Boolean) {
        val call = "(function(){var w=window.__wcWait;if(w&&w[" + JSONObject.quote(token) + "])w[" +
            JSONObject.quote(token) + "](" + ok + ");})();"
        try {
            webView.evaluateJavascript(call, null)
        } catch (e: Exception) {
            Diag.log("unduh", "jsResume gagal: " + Diag.err(e))
        }
    }

    private fun startBlob(token: String, w: BlobWait, dest: Dest): Boolean {
        val res = Downloads.beginStream(this, w.name.ifBlank { null }, w.mime.ifBlank { null }, w.size, dest)
        val stream = res.stream
        if (stream == null) {
            blobPending.remove(token)
            toastText(getString(R.string.blob_failed_reason, res.error ?: "?"))
            return false
        }
        blobStreams[token] = stream
        toast(R.string.download_queued)
        return true
    }

    /** Dipanggil dari JavaScript (thread terpisah). Semua metode memeriksa token. */
    private inner class DownloadBridge {

        @JavascriptInterface
        fun begin(token: String, name: String, mime: String, size: String): String {
            val hint = blobPending[token] ?: return "fail"
            val finalMime = mime.ifBlank { hint }
            val w = BlobWait(name, finalMime, size.toLongOrNull() ?: -1L)
            val latch = java.util.concurrent.CountDownLatch(1)
            var answer = "fail"
            runOnUiThread {
                try {
                    val (shownName, shownMime) = FileNames.resolve(name.ifBlank { null }, finalMime.ifBlank { null })
                    resolveDestOrWait(token, w, shownName, shownMime) { a ->
                        answer = a
                        latch.countDown()
                    }
                } catch (e: Throwable) {
                    Diag.log("unduh", "begin gagal: " + Diag.err(e))
                    latch.countDown()
                }
            }
            try {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                // lanjut dengan jawaban terakhir
            }
            if (answer == "fail") blobPending.remove(token)
            return answer
        }

        @JavascriptInterface
        fun chunk(token: String, base64: String) {
            val stream = blobStreams[token] ?: return
            try {
                stream.write(Base64.decode(base64, Base64.DEFAULT))
            } catch (e: Exception) {
                Diag.log("unduh", "chunk gagal: " + Diag.err(e))
                blobStreams.remove(token)
                blobPending.remove(token)
                stream.fail(e.message)
            }
        }

        @JavascriptInterface
        fun finish(token: String) {
            blobPending.remove(token)
            val s = blobStreams.remove(token)
            Diag.log("unduh", "blob selesai " + (s != null))
            s?.finish()
        }

        @JavascriptInterface
        fun fail(token: String, reason: String) {
            val known = blobPending.remove(token) != null
            blobWaiting.remove(token)
            val stream = blobStreams.remove(token)
            Diag.log("unduh", "blob gagal dari halaman: " + reason)
            if (stream != null) {
                stream.fail("Halaman gagal menyiapkan file: " + reason)
            } else if (known) {
                runOnUiThread { toastText(getString(R.string.blob_failed_reason, reason.ifBlank { "?" })) }
            }
        }
    }

    /**
     * Dipanggil di thread utama oleh begin(). Mode Download/folder tetap: jawab "go" langsung.
     * Mode tanya: tampilkan picker, jawab "wait", dan lanjutkan lewat jsResume setelah dipilih.
     */
    private fun resolveDestOrWait(
        token: String,
        w: BlobWait,
        shownName: String,
        shownMime: String,
        reply: (String) -> Unit
    ) {
        var replied = false
        var sync = true
        resolveDest(shownName, shownMime) { dest ->
            if (dest == null) {
                blobPending.remove(token)
                blobWaiting.remove(token)
                toast(R.string.dl_canceled)
                if (sync) {
                    replied = true
                    reply("fail")
                }
                return@resolveDest
            }
            val ok = startBlob(token, w, dest)
            if (sync) {
                replied = true
                reply(if (ok) "go" else "fail")
            } else {
                jsResume(token, ok)
            }
        }
        sync = false
        if (!replied) {
            blobWaiting[token] = w
            reply("wait")
        }
    }

    // ------------------------------------------------------------------ misc

    private fun confirmClearData() {
        GlassDialog(this)
            .title(R.string.clear_data)
            .message(R.string.clear_confirm)
            .button(R.string.cancel)
            .button(R.string.clear_data_short, GlassDialog.Kind.DANGER) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                Profiles.webStorage(site, usingProfile).deleteAllData()
                webView.clearCache(true)
                webView.clearHistory()
                toast(R.string.clear_done)
                webView.loadUrl(startUrl)
            }
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toastText(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_SITE_ID = "extra_site_id"
        private const val BRIDGE_NAME = "WCDownload"
        private const val PWA_BRIDGE = "WCPwa"

        private const val IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        private const val IPAD_UA =
            "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

        /** Menyamarkan identitas navigator sebagai Safari di iPhone/iPad. */
        private val IOS_JS = """
            (function() {
              if (window.__wcI) return;
              window.__wcI = true;
              var def = function(o, k, v) {
                try { Object.defineProperty(o, k, { get: function() { return v; }, configurable: true }); } catch (e) {}
              };
              def(Navigator.prototype, 'platform', '%P%');
              def(Navigator.prototype, 'vendor', 'Apple Computer, Inc.');
              def(Navigator.prototype, 'maxTouchPoints', 5);
              def(Navigator.prototype, 'userAgentData', undefined);
            })();
        """.trimIndent()

        /**
         * Skrip mode desktop. Berjalan sebelum skrip situs (document-start) dan menyamarkan
         * identitas navigator sebagai Linux desktop. Lebar viewport diatur [VIEWPORT_JS].
         */
        private val DESKTOP_JS = """
            (function() {
              if (window.__wcD) return;
              window.__wcD = true;
              var def = function(o, k, v) {
                try { Object.defineProperty(o, k, { get: function() { return v; }, configurable: true }); } catch (e) {}
              };
              try { def(Navigator.prototype, 'platform', 'Linux x86_64'); } catch (e) {}
              try { def(Navigator.prototype, 'maxTouchPoints', 0); } catch (e) {}
              try {
                if (navigator.userAgentData) {
                  var brands = navigator.userAgentData.brands;
                  def(Navigator.prototype, 'userAgentData', {
                    mobile: false,
                    platform: 'Linux',
                    brands: brands,
                    getHighEntropyValues: function() {
                      return Promise.resolve({ mobile: false, platform: 'Linux', brands: brands });
                    },
                    toJSON: function() { return { mobile: false, platform: 'Linux', brands: brands }; }
                  });
                }
              } catch (e) {}
            })();
        """.trimIndent()

        /**
         * Mengatur <meta viewport>: lebar/zoom paksaan dan kunci zoom. Aman dipanggil berulang:
         * panggilan berikutnya hanya memperbarui konfigurasi. Nilai asli meta situs disimpan
         * sehingga bisa dipulihkan saat pengaturan dimatikan.
         */
        private val VIEWPORT_JS = """
            (function() {
              var cfg = { w: %W%, s: %S%, lock: %LOCK%, m: %MIN% };
              var again = !!window.__wcV;
              window.__wcV = cfg;
              if (again) { if (window.__wcVfix) window.__wcVfix(); return; }
              var need = function() { var c = window.__wcV; return c.w > 0 || c.lock; };
              var build = function(orig) {
                var c = window.__wcV, parts = [], src = String(orig || '').split(/[,;]/);
                for (var i = 0; i < src.length; i++) {
                  var p = src[i].trim();
                  if (!p) continue;
                  var k = p.split('=')[0].trim().toLowerCase();
                  if (c.lock && k === 'user-scalable') continue;
                  if (c.w > 0 && (k === 'width' || k === 'initial-scale' || k === 'minimum-scale' || k === 'maximum-scale')) continue;
                  if (c.lock && k === 'maximum-scale') continue;
                  parts.push(p);
                }
                if (c.w > 0) {
                  parts.push('width=' + c.w);
                  parts.push('initial-scale=' + c.s);
                  if (c.lock) { parts.push('minimum-scale=' + c.s); parts.push('maximum-scale=' + c.s); }
                  else if (c.m) { parts.push('minimum-scale=' + c.s); }
                }
                if (c.lock) parts.push('user-scalable=no');
                return parts.join(', ');
              };
              var fix = function() {
                try {
                  var root = document.documentElement;
                  if (!root) return;
                  var list = document.querySelectorAll('meta[name=viewport]');
                  if (!list.length) {
                    if (!need()) return;
                    var m = document.createElement('meta');
                    m.setAttribute('name', 'viewport');
                    var made = build('');
                    m.setAttribute('content', made);
                    m.__wcOrig = '';
                    m.__wcLast = made;
                    (document.head || root).appendChild(m);
                    return;
                  }
                  for (var i = 0; i < list.length; i++) {
                    var el = list[i];
                    var cur = el.getAttribute('content') || '';
                    if (el.__wcLast === undefined || cur !== el.__wcLast) el.__wcOrig = cur;
                    var want = need() ? build(el.__wcOrig) : el.__wcOrig;
                    if (cur !== want) el.setAttribute('content', want);
                    el.__wcLast = want;
                  }
                } catch (e) {}
              };
              window.__wcVfix = fix;
              fix();
              var obs = null;
              var watch = function(target) {
                try {
                  if (obs) obs.disconnect();
                  obs = new MutationObserver(fix);
                  obs.observe(target, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
                } catch (e) {}
              };
              watch(document);
              var done = function() {
                fix();
                // Setelah dimuat cukup awasi <head> (kecil), bukan seluruh dokumen.
                setTimeout(function() { if (document.head) watch(document.head); else if (obs) obs.disconnect(); }, 2500);
              };
              if (document.readyState === 'complete') done();
              else window.addEventListener('load', done);
            })();
        """.trimIndent()
    }
}
