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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

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

/**
 * Pemilih tujuan: folder pilihan pengguna (SAF) bila ada dan masih bisa diakses; jika tidak,
 * folder Download publik (MediaStore di Android 10+, berkas langsung di Android 9 ke bawah).
 */
object Sinks {

    fun open(c: Context, name: String, mime: String): SinkTarget {
        val tree = Downloads.treeUri(c)
        if (tree != null) {
            try {
                return openTree(c, tree, name, mime)
            } catch (e: Exception) {
                // Izin folder hilang atau folder dihapus: jatuh ke folder Download bawaan.
            }
        }
        val target = openDefault(c, name, mime)
        return if (tree != null) {
            SinkTarget(target.stream, target.name, c.getString(R.string.dl_folder_fallback),
                target.commit, target.abort)
        } else {
            target
        }
    }

    // ------------------------------------------------------------------ folder pilihan (SAF)

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
            out, unique, Downloads.folderLabel(c),
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openMediaStore(c, name, mime)
        } else {
            openLegacy(c, name, mime)
        }
    }

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

    private fun openLegacy(c: Context, name: String, mime: String): SinkTarget {
        val granted = ContextCompat.checkSelfPermission(
            c, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val dir: File = if (granted) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir
        }
        dir.mkdirs()
        val file = uniqueFile(dir, name)
        val out = FileOutputStream(file)
        return SinkTarget(
            out, file.name,
            if (granted) c.getString(R.string.dl_folder_default) else c.getString(R.string.dl_folder_app),
            {
                if (granted) {
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
