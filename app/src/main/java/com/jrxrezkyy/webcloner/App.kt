package com.jrxrezkyy.webcloner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(SiteStore.theme(this))
        installCrashReporter()
    }

    /**
     * Menyimpan penyebab crash ke berkas agar bisa ditampilkan saat aplikasi dibuka lagi.
     * Berguna karena tidak semua orang bisa membaca logcat dari HP.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText(
                    "Thread: ${thread.name}\n" + sw.toString().take(6000)
                )
            } catch (e: Exception) {
                // gagal menulis laporan: lanjut ke penanganan bawaan
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"

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
