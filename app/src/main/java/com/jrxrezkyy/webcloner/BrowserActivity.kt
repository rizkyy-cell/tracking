package com.jrxrezkyy.webcloner

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
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
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jrxrezkyy.webcloner.databinding.ActivityBrowserBinding

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var webView: WebView
    private lateinit var startUrl: String
    private lateinit var host: String
    private lateinit var defaultUserAgent: String

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

        val raw = intent.dataString ?: intent.getStringExtra(EXTRA_URL)
        val normalized = raw?.let { UrlUtils.normalize(it) }
        if (normalized == null) {
            finish()
            return
        }
        startUrl = normalized
        host = UrlUtils.host(startUrl)

        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webView = binding.webView

        if (!SiteStore.contains(this, startUrl)) {
            SiteStore.add(this, Site(startUrl, host))
        }
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
            CookieManager.getInstance().flush()
        }
        super.onPause()
    }

    override fun onDestroy() {
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
        val toolbar = binding.toolbar
        toolbar.title = host
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationOnClickListener { finishAndRemoveTask() }
        toolbar.inflateMenu(R.menu.browser_menu)
        toolbar.menu.findItem(R.id.action_desktop).isChecked = SiteStore.isDesktop(this, host)
        toolbar.menu.findItem(R.id.action_pull_refresh).isChecked = SiteStore.pullRefresh(this)
        toolbar.setOnMenuItemClickListener { onMenuItem(it) }
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> webView.reload()
            R.id.action_desktop -> {
                item.isChecked = !item.isChecked
                SiteStore.setDesktop(this, host, item.isChecked)
                applyUserAgent()
                webView.reload()
            }
            R.id.action_pull_refresh -> {
                item.isChecked = !item.isChecked
                SiteStore.setPullRefresh(this, item.isChecked)
                binding.swipe.isEnabled = item.isChecked
            }
            R.id.action_shortcut -> ShortcutHelper.pin(this, startUrl, host)
            R.id.action_external -> openExternal(Uri.parse(webView.url ?: startUrl))
            R.id.action_clear -> confirmClearData()
            else -> return false
        }
        return true
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
        setTaskDescription(ActivityManager.TaskDescription(host, IconFactory.create(host, favicon, 128)))
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
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
        applyUserAgent()

        // Cookie persisten (sesi login bertahan) + cookie pihak ketiga untuk alur SSO/OAuth.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        binding.swipe.isEnabled = SiteStore.pullRefresh(this)
        binding.swipe.setOnRefreshListener { webView.reload() }
        // Hanya izinkan tarik-untuk-muat-ulang jika WebView ada di posisi paling atas.
        binding.swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })
        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createChromeClient()
    }

    /**
     * Menghapus penanda "; wv" dan "Version/x.y" dari User-Agent bawaan WebView sehingga
     * lebih banyak situs memperlakukannya sebagai Chrome seluler biasa.
     */
    private fun applyUserAgent() {
        val s = webView.settings
        if (SiteStore.isDesktop(this, host)) {
            val chrome = Regex("Chrome/([\\d.]+)").find(defaultUserAgent)?.groupValues?.get(1)
                ?: "120.0.0.0"
            s.userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chrome Safari/537.36"
            s.loadWithOverviewMode = true
        } else {
            s.userAgentString = defaultUserAgent
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)*\\s"), "")
            s.loadWithOverviewMode = false
        }
    }

    private fun createWebViewClient() = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return handleUrl(request.url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.errorGroup.visibility = View.GONE
            binding.progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.progress.visibility = View.GONE
            binding.swipe.isRefreshing = false
            CookieManager.getInstance().flush()
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
            binding.progress.progress = newProgress
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
            return try {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (e: Exception) {
                fileCallback = null
                false
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
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.allow) { _, _ -> onAnswer(true) }
            .setNegativeButton(R.string.deny) { _, _ -> onAnswer(false) }
            .setOnCancelListener { onAnswer(false) }
            .show()
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

    // ------------------------------------------------------------------ downloads

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(R.string.download_unsupported)
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ensurePermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
                if (granted.values.all { it }) {
                    startDownload(url, userAgent, contentDisposition, mimeType)
                } else {
                    toast(R.string.storage_denied)
                }
            }
        } else {
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            toast(R.string.download_failed)
        }
    }

    // ------------------------------------------------------------------ misc

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.clear_data_short) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
                webView.clearHistory()
                toast(R.string.clear_done)
                webView.loadUrl(startUrl)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        /**
         * Intent untuk membuka situs. `data` diisi URL agar setiap situs punya "dokumen"
         * sendiri (kartu terpisah di Recents), dan action wajib ada untuk pintasan.
         */
        fun createIntent(context: Context, url: String): Intent =
            Intent(context, BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
                putExtra(EXTRA_URL, url)
            }
    }
}
