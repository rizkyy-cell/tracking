package com.jrxrezkyy.webcloner

import android.content.Context
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

object ShortcutHelper {

    /** Meminta launcher menambahkan ikon akun ini ke layar utama. */
    fun pin(context: Context, site: Site) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show()
            return
        }

        val icon = IconFactory.create(site.host, SiteStore.favicon(context, site.host), 192, site.badge)

        val info = ShortcutInfoCompat.Builder(context, "site_${site.id}")
            .setShortLabel(site.name)
            .setLongLabel(site.name)
            .setIcon(IconCompat.createWithBitmap(icon))
            .setIntent(BrowserActivity.createIntent(context, site))
            .build()

        val requested = ShortcutManagerCompat.requestPinShortcut(context, info, null)
        Toast.makeText(
            context,
            if (requested) R.string.shortcut_requested else R.string.shortcut_unsupported,
            Toast.LENGTH_LONG
        ).show()
    }
}
