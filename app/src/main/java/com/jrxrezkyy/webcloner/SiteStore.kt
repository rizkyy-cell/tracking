package com.jrxrezkyy.webcloner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Satu "akun" = satu entri. Domain yang sama boleh punya banyak entri; masing-masing
 * memakai profil WebView sendiri sehingga login dan cookie-nya terpisah.
 *
 * @param num nomor urut akun untuk domain ini (1, 2, 3, ...), dipakai sebagai lencana ikon.
 */
data class Site(val id: String, val url: String, val name: String, val num: Int) {
    val host: String get() = UrlUtils.host(url)
    val profile: String get() = "acc_$id"
    val badge: String? get() = if (num > 1) num.toString() else null
}

/** Penyimpanan daftar akun, ikon (favicon), dan preferensi. */
object SiteStore {

    private const val PREFS = "webcloner"
    private const val KEY_SITES = "sites"
    private const val KEY_PULL_REFRESH = "pull_refresh"
    private const val KEY_THEME = "theme"
    private const val KEY_BADGE = "show_badge"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<Site> {
        val raw = prefs(c).getString(KEY_SITES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<Site>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.getString("url")
                val id = o.optString("id").ifBlank { "l" + Integer.toHexString(url.hashCode()) }
                val name = o.optString("name", UrlUtils.host(url))
                result.add(Site(id, url, name, o.optInt("num", 1)))
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(c: Context, list: List<Site>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("url", s.url)
                    .put("name", s.name)
                    .put("num", s.num)
            )
        }
        prefs(c).edit().putString(KEY_SITES, arr.toString()).apply()
    }

    fun get(c: Context, id: String): Site? = all(c).firstOrNull { it.id == id }

    fun countForHost(c: Context, host: String): Int = all(c).count { it.host == host }

    /** Membuat akun baru untuk [url]. Selalu membuat entri baru, tidak menimpa yang lama. */
    fun create(c: Context, url: String): Site {
        val host = UrlUtils.host(url)
        val list = all(c).toMutableList()
        val num = (list.filter { it.host == host }.maxOfOrNull { it.num } ?: 0) + 1
        val name = if (num == 1) host else "$host $num"
        val site = Site(UUID.randomUUID().toString().replace("-", ""), url, name, num)
        list.add(site)
        save(c, list)
        return site
    }

    fun rename(c: Context, id: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        save(c, all(c).map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Menghapus akun beserta seluruh data login-nya. */
    fun remove(c: Context, site: Site) {
        save(c, all(c).filterNot { it.id == site.id })
        prefs(c).edit().remove("desktop_${site.id}").apply()
        clearCustomIcon(c, site.id)
        Profiles.delete(site)
    }

    // ---- Ikon khusus (dipilih pengguna) ----

    private fun customIconFile(c: Context, id: String): File {
        val dir = File(c.filesDir, "icons")
        dir.mkdirs()
        return File(dir, "$id.png")
    }

    fun hasCustomIcon(c: Context, id: String): Boolean = customIconFile(c, id).exists()

    /** Berubah setiap ikon khusus diganti; dipakai daftar untuk tahu kapan harus menggambar ulang. */
    fun iconStamp(c: Context, id: String): Long = customIconFile(c, id).lastModified()

    /** Tampilkan lencana angka di ikon akun ke-2 dan seterusnya. */
    fun showBadge(c: Context): Boolean = prefs(c).getBoolean(KEY_BADGE, true)

    fun setShowBadge(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_BADGE, value).apply()
    }

    fun customIcon(c: Context, id: String): Bitmap? {
        val f = customIconFile(c, id)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun clearCustomIcon(c: Context, id: String) {
        customIconFile(c, id).delete()
    }

    /**
     * Membaca gambar dari [uri], memotong persegi di tengah, memperkecil ke 256 px, lalu
     * menyimpannya sebagai ikon akun. Dibaca dua kali: pertama hanya ukuran (supaya gambar
     * besar tidak memenuhi memori), kedua dengan inSampleSize.
     */
    fun saveCustomIconFrom(c: Context, id: String, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) {
                sample *= 2
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val src = c.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
            customIconFile(c, id).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    // ---- Favicon (per domain) ----

    private fun faviconFile(c: Context, host: String): File {
        val dir = File(c.filesDir, "favicons")
        dir.mkdirs()
        return File(dir, host.replace(Regex("[^A-Za-z0-9.-]"), "_") + ".png")
    }

    fun saveFavicon(c: Context, host: String, bitmap: Bitmap) {
        try {
            faviconFile(c, host).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Exception) {
            // Abaikan: ikon hanya pelengkap.
        }
    }

    fun favicon(c: Context, host: String): Bitmap? {
        val f = faviconFile(c, host)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    // ---- Preferensi ----

    fun isDesktop(c: Context, id: String): Boolean =
        prefs(c).getBoolean("desktop_$id", false)

    fun setDesktop(c: Context, id: String, value: Boolean) {
        prefs(c).edit().putBoolean("desktop_$id", value).apply()
    }

    /** 0 = ikuti sistem, 1 = terang, 2 = gelap. */
    fun theme(c: Context): Int = prefs(c).getInt(KEY_THEME, 0)

    fun setTheme(c: Context, value: Int) {
        prefs(c).edit().putInt(KEY_THEME, value).apply()
    }

    fun pullRefresh(c: Context): Boolean = prefs(c).getBoolean(KEY_PULL_REFRESH, true)

    fun setPullRefresh(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_PULL_REFRESH, value).apply()
    }
}
