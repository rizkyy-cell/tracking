package com.jrxrezkyy.webcloner

import android.app.ActivityManager
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
        if (!bringToFront(context, site)) {
            context.startActivity(intent(context, site))
        }
    }

    private fun findTask(context: Context, site: Site): ActivityManager.AppTask? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val wanted = dataFor(site)
            am.appTasks.firstOrNull { task ->
                val base = task.taskInfo?.baseIntent
                base != null && base.dataString == wanted
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun bringToFront(context: Context, site: Site): Boolean {
        val task = findTask(context, site) ?: return false
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
