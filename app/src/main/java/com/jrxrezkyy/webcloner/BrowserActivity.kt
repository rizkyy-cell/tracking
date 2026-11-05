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
    private lateinit var site: Site
    private lateinit var startUrl: String
    private lateinit var host: String
    private lateinit var defaultUserAgent: String
    private lateinit var cookieManager: CookieManager

    /** true jika WebView ini terikat ke profil milik akun (login terpisah). */
    private var usingProfile = false

    // Status unduhan blob:/data: yang sedang berjalan (satu per satu). Semua akses lewat blobLock.
    private val blobLock = Any()
    private var blobToken: String? = null
    private var blobStream: OutputStream? = null
    private var blobUri: Uri? = null
    private var blobFile: File? = null
    private var blobName: String = ""
    private var blobMimeHint: String = ""

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
        startUrl = site.url
        host = site.host

        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webView = binding.webView

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
        toolbar.title = site.name
        toolbar.subtitle = if (site.name != host) host else null
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationOnClickListener { finishAndRemoveTask() }
        toolbar.inflateMenu(R.menu.browser_menu)
        toolbar.menu.findItem(R.id.action_desktop).isChecked = SiteStore.isDesktop(this, site.id)
        toolbar.menu.findItem(R.id.action_pull_refresh).isChecked = SiteStore.pullRefresh(this)
        toolbar.setOnMenuItemClickListener { onMenuItem(it) }
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> webView.reload()
            R.id.action_desktop -> {
                item.isChecked = !item.isChecked
                SiteStore.setDesktop(this, site.id, item.isChecked)
                applyUserAgent()
                webView.reload()
            }
            R.id.action_pull_refresh -> {
                item.isChecked = !item.isChecked
                SiteStore.setPullRefresh(this, item.isChecked)
                binding.swipe.isEnabled = item.isChecked
            }
            R.id.action_shortcut -> ShortcutHelper.pin(this, site)
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
        setTaskDescription(
            ActivityManager.TaskDescription(
                site.name,
                IconFactory.create(host, favicon, 128, site.badge)
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
        applyUserAgent()

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
     * Menghapus penanda "; wv" dan "Version/x.y" dari User-Agent bawaan WebView sehingga
     * lebih banyak situs memperlakukannya sebagai Chrome seluler biasa.
     */
    private fun applyUserAgent() {
        val s = webView.settings
        if (SiteStore.isDesktop(this, site.id)) {
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
            view.evaluateJavascript(hookJs, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.progress.visibility = View.GONE
            binding.swipe.isRefreshing = false
            cookieManager.flush()
            view.evaluateJavascript(hookJs, null)
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
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            handleBlobDownload(url, mimeType)
            return
        }
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
                addRequestHeader("Cookie", cookieManager.getCookie(url) ?: "")
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

    // ------------------------------------------------------------------ unduhan blob: / data:

    /*
     * Alamat blob: hanya ada di dalam halaman (dibuat oleh JavaScript), jadi DownloadManager
     * tidak bisa mengambilnya. Caranya: jalankan skrip di halaman yang membaca blob itu per
     * potongan 512 KB, lalu kirim ke aplikasi lewat jembatan JavaScript untuk ditulis ke folder
     * Download. Setiap unduhan memakai token acak sekali pakai; panggilan tanpa token yang cocok
     * (misalnya dari iklan atau iframe) diabaikan, sehingga halaman lain tidak bisa menulis file.
     */

    private fun handleBlobDownload(url: String, mimeType: String?) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ensurePermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
                if (granted.values.all { it }) {
                    runBlobScript(url, mimeType)
                } else {
                    toast(R.string.storage_denied)
                }
            }
        } else {
            runBlobScript(url, mimeType)
        }
    }

    private fun runBlobScript(url: String, mimeType: String?) {
        abortBlob()
        val token = UUID.randomUUID().toString()
        synchronized(blobLock) {
            blobToken = token
            blobMimeHint = mimeType ?: ""
        }
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
                B.begin(T, name, blob.type || '');
                var size = blob.size, step = 524288, pos = 0;
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

    /** Menutup dan menghapus unduhan setengah jadi. */
    private fun abortBlob() {
        synchronized(blobLock) {
            try {
                blobStream?.close()
            } catch (e: Exception) {
                // abaikan
            }
            blobStream = null
            val uri = blobUri
            if (uri != null) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    // abaikan
                }
            }
            blobFile?.delete()
            blobUri = null
            blobFile = null
            blobToken = null
        }
    }

    private fun failBlob() {
        abortBlob()
        runOnUiThread { toast(R.string.blob_failed) }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').take(120)

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (file.exists()) {
            file = File(dir, "$base ($i)$ext")
            i++
        }
        return file
    }

    /** Dipanggil dari JavaScript (thread terpisah). Semua metode memeriksa token. */
    private inner class DownloadBridge {

        @JavascriptInterface
        fun begin(token: String, name: String, mime: String) {
            synchronized(blobLock) {
                if (token != blobToken) return
                try {
                    val type = mime.ifBlank { blobMimeHint.ifBlank { "application/octet-stream" } }
                    var fileName = sanitizeFileName(name)
                    if (fileName.isBlank()) {
                        fileName = URLUtil.guessFileName("http://localhost/download", null, type)
                    }
                    blobName = fileName

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, type)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("insert gagal")
                        blobUri = uri
                        blobStream = contentResolver.openOutputStream(uri)
                            ?: throw IOException("stream gagal")
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        val file = uniqueFile(dir, fileName)
                        blobFile = file
                        blobName = file.name
                        blobStream = FileOutputStream(file)
                    }
                } catch (e: Exception) {
                    failBlob()
                }
            }
        }

        @JavascriptInterface
        fun chunk(token: String, base64: String) {
            synchronized(blobLock) {
                if (token != blobToken) return
                val out = blobStream ?: return
                try {
                    out.write(Base64.decode(base64, Base64.DEFAULT))
                } catch (e: Exception) {
                    failBlob()
                }
            }
        }

        @JavascriptInterface
        fun finish(token: String) {
            synchronized(blobLock) {
                if (token != blobToken) return
                try {
                    blobStream?.flush()
                    blobStream?.close()
                    blobStream = null
                    val uri = blobUri
                    if (uri != null) {
                        val done = ContentValues()
                        done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(uri, done, null, null)
                    }
                    val file = blobFile
                    if (file != null) {
                        MediaScannerConnection.scanFile(
                            this@BrowserActivity, arrayOf(file.absolutePath), null, null
                        )
                    }
                    val savedName = blobName
                    blobUri = null
                    blobFile = null
                    blobToken = null
                    runOnUiThread {
                        Toast.makeText(
                            this@BrowserActivity,
                            getString(R.string.blob_saved, savedName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    failBlob()
                }
            }
        }

        @JavascriptInterface
        fun fail(token: String) {
            synchronized(blobLock) {
                if (token != blobToken) return
                failBlob()
            }
        }
    }

    // ------------------------------------------------------------------ misc

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.clear_data_short) { _, _ ->
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                Profiles.webStorage(site, usingProfile).deleteAllData()
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
        const val EXTRA_SITE_ID = "extra_site_id"
        private const val BRIDGE_NAME = "WCDownload"

        /**
         * Intent untuk membuka satu akun. `data` unik per akun (webcloner://site/<id>) sehingga
         * setiap akun mendapat kartu sendiri di Recents, bahkan untuk domain yang sama.
         */
        fun createIntent(context: Context, site: Site): Intent =
            Intent(context, BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("webcloner://site/${site.id}")
                putExtra(EXTRA_SITE_ID, site.id)
            }
    }
}
