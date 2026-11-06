package com.jrxrezkyy.webcloner

import android.net.Uri
import java.net.URLEncoder

object UrlUtils {

    /**
     * Mengubah input pengguna menjadi URL valid.
     * - "tiktok.com"        -> "https://tiktok.com"
     * - "https://x.com/abc" -> dipakai apa adanya
     * - teks biasa          -> pencarian Google
     * Mengembalikan null jika input kosong / tidak bisa dijadikan URL.
     */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null

        val hasScheme = text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        val looksLikeHost = !text.contains(' ') &&
            (text.contains('.') || text.startsWith("localhost", ignoreCase = true))

        val candidate = when {
            hasScheme -> text
            looksLikeHost -> "https://$text"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
        }

        val uri = Uri.parse(candidate)
        return if (uri.host.isNullOrBlank()) null else candidate
    }

    /** Host tanpa awalan "www." */
    fun host(url: String): String {
        val h = Uri.parse(url).host
        return if (h.isNullOrBlank()) url else h.removePrefix("www.")
    }
}
