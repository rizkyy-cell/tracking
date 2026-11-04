package com.jrxrezkyy.webcloner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        Profiles.delete(site)
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

    fun pullRefresh(c: Context): Boolean = prefs(c).getBoolean(KEY_PULL_REFRESH, true)

    fun setPullRefresh(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_PULL_REFRESH, value).apply()
    }
}
