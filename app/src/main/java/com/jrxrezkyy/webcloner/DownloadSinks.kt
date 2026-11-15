package com.jrxrezkyy.webcloner

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Tujuan unduhan yang ditentukan per situs (lihat [DownloadLocation]). */
sealed class Dest {
    /** Folder Download publik HP. */
    object Default : Dest()

    /** Folder tetap pilihan pengguna (pohon dokumen SAF). */
    class Tree(val uri: Uri) : Dest()

    /** Satu file yang dipilih pengguna lewat "Simpan sebagai" (ACTION_CREATE_DOCUMENT). */
    class Doc(val uri: Uri) : Dest()
}

/**
 * Tempat tujuan satu file unduhan. [commit] dipanggil setelah semua byte tertulis dan stream
 * ditutup; mengembalikan Uri hasil. [abort] menghapus file setengah jadi.
 */
class SinkTarget(
    val stream: OutputStream,
    val name: String,
    val where: String,
    val commit: () -> Uri?,
    val abort: () -> Unit
)

object Sinks {

    /**
     * Pilihan eksplisit pengguna tidak boleh diam-diam dialihkan: bila file yang dipilih
     * ([Dest.Doc]) gagal dibuka, kesalahan dilempar agar tampil. Folder tetap ([Dest.Tree])
     * yang tidak bisa diakses lagi jatuh ke Download, dengan keterangan di daftar unduhan.
     */
    fun open(c: Context, name: String, mime: String, dest: Dest): SinkTarget {
        if (dest is Dest.Doc) {
            try {
                return openDoc(c, dest.uri, name)
            } catch (e: Exception) {
                Diag.log("sink", "file pilihan gagal dibuka: " + Diag.err(e))
                throw e
            }
        }
        if (dest is Dest.Tree) {
            try {
                return openTree(c, dest.uri, name, mime)
            } catch (e: Exception) {
                Diag.log("sink", "folder pilihan gagal: " + Diag.err(e))
                val t = openDefault(c, name, mime)
                return SinkTarget(t.stream, t.name, c.getString(R.string.dl_folder_fallback), t.commit, t.abort)
            }
        }
        return openDefault(c, name, mime)
    }

    // ------------------------------------------------------------------ file pilihan ("Simpan sebagai")

    private fun openDoc(c: Context, uri: Uri, fallbackName: String): SinkTarget {
        val resolver = c.contentResolver
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("openOutputStream null")
        var shown = fallbackName
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> shown = n }
            }
        } catch (e: Exception) {
            // nama cadangan cukup
        }
        return SinkTarget(
            out, shown, c.getString(R.string.dl_folder_chosen),
            { uri },
            {
                try {
                    DocumentsContract.deleteDocument(resolver, uri)
                } catch (e: Exception) {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (e2: Exception) {
                        // abaikan
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------ folder tetap (SAF)

    private fun openTree(c: Context, tree: Uri, name: String, mime: String): SinkTarget {
        val resolver = c.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        val existing = HashSet<String>()
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                cur.getString(0)?.let { existing.add(it.lowercase()) }
            }
        }
        val unique = uniqueName(name, existing)
        val doc = DocumentsContract.createDocument(resolver, parent, mime, unique)
            ?: throw IOException("createDocument gagal")
        val out = resolver.openOutputStream(doc, "w") ?: throw IOException("openOutputStream gagal")
        return SinkTarget(
            out, unique, Downloads.treeLabel(c, tree),
            { doc },
            {
                try {
                    DocumentsContract.deleteDocument(resolver, doc)
                } catch (e: Exception) {
                    // abaikan
                }
            }
        )
    }

    // ------------------------------------------------------------------ folder Download bawaan

    private fun openDefault(c: Context, name: String, mime: String): SinkTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return openMediaStore(c, name, mime)
            } catch (e: Exception) {
                // Beberapa ROM menolak MediaStore: simpan di folder aplikasi agar tidak gagal total.
                Diag.log("sink", "MediaStore gagal: " + Diag.err(e))
            }
            return openFile(c, name, mime, appDir(c), false)
        }
        val granted = ContextCompat.checkSelfPermission(
            c, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            openFile(c, name, mime, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), true)
        } else {
            openFile(c, name, mime, appDir(c), false)
        }
    }

    private fun appDir(c: Context): File =
        c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir

    private fun openMediaStore(c: Context, name: String, mime: String): SinkTarget {
        val resolver = c.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("insert gagal")
        val out = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("openOutputStream gagal")
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (e2: Exception) { /* abaikan */ }
            throw e
        }
        var finalName = name
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> finalName = n }
            }
        } catch (e: Exception) {
            // nama bawaan cukup
        }
        return SinkTarget(
            out, finalName, c.getString(R.string.dl_folder_default),
            {
                val done = ContentValues()
                done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, done, null, null)
                uri
            },
            {
                try {
                    resolver.delete(uri, null, null)
                } catch (e: Exception) {
                    // abaikan
                }
            }
        )
    }

    private fun openFile(c: Context, name: String, mime: String, dir: File, scan: Boolean): SinkTarget {
        dir.mkdirs()
        val file = uniqueFile(dir, name)
        val out = FileOutputStream(file)
        return SinkTarget(
            out, file.name,
            if (scan) c.getString(R.string.dl_folder_default) else c.getString(R.string.dl_folder_app),
            {
                if (scan) {
                    MediaScannerConnection.scanFile(c, arrayOf(file.absolutePath), arrayOf(mime), null)
                }
                FileProvider.getUriForFile(c, c.packageName + ".files", file)
            },
            { file.delete() }
        )
    }

    // ------------------------------------------------------------------ bantu

    private fun uniqueName(name: String, existingLower: Set<String>): String {
        if (name.lowercase() !in existingLower) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate.lowercase() !in existingLower) return candidate
            i++
        }
    }

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
}
