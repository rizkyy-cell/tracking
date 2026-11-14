package com.jrxrezkyy.webcloner

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Perantara tanpa tampilan untuk pintasan layar utama. Launcher membuka ini di task-nya
 * sendiri (affinity terpisah, lihat manifest), lalu [TaskLauncher] memilih: tampilkan kartu akun
 * yang sudah ada atau buat kartu baru. Dengan begitu pintasan A dan B tidak saling menimpa.
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(BrowserActivity.EXTRA_SITE_ID)
            ?: intent?.data?.lastPathSegment
        val site = if (id != null) SiteStore.get(this, id) else null
        if (site != null) {
            TaskLauncher.open(this, site, intent?.data?.host == "shortcutapp")
        } else {
            Toast.makeText(this, R.string.account_missing, Toast.LENGTH_LONG).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
