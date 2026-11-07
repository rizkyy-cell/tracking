package com.jrxrezkyy.webcloner

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Dasar semua layar: latar cairan penuh layar + konten di atasnya, tepi-ke-tepi (di belakang
 * status bar dan bilah navigasi) dengan padding sesuai inset sistem dan papan ketik.
 */
abstract class GlassActivity : AppCompatActivity() {

    /** Akar layar (latar + konten). Overlay layar penuh boleh ditambahkan ke sini. */
    protected lateinit var rootFrame: FrameLayout

    private var iconTarget: Site? = null
    private var iconDone: (() -> Unit)? = null

    // Harus didaftarkan saat konstruksi (sebelum STARTED), makanya berupa properti.
    private val iconPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            val site = iconTarget
            val done = iconDone
            iconTarget = null
            iconDone = null
            if (uri != null && site != null) {
                if (SiteStore.saveCustomIconFrom(this, site.id, uri)) {
                    ShortcutHelper.refresh(this, site)
                    Toast.makeText(this, R.string.icon_updated, Toast.LENGTH_SHORT).show()
                    done?.invoke()
                } else {
                    Toast.makeText(this, R.string.icon_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    protected fun setGlassContent(content: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val night = Glass.night(this)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night

        rootFrame = FrameLayout(this)
        rootFrame.addView(GlassBackgroundView(this), FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))

        val inset = FrameLayout(this)
        inset.addView(content, FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))
        ViewCompat.setOnApplyWindowInsetsListener(inset) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        rootFrame.addView(inset, FrameLayout.LayoutParams(Glass.MATCH, Glass.MATCH))
        setContentView(rootFrame)
    }

    /** Membuka pemilih gambar untuk ikon khusus akun [site]; [done] dipanggil bila berhasil. */
    fun pickIcon(site: Site, done: () -> Unit) {
        iconTarget = site
        iconDone = done
        try {
            iconPicker.launch("image/*")
        } catch (e: Exception) {
            iconTarget = null
            iconDone = null
            Toast.makeText(this, R.string.icon_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
