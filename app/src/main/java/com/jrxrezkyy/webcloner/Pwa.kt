package com.jrxrezkyy.webcloner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data "aplikasi terinstal" sebuah situs, dibaca dari Web App Manifest (seperti Chrome).
 *
 * @param display standalone / fullscreen / minimal-ui / browser. Selain "browser", halaman
 *                dibuka tanpa bilah Web Cloner, seperti aplikasi biasa.
 * @param maskable true jika ikon manifest memang dirancang untuk dipotong bentuk ikon sistem.
 */
data class PwaInfo(
    val name: String,
    val shortName: String,
    val startUrl: String,
    val display: String,
    val themeColor: Int?,
    val backgroundColor: Int?,
    val maskable: Boolean,
    val hasIcon: Boolean
) {
    val standalone: Boolean get() = display != "browser"

    fun toJson(): String = JSONObject()
        .put("name", name)
        .put("shortName", shortName)
        .put("startUrl", startUrl)
        .put("display", display)
        .put("theme", themeColor ?: JSONObject.NULL)
        .put("bg", backgroundColor ?: JSONObject.NULL)
        .put("maskable", maskable)
        .put("hasIcon", hasIcon)
        .toString()

    companion object {
        fun fromJson(raw: String): PwaInfo? = try {
            val o = JSONObject(raw)
            PwaInfo(
                o.getString("name"),
                o.optString("shortName", o.getString("name")),
                o.getString("startUrl"),
                o.optString("display", "standalone"),
                if (o.isNull("theme")) null else o.getInt("theme"),
                if (o.isNull("bg")) null else o.getInt("bg"),
                o.optBoolean("maskable", false),
                o.optBoolean("hasIcon", false)
            )
        } catch (e: Exception) {
            null
        }
    }
}

object Pwa {

