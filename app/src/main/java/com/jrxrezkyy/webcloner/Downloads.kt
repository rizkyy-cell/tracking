package com.jrxrezkyy.webcloner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class DlStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }

/** Permintaan unduhan HTTP. [cookie] hanya dipakai untuk host asal (bukan host pengalihan). */
class HttpReq(
    val url: String,
    val userAgent: String,
    val referer: String?,
    val cookie: String?,
    val dispositionHint: String?,
    val mimeHint: String?,
    val nameHint: String?
)

class DownloadItem(val id: Long, @Volatile var name: String, val createdAt: Long) {
    @Volatile var status: DlStatus = DlStatus.QUEUED
    @Volatile var total: Long = -1L
    @Volatile var done: Long = 0L
    @Volatile var uri: String? = null
    @Volatile var mime: String = ""
    @Volatile var error: String? = null
    @Volatile var where: String = ""
    @Volatile var cancel: Boolean = false
    @Volatile var request: HttpReq? = null

    val active: Boolean get() = status == DlStatus.QUEUED || status == DlStatus.RUNNING
}

private class CancelException : Exception()

private class HttpError(val code: Int) : IOException("HTTP $code")

/**
 * Mesin unduhan milik aplikasi (bukan DownloadManager), supaya:
 *  - banyak unduhan berjalan bersamaan (3 paralel, sisanya antre),
 *  - folder tujuan bisa dipilih (SAF) dan nama/format file selalu benar,
 *  - unduhan blob:/data: dan HTTP memakai jalur penyimpanan yang sama,
 *  - tetap berjalan saat aplikasi ditinggalkan (layanan latar depan + notifikasi).
 */
object Downloads {

    private const val PREFS = "webcloner"
    private const val KEY_HISTORY = "dl_history"
    private const val KEY_TREE = "dl_tree"
    private const val KEY_ASKED_NOTIF = "dl_asked_notif"
    private const val KEEP = 60

    private val items = CopyOnWriteArrayList<DownloadItem>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val ids = AtomicLong(System.currentTimeMillis())
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
    @Volatile private var loaded = false

