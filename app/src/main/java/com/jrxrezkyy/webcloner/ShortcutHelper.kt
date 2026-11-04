package com.jrxrezkyy.webcloner

import android.content.Context
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

object ShortcutHelper {

    /** Meminta launcher menambahkan ikon situs ke layar utama. */
    fun pin(context: Context, url: String, title: String) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show()
            return
        }

        val host = UrlUtils.host(url)
        val label = title.ifBlank { host }
        val icon = IconFactory.create(host, SiteStore.favicon(context, host))

        val info = ShortcutInfoCompat.Builder(context, "site_$host")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithBitmap(icon))
            .setIntent(BrowserActivity.createIntent(context, url))
            .build()

        val requested = ShortcutManagerCompat.requestPinShortcut(context, info, null)
        Toast.makeText(
            context,
            if (requested) R.string.shortcut_requested else R.string.shortcut_unsupported,
            Toast.LENGTH_LONG
        ).show()
    }
}
