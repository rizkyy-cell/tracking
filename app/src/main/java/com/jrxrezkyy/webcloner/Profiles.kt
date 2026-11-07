package com.jrxrezkyy.webcloner

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Pembungkus fitur profil WebView (androidx.webkit).
 *
 * Satu profil = satu set cookie, localStorage, cache, dan izin yang terpisah. Karena itu
 * lima akun TikTok bisa login bersamaan tanpa saling menimpa. Fitur ini butuh
 * "Android System WebView" versi cukup baru; bila tidak ada, semua akun jatuh ke
 * penyimpanan bersama (perilaku lama).
 */
object Profiles {

    fun supported(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    } catch (e: Throwable) {
        false
    }

    /**
     * Mengikat [webView] ke profil milik [site]. HARUS dipanggil sebelum WebView memuat
     * halaman apa pun. Mengembalikan true jika profil terpasang.
     */
    fun apply(webView: WebView, site: Site): Boolean {
        if (!supported()) return false
        return try {
            WebViewCompat.setProfile(webView, site.profile)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun cookieManager(site: Site, usingProfile: Boolean): CookieManager {
        if (usingProfile) {
            try {
                return ProfileStore.getInstance().getOrCreateProfile(site.profile).cookieManager
            } catch (e: Exception) {
                // jatuh ke pengelola cookie bersama
            }
        }
        return CookieManager.getInstance()
    }

    fun webStorage(site: Site, usingProfile: Boolean): WebStorage {
        if (usingProfile) {
            try {
                return ProfileStore.getInstance().getOrCreateProfile(site.profile).webStorage
            } catch (e: Exception) {
                // jatuh ke penyimpanan bersama
            }
        }
        return WebStorage.getInstance()
    }

    /** Menghapus data login akun lalu profilnya. Gagal diam-diam jika profil masih dipakai. */
    fun delete(site: Site) {
        if (!supported()) return
        try {
            val profile = ProfileStore.getInstance().getOrCreateProfile(site.profile)
            profile.cookieManager.removeAllCookies(null)
            profile.webStorage.deleteAllData()
        } catch (e: Exception) {
            // abaikan
        }
        try {
            ProfileStore.getInstance().deleteProfile(site.profile)
        } catch (e: Exception) {
            // Profil masih dipakai oleh layar yang terbuka; datanya sudah dikosongkan di atas.
        }
    }

    /**
     * Mengosongkan login dan data situs milik akun, tetapi profilnya tetap ada.
     * Tanpa dukungan profil, yang dikosongkan adalah penyimpanan bersama (semua akun).
     */
    fun clearData(site: Site) {
        if (supported()) {
            try {
                val profile = ProfileStore.getInstance().getOrCreateProfile(site.profile)
                profile.cookieManager.removeAllCookies(null)
                profile.cookieManager.flush()
                profile.webStorage.deleteAllData()
                return
            } catch (e: Exception) {
                // jatuh ke penyimpanan bersama
            }
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}