    private const val PREFS = "webcloner"
    private const val MAX_BYTES = 2 * 1024 * 1024

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, siteId: String): PwaInfo? =
        prefs(c).getString("pwa_$siteId", null)?.let { PwaInfo.fromJson(it) }

    fun save(c: Context, siteId: String, info: PwaInfo, icon: Bitmap?) {
        if (icon != null) {
            try {
                FileOutputStream(iconFile(c, siteId)).use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) {
                // ikon hanya pelengkap
            }
        }
        prefs(c).edit().putString("pwa_$siteId", info.toJson()).apply()
    }

    fun remove(c: Context, siteId: String) {
        prefs(c).edit().remove("pwa_$siteId").apply()
        iconFile(c, siteId).delete()
    }

    fun icon(c: Context, siteId: String): Bitmap? {
        val f = iconFile(c, siteId)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun iconFile(c: Context, siteId: String): File {
        val dir = File(c.filesDir, "pwa")
        dir.mkdirs()
        return File(dir, "$siteId.png")
    }

    /** Hasil ambil manifest: info + gambar ikon terbaik (bila ada). */
    class Result(val info: PwaInfo, val icon: Bitmap?)

    /**
     * Mengambil dan membaca manifest. BLOKIR: panggil dari thread latar, bukan thread utama.
     * [cookie] dan [userAgent] dikirim agar manifest yang butuh login tetap terbaca.
     */
    fun fetch(
        manifestUrl: String,
        pageUrl: String,
        pageTitle: String?,
        cookie: String?,
        userAgent: String
    ): Result? {
        val text = download(manifestUrl, cookie, userAgent)?.toString(Charsets.UTF_8) ?: return null
        val json = try {
            JSONObject(text.trimStart('﻿'))
        } catch (e: Exception) {
            return null
        }

        val pageHost = UrlUtils.host(pageUrl)
        fun resolve(ref: String?): String? {
            if (ref.isNullOrBlank()) return null
            return try {
                URL(URL(manifestUrl), ref).toString()
            } catch (e: Exception) {
                null
            }
        }

        val name = json.optString("name").ifBlank { json.optString("short_name") }
            .ifBlank { pageTitle.orEmpty() }.ifBlank { pageHost }
        val shortName = json.optString("short_name").ifBlank { name }.take(24)

        // start_url hanya dipakai bila masih di situs yang sama; kalau tidak, pakai halaman ini.
        val start = resolve(json.optString("start_url"))
            ?.takeIf { UrlUtils.host(it) == pageHost && it.startsWith("http") }
            ?: pageUrl

        val override = json.optJSONArray("display_override")
        val declared = json.optString("display").ifBlank { "browser" }
        val candidates = ArrayList<String>()
        if (override != null) for (i in 0 until override.length()) candidates.add(override.optString(i))
        candidates.add(declared)
        val display = candidates.firstOrNull {
            it == "standalone" || it == "fullscreen" || it == "minimal-ui" || it == "browser"
        } ?: "standalone"

        val theme = color(json.optString("theme_color"))
        val bg = color(json.optString("background_color"))

        // Pilih ikon: PNG/JPG/WebP, ukuran terbesar yang masuk akal (>=192 diutamakan).
        class Cand(val url: String, val size: Int, val maskable: Boolean)
        val cands = ArrayList<Cand>()
        val icons = json.optJSONArray("icons") ?: JSONArray()
        for (i in 0 until icons.length()) {
            val o = icons.optJSONObject(i) ?: continue
            val src = resolve(o.optString("src")) ?: continue
            val type = o.optString("type").lowercase()
            if (type.contains("svg") || src.substringBefore('?').lowercase().endsWith(".svg")) continue
            val purpose = o.optString("purpose", "any").lowercase()
            if (purpose.trim() == "monochrome") continue
            var size = 0
            for (part in o.optString("sizes").split(' ')) {
                val w = part.lowercase().substringBefore('x').toIntOrNull() ?: continue
                if (w > size) size = w
            }
            cands.add(Cand(src, size, purpose.contains("maskable")))
        }
        cands.sortWith(compareBy<Cand> {
            when {
                it.size in 192..512 -> 0
                it.size > 512 -> 1
                it.size == 0 -> 2
                else -> 3
            }
        }.thenByDescending { if (it.size in 192..512) it.size else -it.size })

        var iconBmp: Bitmap? = null
        var maskable = false
        for (cand in cands.take(4)) {
            val bytes = download(cand.url, cookie, userAgent) ?: continue
            val bmp = decode(bytes) ?: continue
            iconBmp = bmp
            maskable = cand.maskable
            break
        }

        return Result(
            PwaInfo(name, shortName, start, display, theme, bg, maskable, iconBmp != null),
            iconBmp
        )
    }

    private fun color(raw: String): Int? = try {
        if (raw.isBlank()) null else Color.parseColor(raw.trim())
    } catch (e: Exception) {
        null
    }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (opts.outWidth / sample > 1024) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    private fun download(url: String, cookie: String?, userAgent: String): ByteArray? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            }
            if (conn.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Ikon pintasan gaya Android (adaptive, 432 px = 108 dp): latar penuh + gambar di tengah.
     * Ikon "maskable" sudah punya zona aman, jadi cukup diskalakan ke area terlihat (72/108).
     * Ikon biasa diberi ruang napas ekstra agar tidak terpotong bentuk ikon.
     */
    fun adaptiveIcon(info: PwaInfo, icon: Bitmap?, fallback: Bitmap): Bitmap {
        val size = 432
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val src = icon ?: fallback

        var back = info.backgroundColor ?: Color.WHITE
        if (icon != null && info.maskable) {
            val px = icon.getPixel(0, 0)
            if (Color.alpha(px) > 200) back = px
        }
        canvas.drawColor(back)

        val frac = if (icon != null && info.maskable) 0.667f else 0.5f
        val target = size * frac
        val left = (size - target) / 2f
        canvas.drawBitmap(src, null, RectF(left, left, left + target, left + target), paint)
        return out
    }
}