    private val pool: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "wc-download").apply { isDaemon = true }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ daftar dan pemantau

    fun list(c: Context): List<DownloadItem> {
        load(c.applicationContext)
        return items.toList()
    }

    fun activeCount(): Int = items.count { it.active }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Memberi tahu pemantau (di thread utama), paling cepat tiap 250 ms. */
    fun changed() {
        if (pending.compareAndSet(false, true)) {
            main.postDelayed({
                pending.set(false)
                for (l in listeners) {
                    try {
                        l()
                    } catch (e: Exception) {
                        // satu pemantau bermasalah tidak boleh mengganggu yang lain
                    }
                }
            }, 250L)
        }
    }

    // ------------------------------------------------------------------ pengaturan folder

    fun treeUri(c: Context): Uri? =
        prefs(c).getString(KEY_TREE, null)?.let { Uri.parse(it) }

    fun setTree(c: Context, uri: Uri?) {
        val old = treeUri(c)
        if (old != null && old != uri) {
            try {
                c.contentResolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // sudah tidak berlaku
            }
        }
        prefs(c).edit().apply {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
        }.apply()
    }

    fun folderLabel(c: Context): String {
        val tree = treeUri(c) ?: return c.getString(R.string.dl_folder_default)
        return try {
            val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
            id.substringAfter(':', id).ifBlank { c.getString(R.string.dl_folder_default) }
        } catch (e: Exception) {
            tree.lastPathSegment ?: c.getString(R.string.dl_folder_default)
        }
    }

    fun askedNotificationPermission(c: Context): Boolean = prefs(c).getBoolean(KEY_ASKED_NOTIF, false)

    fun markAskedNotificationPermission(c: Context) {
        prefs(c).edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
    }

    // ------------------------------------------------------------------ antrean

    fun enqueueHttp(c: Context, req: HttpReq): DownloadItem {
        val app = c.applicationContext
        load(app)
        val guess = FileNames.sanitize(
            FileNames.fromDisposition(req.dispositionHint)
                ?: req.nameHint
                ?: FileNames.fromUrl(req.url)
                ?: ""
        )
        val item = DownloadItem(ids.incrementAndGet(), guess.ifBlank { "unduhan" }, System.currentTimeMillis())
        item.request = req
        items.add(0, item)
        DownloadService.ensure(app)
        changed()
        pool.execute { runHttp(app, item) }
        return item
    }

    /** Unduhan yang isinya sudah ada di memori (alamat data:). */
    fun enqueueBytes(c: Context, rawName: String?, mime: String?, bytes: ByteArray) {
        val app = c.applicationContext
        load(app)
        val (name, finalMime) = FileNames.resolve(rawName, mime)
        val item = DownloadItem(ids.incrementAndGet(), name, System.currentTimeMillis())
        item.total = bytes.size.toLong()
        items.add(0, item)
        DownloadService.ensure(app)
        changed()
        pool.execute {
            item.status = DlStatus.RUNNING
            var sink: SinkTarget? = null
            try {
                sink = Sinks.open(app, name, finalMime)
                item.name = sink.name
                item.mime = finalMime
                item.where = sink.where
                sink.stream.write(bytes)
                sink.stream.flush()
                sink.stream.close()
                item.done = bytes.size.toLong()
                item.uri = sink.commit()?.toString()
                sink = null
                finish(app, item, DlStatus.DONE, null)
            } catch (e: Throwable) {
                abortSink(sink)
                finish(app, item, DlStatus.FAILED, friendly(e))
            }
        }
    }

    /** Unduhan yang datanya dikirim bertahap oleh halaman (blob:). Bisa banyak sekaligus. */
    class Stream internal constructor(
        private val c: Context,
        private val item: DownloadItem,
        private val sink: SinkTarget
    ) {
        @Volatile private var closed = false

        fun write(bytes: ByteArray) {
            if (closed) return
            if (item.cancel) {
                fail("Dibatalkan")
                return
            }
            sink.stream.write(bytes)
            item.done += bytes.size
            Downloads.changed()
        }

        fun finish() {
            if (closed) return
            closed = true
            try {
                sink.stream.flush()
                sink.stream.close()
                item.uri = sink.commit()?.toString()
                Downloads.finish(c, item, DlStatus.DONE, null)
            } catch (e: Throwable) {
                try { sink.abort() } catch (e2: Exception) { /* abaikan */ }
                Downloads.finish(c, item, DlStatus.FAILED, Downloads.friendly(e))
            }
        }

        fun fail(message: String?) {
            if (closed) return
            closed = true
            try { sink.stream.close() } catch (e: Exception) { /* abaikan */ }
            try { sink.abort() } catch (e: Exception) { /* abaikan */ }
            Downloads.finish(
                c, item,
                if (item.cancel) DlStatus.CANCELED else DlStatus.FAILED,
                if (item.cancel) null else message
            )
        }
    }

    fun beginStream(c: Context, rawName: String?, mime: String?, total: Long): Stream? {
        val app = c.applicationContext
        load(app)
        return try {
            val (name, finalMime) = FileNames.resolve(rawName, mime)
            val sink = Sinks.open(app, name, finalMime)
            val item = DownloadItem(ids.incrementAndGet(), sink.name, System.currentTimeMillis())
            item.status = DlStatus.RUNNING
            item.total = total
            item.mime = finalMime
            item.where = sink.where
            items.add(0, item)
            DownloadService.ensure(app)
            changed()
            Stream(app, item, sink)
        } catch (e: Throwable) {
            null
        }
    }

    fun cancel(item: DownloadItem) {
        item.cancel = true
        changed()
    }

    fun retry(c: Context, item: DownloadItem) {
        val req = item.request ?: return
        if (item.active) return
        val app = c.applicationContext
        item.cancel = false
        item.done = 0L
        item.total = -1L
        item.error = null
        item.uri = null
        item.status = DlStatus.QUEUED
        DownloadService.ensure(app)
        changed()
        pool.execute { runHttp(app, item) }
    }

    fun remove(c: Context, item: DownloadItem) {
        if (item.active) return
        items.remove(item)
        persist(c.applicationContext)
        changed()
    }

    fun clearFinished(c: Context) {
        items.removeAll(items.filter { !it.active }.toSet())
        persist(c.applicationContext)
        changed()
    }

    // ------------------------------------------------------------------ HTTP

    private fun connect(req: HttpReq): HttpURLConnection {
        var url = req.url
        val origHost = Uri.parse(req.url).host
        var hops = 0
        while (true) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", req.userAgent)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (!req.referer.isNullOrBlank()) conn.setRequestProperty("Referer", req.referer)
            val host = Uri.parse(url).host
            if (!req.cookie.isNullOrBlank() && host != null && host == origHost) {
                conn.setRequestProperty("Cookie", req.cookie)
            }
            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                hops++
                if (location.isNullOrBlank() || hops > 10) throw IOException("Terlalu banyak pengalihan")
                url = URL(URL(url), location).toString()
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw HttpError(code)
            }
            return conn
        }
    }

    private fun runHttp(c: Context, item: DownloadItem) {
        val req = item.request
        if (req == null || item.cancel) {
            finish(c, item, DlStatus.CANCELED, null)
            return
        }
        item.status = DlStatus.RUNNING
        changed()
        var conn: HttpURLConnection? = null
        var sink: SinkTarget? = null
        try {
            conn = connect(req)
            item.total = conn.contentLengthLong
            val raw = FileNames.fromDisposition(conn.getHeaderField("Content-Disposition"))
                ?: FileNames.fromDisposition(req.dispositionHint)
                ?: req.nameHint?.takeIf { it.isNotBlank() }
                ?: FileNames.fromUrl(conn.url.toString())
                ?: FileNames.fromUrl(req.url)
            val (name, mime) = FileNames.resolve(raw, conn.contentType ?: req.mimeHint)
            sink = Sinks.open(c, name, mime)
            item.name = sink.name
            item.mime = mime
            item.where = sink.where
            changed()

            val buf = ByteArray(64 * 1024)
            val input = conn.inputStream
            val out = sink.stream
            while (true) {
                if (item.cancel) throw CancelException()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                item.done += n
                changed()
            }
            out.flush()
            out.close()
            item.uri = sink.commit()?.toString()
            sink = null
            finish(c, item, DlStatus.DONE, null)
        } catch (e: CancelException) {
            abortSink(sink)
            finish(c, item, DlStatus.CANCELED, null)
        } catch (e: Throwable) {
            abortSink(sink)
            finish(c, item, DlStatus.FAILED, friendly(e))
        } finally {
            conn?.disconnect()
        }
    }

    private fun abortSink(sink: SinkTarget?) {
        if (sink == null) return
        try { sink.stream.close() } catch (e: Exception) { /* abaikan */ }
        try { sink.abort() } catch (e: Exception) { /* abaikan */ }
    }

    private fun friendly(e: Throwable): String = when {
        e is HttpError && (e.code == 401 || e.code == 403) ->
            "Ditolak server (HTTP ${e.code}). Muat ulang halaman lalu unduh lagi."
        e is HttpError && e.code == 404 -> "File tidak ditemukan (HTTP 404)."
        e is HttpError -> "Server membalas HTTP ${e.code}."
        e is UnknownHostException -> "Tidak ada koneksi internet."
        e is SocketTimeoutException -> "Koneksi lambat atau terputus."
        e is SecurityException -> "Tidak ada izin menulis ke folder tujuan."
        e.message?.contains("ENOSPC") == true -> "Penyimpanan penuh."
        else -> e.message?.take(120) ?: e.javaClass.simpleName
    }

    // ------------------------------------------------------------------ selesai dan riwayat

    internal fun finish(c: Context, item: DownloadItem, status: DlStatus, error: String?) {
        item.error = error
        item.status = status
        persist(c)
        DlNotify.finished(c, item)
        if (status == DlStatus.DONE) {
            main.post {
                Toast.makeText(c, c.getString(R.string.dl_saved, item.name), Toast.LENGTH_SHORT).show()
            }
        }
        changed()
    }

    private fun persist(c: Context) {
        val finished = items.filter { !it.active }
        if (finished.size > KEEP) items.removeAll(finished.drop(KEEP).toSet())
        val arr = JSONArray()
        for (d in items.filter { x -> !x.active }) {
            val o = JSONObject()
                .put("id", d.id)
                .put("name", d.name)
                .put("t", d.createdAt)
                .put("status", d.status.name)
                .put("total", d.total)
                .put("done", d.done)
                .put("mime", d.mime)
                .put("where", d.where)
            d.uri?.let { u -> o.put("uri", u) }
            d.error?.let { e -> o.put("error", e) }
            d.request?.let { r ->
                o.put("url", r.url).put("ua", r.userAgent)
                r.referer?.let { x -> o.put("ref", x) }
            }
            arr.put(o)
        }
        prefs(c).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(c).getString(KEY_HISTORY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = DownloadItem(o.getLong("id"), o.getString("name"), o.optLong("t"))
                item.status = try { DlStatus.valueOf(o.getString("status")) } catch (e: Exception) { DlStatus.FAILED }
                item.total = o.optLong("total", -1L)
                item.done = o.optLong("done", 0L)
                item.mime = o.optString("mime")
                item.where = o.optString("where")
                item.uri = o.optString("uri").ifBlank { null }
                item.error = o.optString("error").ifBlank { null }
                val url = o.optString("url")
                if (url.isNotBlank()) {
                    item.request = HttpReq(url, o.optString("ua"), o.optString("ref").ifBlank { null },
                        null, null, null, null)
                }
                items.add(item)
            }
        } catch (e: Exception) {
            // riwayat rusak: abaikan
        }
    }
}
