package com.jrxrezkyy.webcloner

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Membuka akun di "kartu" (task) miliknya sendiri.
 *
 * Penyebab bug lama: pintasan layar utama diluncurkan launcher dengan flag standar, sehingga
 * sistem (terutama pengelola jendela mengambang di beberapa HP) mencocokkan task yang sudah
 * ada milik aplikasi ini dan hanya memunculkannya, lalu akun B tidak pernah dibuka.
 *
 * Perbaikan: kita yang memutuskan.
 *  - Jika task untuk akun ini sudah ada -> bawa ke depan (tidak ada duplikat).
 *  - Jika belum -> buat task BARU dengan NEW_DOCUMENT + MULTIPLE_TASK, yang memaksa sistem
 *    membuat kartu terpisah untuk setiap akun, bahkan saat akun lain sedang terbuka.
 */
object TaskLauncher {

    private fun dataFor(site: Site) = "webcloner://site/${site.id}"

    fun intent(context: Context, site: Site): Intent =
        Intent(context, BrowserActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(dataFor(site))
            putExtra(BrowserActivity.EXTRA_SITE_ID, site.id)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        }

    fun open(context: Context, site: Site) {
        // Jika dipanggil dari ShortcutActivity, task perantara itu sendiri tidak boleh dianggap
        // sebagai kartu akun (lihat findTask).
        val ownTask = (context as? android.app.Activity)?.taskId ?: -1
        if (bringToFront(context, site, ownTask)) return
        try {
            val options = ActivityOptions.makeCustomAnimation(
                context, R.anim.zoom_enter, R.anim.zoom_exit
            ).toBundle()
            context.startActivity(intent(context, site), options)
        } catch (e: Exception) {
            // Cadangan: buka tanpa flag dokumen bila sistem menolak kombinasi flag.
            try {
                context.startActivity(
                    intent(context, site).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            } catch (e2: Exception) {
                android.widget.Toast.makeText(
                    context, "Gagal membuka: ${e2.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findTask(context: Context, site: Site, ownTask: Int = -1): ActivityManager.AppTask? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val wanted = dataFor(site)
            am.appTasks.firstOrNull { task ->
                val info = task.taskInfo
                val base = info?.baseIntent
                if (info != null && info.persistentId == ownTask) return@firstOrNull false
                // Hanya kartu BrowserActivity. Tanpa pemeriksaan komponen, task perantara
                // ShortcutActivity (yang intent dasarnya membawa data yang sama) ikut cocok,
                // sehingga "bawa ke depan" mengenai dirinya sendiri dan tidak ada yang terbuka.
                base != null &&
                    base.component?.className == BrowserActivity::class.java.name &&
                    base.dataString == wanted
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun bringToFront(context: Context, site: Site, ownTask: Int): Boolean {
        val task = findTask(context, site, ownTask) ?: return false
        return try {
            task.moveToFront()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Menutup kartu akun (dipakai saat akun dihapus). */
    fun close(context: Context, site: Site) {
        try {
            findTask(context, site)?.finishAndRemoveTask()
        } catch (e: Exception) {
            // abaikan
        }
    }
}
