package com.jrxrezkyy.webcloner

import android.content.Context

/**
 * Lokasi unduhan PER SITUS. Tiga mode:
 *  - tanya setiap kali: pemilih file sistem ("Simpan sebagai", bisa membuat folder baru),
 *  - folder tetap: dipilih sekali lewat pengelola file, semua unduhan situs ini masuk ke sana,
 *  - Download bawaan: folder Download publik HP.
 * Situs baru memakai "tanya setiap kali" dan langsung ditanya saat pertama kali ditambahkan.
 */
object DownloadLocation {

    fun label(c: Context, site: Site): String {
        return when (SiteStore.dlMode(c, site.id)) {
            SiteStore.DL_FOLDER -> {
                val tree = SiteStore.dlTree(c, site.id)
                if (tree != null) {
                    c.getString(R.string.dl_loc_folder_named, Downloads.treeLabel(c, tree))
                } else {
                    c.getString(R.string.dl_loc_ask)
                }
            }
            SiteStore.DL_DEFAULT -> c.getString(R.string.dl_loc_default)
            else -> c.getString(R.string.dl_loc_ask)
        }
    }

    /** [onDone] dipanggil tepat sekali, apa pun pilihan pengguna (termasuk menutup dialog). */
    fun choose(act: GlassActivity, site: Site, firstTime: Boolean, onDone: () -> Unit) {
        var handled = false
        fun finish() {
            if (!handled) {
                handled = true
                onDone()
            }
        }
        val mode = SiteStore.dlMode(act, site.id)
        val tree = SiteStore.dlTree(act, site.id)
        val folderLabel = if (mode == SiteStore.DL_FOLDER && tree != null) {
            act.getString(R.string.dl_loc_folder_named, Downloads.treeLabel(act, tree))
        } else {
            act.getString(R.string.dl_loc_folder)
        }
        val dialog = GlassDialog(act, sheet = true)
            .title(act.getString(if (firstTime) R.string.dl_loc_first_title else R.string.dl_location))
            .subtitle(site.name)
            .message(R.string.dl_loc_explain)
            .choice(act.getString(R.string.dl_loc_ask), mode == SiteStore.DL_ASK) {
                handled = true
                SiteStore.setDlMode(act, site.id, SiteStore.DL_ASK)
                onDone()
            }
            .choice(folderLabel, mode == SiteStore.DL_FOLDER) {
                handled = true
                act.pickFolder { uri ->
                    if (uri != null) {
                        SiteStore.setDlTree(act, site.id, uri)
                        SiteStore.setDlMode(act, site.id, SiteStore.DL_FOLDER)
                    }
                    onDone()
                }
            }
            .choice(act.getString(R.string.dl_loc_default), mode == SiteStore.DL_DEFAULT) {
                handled = true
                SiteStore.setDlMode(act, site.id, SiteStore.DL_DEFAULT)
                onDone()
            }
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }
}
