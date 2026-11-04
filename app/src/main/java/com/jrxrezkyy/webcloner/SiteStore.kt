package com.jrxrezkyy.webcloner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Site(val url: String, val name: String)

/** Penyimpanan daftar situs, ikon (favicon), dan preferensi per situs. */
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
                result.add(Site(url, o.optString("name", UrlUtils.host(url))))
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(c: Context, list: List<Site>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("url", s.url).put("name", s.name))
        }
        prefs(c).edit().putString(KEY_SITES, arr.toString()).apply()
    }

    fun contains(c: Context, url: String): Boolean {
        val host = UrlUtils.host(url)
        return all(c).any { UrlUtils.host(it.url) == host }
    }

    /** Menambahkan situs ke urutan teratas; situs dengan host sama diganti. */
    fun add(c: Context, site: Site) {
        val host = UrlUtils.host(site.url)
        val list = all(c).filterNot { UrlUtils.host(it.url) == host }.toMutableList()
        list.add(0, site)
        save(c, list)
    }

    fun remove(c: Context, site: Site) {
        save(c, all(c).filterNot { it.url == site.url })
    }

    // ---- Favicon ----

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

    fun isDesktop(c: Context, host: String): Boolean =
        prefs(c).getBoolean("desktop_$host", false)

    fun setDesktop(c: Context, host: String, value: Boolean) {
        prefs(c).edit().putBoolean("desktop_$host", value).apply()
    }

    fun pullRefresh(c: Context): Boolean = prefs(c).getBoolean(KEY_PULL_REFRESH, true)

    fun setPullRefresh(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_PULL_REFRESH, value).apply()
    }
}
