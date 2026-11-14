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
            .also { d ->
                if (manifestUrl != null) {
                    d.item(R.drawable.ic_install, R.string.install_app) { startInstall() }
                }
            }
            .item(R.drawable.ic_folder, R.string.dl_title) {
                startActivity(Intent(this, DownloadsActivity::class.java))
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
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
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
        val desktop = SiteStore.isDesktop(this, site.id)
        val s = webView.settings

        if (desktop) {
            val chrome = Regex("Chrome/([\\d.]+)").find(defaultUserAgent)?.groupValues?.get(1)
                ?: "120.0.0.0"
            s.userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chrome Safari/537.36"
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            // Skala awal eksplisit agar halaman selebar 1280 px langsung pas di layar.
            val density = resources.displayMetrics.density
            val widthPx = if (webView.width > 0) webView.width else resources.displayMetrics.widthPixels
            webView.setInitialScale(((widthPx / density) / 1280f * 100f).toInt().coerceIn(10, 100))
        } else {
            webView.setInitialScale(0)
            // Hapus "; wv" dan "Version/x.y" agar situs menganggapnya Chrome seluler biasa.
            s.userAgentString = defaultUserAgent
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)*\\s"), "")
            s.useWideViewPort = true
            s.loadWithOverviewMode = false
        }

        try {
            desktopScript?.remove()
        } catch (e: Exception) {
            // abaikan
        }
        desktopScript = null
        if (desktop) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    desktopScript = WebViewCompat.addDocumentStartJavaScript(
                        webView, DESKTOP_JS, setOf("*")
                    )
                }
            } catch (e: Exception) {
                desktopScript = null
            }
        }

        if (reload) {
            Toast.makeText(
                this,
                if (desktop) "Mode desktop aktif" else "Mode desktop nonaktif",
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

    private fun startInstall() {
        val manifest = manifestUrl ?: return
        val pageUrl = webView.url ?: startUrl
        val title = pageTitle
        val cookie = try { cookieManager.getCookie(manifest) } catch (e: Exception) { null }
        val ua = webView.settings.userAgentString
        Toast.makeText(this, R.string.install_preparing, Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                Pwa.fetch(manifest, pageUrl, title, cookie, ua)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
                } else {
                    confirmInstall(result)
                }
            }
        }.start()
    }

    private fun confirmInstall(result: Pwa.Result) {
        val info = result.info
        GlassDialog(this)
            .title(getString(R.string.install_title, info.shortName))
            .subtitle(UrlUtils.host(info.startUrl))
            .message(R.string.install_message)
            .button(R.string.cancel)
            .button(R.string.install_confirm, GlassDialog.Kind.ACCENT) {
                Pwa.save(this, site.id, info, result.icon)
                ShortcutHelper.pinApp(this, site, info)
            }
            .show()
    }

    private fun createWebViewClient() = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return handleUrl(request.url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.errorGroup.visibility = View.GONE
            binding.progress.visibility = View.VISIBLE
            view.evaluateJavascript(hookJs, null)
            if (SiteStore.isDesktop(this@BrowserActivity, site.id)) {
                view.evaluateJavascript(DESKTOP_JS, null)
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (SiteStore.isDesktop(this@BrowserActivity, site.id)) {
                view.evaluateJavascript(DESKTOP_JS, null)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.progress.visibility = View.GONE
            binding.swipe.isRefreshing = false
            cookieManager.flush()
            view.evaluateJavascript(hookJs, null)
            detectManifest(view)
            if (SiteStore.isDesktop(this@BrowserActivity, site.id)) {
                view.evaluateJavascript(DESKTOP_JS, null)
            }
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

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        when {
            url.startsWith("blob:") -> withDownloadPermissions { runBlobScript(url, mimeType) }
            url.startsWith("data:") -> withDownloadPermissions { handleDataUrl(url, mimeType) }
            url.startsWith("http://") || url.startsWith("https://") -> withDownloadPermissions {
                readNameHint { hint ->
                    Downloads.enqueueHttp(
                        this,
                        HttpReq(
                            url,
                            userAgent?.takeIf { it.isNotBlank() } ?: webView.settings.userAgentString,
                            webView.url,
                            cookieManager.getCookie(url),
                            contentDisposition,
                            mimeType,
                            hint
                        )
                    )
                    toast(R.string.download_queued)
                }
            }
            else -> toast(R.string.download_unsupported)
        }
    }

    /** Izin yang berguna untuk unduhan; unduhan tetap jalan walau ditolak (ada cadangan). */
    private fun withDownloadPermissions(go: () -> Unit) {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && Downloads.treeUri(this) == null) {
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
        readNameHint { name ->
            Downloads.enqueueBytes(this, name, mime, bytes)
            toast(R.string.download_queued)
        }
    }

    private fun runBlobScript(url: String, mimeType: String?) {
        val token = UUID.randomUUID().toString()
        blobPending[token] = mimeType ?: ""
        val js = """
            (function() {
              var B = window.$BRIDGE_NAME, T = ${JSONObject.quote(token)}, U = ${JSONObject.quote(url)};
              var name = window.__wcName || '';
              window.__wcName = '';
              if (!B) return;
              var x = new XMLHttpRequest();
              x.open('GET', U, true);
              x.responseType = 'blob';
              x.onerror = function() { B.fail(T); };
              x.onload = function() {
                var blob = x.response;
                if (!blob) { B.fail(T); return; }
                B.begin(T, name, blob.type || '', String(blob.size));
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
                  r.onerror = function() { B.fail(T); };
                  r.readAsDataURL(blob.slice(pos, end));
                })();
              };
              x.send();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** Dipanggil dari JavaScript (thread terpisah). Semua metode memeriksa token. */
    private inner class DownloadBridge {

        @JavascriptInterface
        fun begin(token: String, name: String, mime: String, size: String) {
            val hint = blobPending[token] ?: return
            val stream = Downloads.beginStream(
                this@BrowserActivity,
                name.ifBlank { null },
                mime.ifBlank { hint }.ifBlank { null },
                size.toLongOrNull() ?: -1L
            )
            if (stream == null) {
                blobPending.remove(token)
                runOnUiThread { toast(R.string.blob_failed) }
                return
            }
            blobStreams[token] = stream
            runOnUiThread { toast(R.string.download_queued) }
        }

        @JavascriptInterface
        fun chunk(token: String, base64: String) {
            val stream = blobStreams[token] ?: return
            try {
                stream.write(Base64.decode(base64, Base64.DEFAULT))
            } catch (e: Exception) {
                blobStreams.remove(token)
                blobPending.remove(token)
                stream.fail(e.message)
            }
        }

        @JavascriptInterface
        fun finish(token: String) {
            blobPending.remove(token)
            blobStreams.remove(token)?.finish()
        }

        @JavascriptInterface
        fun fail(token: String) {
            val known = blobPending.remove(token) != null
            val stream = blobStreams.remove(token)
            if (stream != null) {
                stream.fail("Halaman gagal menyiapkan file")
            } else if (known) {
                runOnUiThread { toast(R.string.blob_failed) }
            }
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

    companion object {
        const val EXTRA_SITE_ID = "extra_site_id"
        private const val BRIDGE_NAME = "WCDownload"

        /**
         * Skrip mode desktop. Berjalan sebelum skrip situs (document-start). Memaksa viewport
         * selebar 1280 px lalu menjaganya sampai halaman selesai dimuat, dan menyamarkan
         * identitas navigator sebagai Linux desktop.
         */
        private val DESKTOP_JS = """
            (function() {
              if (window.__wcD) return;
              window.__wcD = true;
              var W = 1280;
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

              // Pada document-start, <html> belum tentu ada. Semua akses dijaga.
              var want = 'width=' + W;
              var fix = function() {
                try {
                  var root = document.documentElement;
                  if (!root) return;
                  var list = document.querySelectorAll('meta[name=viewport]');
                  if (!list.length) {
                    var m = document.createElement('meta');
                    m.setAttribute('name', 'viewport');
                    m.setAttribute('content', want);
                    (document.head || root).appendChild(m);
                    return;
                  }
                  for (var i = 0; i < list.length; i++) {
                    if (list[i].getAttribute('content') !== want) list[i].setAttribute('content', want);
                  }
                } catch (e) {}
              };
              fix();
              var obs = null;
              try {
                obs = new MutationObserver(fix);
                obs.observe(document, {
                  childList: true, subtree: true, attributes: true, attributeFilter: ['content']
                });
              } catch (e) {}
              var done = function() {
                fix();
                setTimeout(function() { try { if (obs) obs.disconnect(); } catch (e) {} }, 2500);
              };
              if (document.readyState === 'complete') done();
              else window.addEventListener('load', done);
            })();
        """.trimIndent()
    }
}
