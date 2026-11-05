# Web Cloner

Aplikasi Android: masukkan alamat situs (mis. `tiktok.com`), situs terbuka di dalam aplikasi
seperti aplikasi sendiri, login Anda tersimpan, dan bisa ditambahkan ke layar utama sebagai ikon.

## Fitur
- Input domain/URL -> otomatis `https://`; teks biasa dicari di Google
- Sesi login persisten (cookie & penyimpanan web), termasuk cookie pihak ketiga untuk SSO
- Banyak akun per situs: tiap akun punya login/cookie sendiri (profil WebView terpisah),
  jadi 5 akun TikTok bisa aktif bersamaan; ganti nama, hapus, dan beri ikon berlencana angka
- Daftar akun tersimpan + ikon (favicon) + pintasan layar utama
- Setiap situs tampil sebagai kartu terpisah di Recents
- Tarik untuk muat ulang (bisa dimatikan), mode desktop per situs
- Unggah file, unduhan, video layar penuh, kamera/mikrofon/lokasi (dengan konfirmasi)
- Rilis APK otomatis lewat GitHub Actions

## Cara rilis
1. Pastikan `.github/workflows/release.yml` ada di repo.
2. Buat tag: GitHub -> Releases -> Draft new release -> tag `v1.0.0` -> Publish,
   atau Actions -> "Build & Release" -> Run workflow -> isi tag.
3. Tunggu ±5 menit. APK `WebCloner-v1.0.0.apk` muncul di halaman Release.

## Tanda tangan stabil (disarankan)
Tanpa secret, tiap rilis memakai kunci sementara sehingga update di atas versi lama gagal
(harus uninstall). Untuk kunci tetap, buat keystore sekali (Termux: `pkg install openjdk-17`):

```
keytool -genkeypair -keystore release.jks -alias webcloner -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks
```

Lalu isi di Settings -> Secrets and variables -> Actions:
`KEYSTORE_BASE64` (hasil base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Simpan `release.jks` di tempat aman; jangan commit ke repo.

## Batasan
- Ini pembungkus WebView, bukan salinan aplikasi asli. Fitur native situs tidak ada.
- Login Google/Gmail sering diblokir di WebView (kebijakan Google); tidak ada solusi andal.
- Banyak akun terpisah butuh "Android System WebView" versi baru (dari Play Store). Jika tidak
  didukung, semua akun berbagi satu penyimpanan login dan aplikasi menampilkan peringatan.
- Unduhan `blob:`/`data:` (file yang dibuat di dalam halaman) disimpan ke folder Download lewat
  jembatan JavaScript; file yang sangat besar bisa lambat karena dikirim per potongan.
- Pastikan Anda mematuhi syarat layanan situs yang dibuka.
