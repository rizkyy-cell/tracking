package com.jrxrezkyy.webcloner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

object ShortcutHelper {

    private fun info(context: Context, site: Site): ShortcutInfoCompat {
        val icon = IconFactory.forSite(context, site, 192)
        val intent = Intent(context, ShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("webcloner://shortcut/${site.id}")
            putExtra(BrowserActivity.EXTRA_SITE_ID, site.id)
        }
        return ShortcutInfoCompat.Builder(context, "site_${site.id}")
            .setShortLabel(site.name)
            .setLongLabel(site.name)
            .setIcon(IconCompat.createWithBitmap(icon))
            .setIntent(intent)
            .build()
    }

    /** Meminta launcher menambahkan ikon akun ini ke layar utama. */
    fun pin(context: Context, site: Site) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val requested = ShortcutManagerCompat.requestPinShortcut(context, info(context, site), null)
        Toast.makeText(
            context,
            if (requested) R.string.shortcut_requested else R.string.shortcut_unsupported,
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Memperbarui pintasan yang SUDAH ada di layar utama (nama, ikon, dan tujuan). Pintasan lama
     * buatan versi sebelumnya langsung diarahkan ke perantara baru sehingga bug "tidak bisa buka
     * dua akun" ikut hilang tanpa harus memasang ulang. Sistem mengabaikan id yang belum dipasang.
     */
    fun refresh(context: Context, site: Site) {
        try {
            ShortcutManagerCompat.updateShortcuts(context, listOf(info(context, site)))
        } catch (e: Exception) {
            // Launcher tidak mendukung pembaruan; pintasan lama tetap berfungsi seperti sebelumnya.
        }
    }

    fun refreshAll(context: Context) {
        try {
            val list = SiteStore.all(context).map { info(context, it) }
            if (list.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, list)
        } catch (e: Exception) {
            // abaikan
        }
    }
}
