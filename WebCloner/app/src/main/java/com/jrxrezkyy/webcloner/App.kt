package com.jrxrezkyy.webcloner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(SiteStore.theme(this))
    }

    companion object {
        /** 0 = ikuti sistem, 1 = terang, 2 = gelap. */
        fun applyTheme(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
