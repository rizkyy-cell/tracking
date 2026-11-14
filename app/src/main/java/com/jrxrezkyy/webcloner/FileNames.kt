package com.jrxrezkyy.webcloner

import android.net.Uri
import android.webkit.MimeTypeMap
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menentukan nama dan jenis (MIME) file unduhan dengan urutan yang masuk akal.
 *
 * Penyebab unduhan sering gagal atau salah format sebelumnya: nama ditebak dari URL lama dengan
 * jenis konten yang tidak cocok (mis. .bin, atau nama ganda "file.xyz.zip"). Sekarang:
 *  1. nama dari header Content-Disposition (termasuk filename*= UTF-8) milik respons asli,
 *  2. nama dari atribut `download` tautan, 3. nama dari URL, 4. nama berbasis waktu;
 *  lalu ekstensi dan MIME diselaraskan: ekstensi yang dikenal dipercaya, ekstensi lemah
 *  (.bin, .php, dst.) diganti sesuai jenis dari server.
 */
object FileNames {

    private val BAD = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val PCT = Regex("%[0-9A-Fa-f]{2}")

    private val GENERIC = setOf(
        "", "application/octet-stream", "binary/octet-stream", "application/x-download",
        "application/download", "application/force-download", "application/unknown",
        "application/x-unknown"
    )

    /** Ekstensi yang tidak menunjukkan isi sebenarnya. */
    private val WEAK = setOf(
        "bin", "dat", "tmp", "download", "php", "asp", "aspx", "jsp", "cgi", "do", "action", "axd", "ashx"
    )

    fun fromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null

        val star = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)
        if (star != null) {
            val charset = star.groupValues[1].ifBlank { "UTF-8" }
            val enc = star.groupValues[2].trim().trim('"').replace("+", "%2B")
            try {
                return URLDecoder.decode(enc, charset)
            } catch (e: Exception) {
                // lanjut ke filename= biasa
            }
        }

        val plain = Regex(
            "filename\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^;]+))", RegexOption.IGNORE_CASE
        ).find(cd) ?: return null
        var v = plain.groups[1]?.value ?: plain.groups[2]?.value ?: return null
        v = v.trim().replace("\\\"", "\"")
        if (PCT.containsMatchIn(v)) {
            try {
                v = URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                // biarkan apa adanya
            }
        }
        return fixMojibake(v).ifBlank { null }
    }

    /** Header HTTP dibaca sebagai ISO-8859-1; bila isinya sebenarnya UTF-8, kembalikan. */
    private fun fixMojibake(s: String): String {
        if (s.none { it.code in 0x80..0xFF }) return s
        if (s.any { it.code > 0xFF }) return s
        return try {
            val fixed = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (fixed.contains('\uFFFD')) s else fixed
        } catch (e: Exception) {
            s
        }
    }

    fun fromUrl(url: String): String? {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return null
        }
        for (key in listOf("filename", "file_name", "fileName", "file", "name", "download")) {
            val v = try { uri.getQueryParameter(key) } catch (e: Exception) { null }
            if (!v.isNullOrBlank() && v.length <= 150 && v.contains('.')) return v
        }
        val seg = uri.lastPathSegment ?: return null
        return seg.takeIf { it.isNotBlank() && it != "/" }
    }

    fun sanitize(raw: String): String {
        var n = raw.replace(BAD, "_").trim().trim('.', ' ')
        if (n.length > 120) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 10) n.substring(dot) else ""
            n = n.substring(0, 120 - ext.length) + ext
        }
        return n
    }

    /** Mengembalikan pasangan (nama akhir, MIME) yang saling cocok. */
    fun resolve(rawName: String?, serverMime: String?): Pair<String, String> {
        val map = MimeTypeMap.getSingleton()
        val sm = serverMime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val specific = sm !in GENERIC && sm.contains('/')
        val extOfServer = if (specific) map.getExtensionFromMimeType(sm) else null

        var name = sanitize(rawName.orEmpty())
        if (name.isBlank()) {
            name = "unduhan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        }
        val dot = name.lastIndexOf('.')
        var ext = if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
        if (ext.length > 8 || !ext.all { it.isLetterOrDigit() }) ext = ""
        val mimeOfExt = if (ext.isNotEmpty()) map.getMimeTypeFromExtension(ext) else null
        val base = if (ext.isNotEmpty()) name.substring(0, dot) else name

        // 1. Ekstensi dikenal dan bukan ekstensi lemah: percaya ekstensi.
        if (mimeOfExt != null && !(ext in WEAK && extOfServer != null)) {
            return name to mimeOfExt
        }
        // 2. Server memberi jenis spesifik: pakai ekstensi sesuai jenis itu.
        if (extOfServer != null && (ext.isEmpty() || ext in WEAK)) {
            return "$base.$extOfServer" to sm
        }
        // 3. Ekstensi asing tapi jenis server spesifik dan cocok dengan salah satu ekstensi? Biarkan nama.
        return name to "application/octet-stream"
    }
}
