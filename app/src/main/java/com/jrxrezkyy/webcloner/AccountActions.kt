package com.jrxrezkyy.webcloner

import android.widget.Toast

/** Menu aksi satu akun. Dipakai di daftar utama dan di Pengaturan > Akun. */
object AccountActions {

    fun show(act: GlassActivity, site: Site, onChanged: () -> Unit) {
        val d = GlassDialog(act, sheet = true)
            .title(site.name)
            .subtitle(site.url)
            .item(R.drawable.ic_open, R.string.open_account) { TaskLauncher.open(act, site) }
            .item(R.drawable.ic_edit, R.string.rename) { rename(act, site, onChanged) }
            .item(R.drawable.ic_image, R.string.change_icon) { act.pickIcon(site, onChanged) }
        if (SiteStore.hasCustomIcon(act, site.id)) {
            d.item(R.drawable.ic_refresh, R.string.reset_icon) {
                SiteStore.clearCustomIcon(act, site.id)
                ShortcutHelper.refresh(act, site)
                onChanged()
            }
        }
        d.item(R.drawable.ic_pin, R.string.add_shortcut) { ShortcutHelper.pin(act, site) }
            .item(R.drawable.ic_trash, R.string.clear_data, danger = true) { confirmClear(act, site) }
            .item(R.drawable.ic_trash, R.string.delete, danger = true) { confirmDelete(act, site, onChanged) }
            .show()
    }

    private fun rename(act: GlassActivity, site: Site, onChanged: () -> Unit) {
        val d = GlassDialog(act).title(R.string.rename)
        val field = d.input(site.name)
        d.button(R.string.cancel)
            .button(R.string.save, GlassDialog.Kind.ACCENT) {
                SiteStore.rename(act, site.id, field.text.toString())
                SiteStore.get(act, site.id)?.let { ShortcutHelper.refresh(act, it) }
                onChanged()
            }
            .show()
    }

    private fun confirmClear(act: GlassActivity, site: Site) {
        GlassDialog(act)
            .title(R.string.clear_data)
            .message(R.string.clear_confirm)
            .button(R.string.cancel)
            .button(R.string.clear_data_short, GlassDialog.Kind.DANGER) {
                Profiles.clearData(site)
                Toast.makeText(act, R.string.clear_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmDelete(act: GlassActivity, site: Site, onChanged: () -> Unit) {
        GlassDialog(act)
            .title(R.string.delete)
            .message(act.getString(R.string.delete_confirm, site.name))
            .button(R.string.cancel)
            .button(R.string.delete, GlassDialog.Kind.DANGER) {
                TaskLauncher.close(act, site)
                SiteStore.remove(act, site)
                onChanged()
            }
            .show()
    }
}
