package com.jrxrezkyy.webcloner

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan singkat langkah-langkah penting (unduh, simpan, instal) beserta alasan gagalnya.
 * Ditampilkan di Pengaturan > Diagnostik supaya penyebab masalah bisa dibaca tanpa logcat.
 */
object Diag {
    private const val FILE = "diag.log"
    private const val MAX = 48 * 1024
    private val lock = Any()
    @Volatile private var dir: File? = null

    fun init(c: Context) {
        dir = c.applicationContext.filesDir
    }

    fun log(tag: String, msg: String) {
        val d = dir ?: return
        val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) +
            " [" + tag + "] " + msg.take(300).replace('\n', ' ') + "\n"
        synchronized(lock) {
            try {
                val f = File(d, FILE)
                if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
                f.appendText(line)
            } catch (e: Exception) {
                // catatan hanya pelengkap
            }
        }
    }

    fun read(c: Context): String = synchronized(lock) {
        try {
            File(c.filesDir, FILE).readText().takeLast(12000)
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(c: Context) {
        synchronized(lock) {
            try {
                File(c.filesDir, FILE).delete()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    fun err(e: Throwable): String = e.javaClass.simpleName + ": " + (e.message ?: "")
}
