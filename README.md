# FamilyGuard

FamilyGuard adalah aplikasi kontrol orang tua (parental control) berbasis Android yang memungkinkan orang tua memantau dan mengelola penggunaan HP anak secara real-time — mulai dari melihat layar, mengunci aplikasi, membatasi waktu layar, hingga melacak lokasi — semuanya lewat satu dashboard.

Aplikasi ini terdiri dari satu basis kode yang berjalan dalam dua peran (role): **Orang Tua** dan **Anak**, dipasangkan lewat kode keluarga (family code) dan disinkronkan secara real-time menggunakan Firebase.

> ⚠️ **Catatan penggunaan yang bertanggung jawab**
> FamilyGuard dibuat untuk membantu orang tua mengawasi perangkat anak di bawah umur dengan sepengetahuan/persetujuan keluarga. Fitur seperti screen mirroring, remote control, dan pelacakan lokasi memerlukan izin sistem tingkat tinggi (Accessibility Service, Device Admin, MediaProjection). Jangan gunakan aplikasi ini untuk memantau perangkat orang lain tanpa izin — hal tersebut dapat melanggar hukum privasi di wilayah Anda.

---

## ✨ Fitur Utama

### 📺 Live Screen Mirroring & Remote Control
- Melihat layar HP anak secara real-time lewat koneksi peer-to-peer (WebRTC), langsung dari HP orang tua.
- **Mode Kontrol**: orang tua bisa mengetuk, swipe, dan mengirim tombol navigasi (Back / Home / Recents) serta Volume & Power ke HP anak dari jarak jauh, seolah-olah memegang HP-nya langsung.
- Tampilan mirroring dibungkus dalam bingkai visual ala "emulator" supaya mudah dibaca, lengkap dengan toggle sembunyikan overlay dan tombol putar tampilan untuk konten yang sedang landscape (misalnya saat anak bermain game).

### 🔒 App Lock
- Kunci aplikasi tertentu di HP anak (misal media sosial atau game) menggunakan Accessibility Service — begitu anak membuka app yang dikunci, layar PIN otomatis muncul.
- Kunci beberapa aplikasi sekaligus (bulk lock) langsung dari daftar aplikasi terpasang di HP anak.

### 🔐 Device Lock (Kunci Perangkat)
- Kunci seluruh HP anak dari jarak jauh menggunakan Device Admin API (`lockNow()`), berguna untuk situasi darurat seperti waktu tidur atau waktu belajar.

### 🔕 Notification Blocker
- Blokir notifikasi dari aplikasi tertentu di HP anak agar tidak mengganggu (misalnya saat jam sekolah), menggunakan `NotificationListenerService`.

### ⏱️ Laporan Waktu Layar (Screen Time Report)
- Lacak dan rekap pemakaian aplikasi per hari di HP anak, ditampilkan dalam laporan yang mudah dibaca oleh orang tua.

### 📍 Pelacakan Lokasi
- Lihat lokasi HP anak secara real-time di peta (menggunakan osmdroid/OpenStreetMap) dari dashboard orang tua.

### 💬 Pesan & Persetujuan (Messaging & Approval Requests)
- Kirim pesan langsung antara orang tua dan anak.
- Anak dapat mengajukan permintaan persetujuan (misalnya minta izin buka aplikasi tertentu), dan orang tua bisa menyetujui/menolak langsung dari dashboard.

### 👨‍👩‍👧‍👦 Multi-anak & Family Code
- Satu akun orang tua dapat terhubung dengan beberapa perangkat anak sekaligus.
- Pairing perangkat dilakukan lewat family code, tanpa perlu proses login yang rumit di sisi anak.

### 🛡️ Guard Service (Anti-uninstall/Anti-kill)
- Layanan latar belakang yang menjaga koneksi tetap aktif, otomatis nyala lagi setelah HP anak restart (`BootReceiver`) atau layar HP nyala/mati (`ScreenStateReceiver`), plus watchdog untuk memantau kondisi service.

---

## 🏗️ Arsitektur & Teknologi

| Komponen | Teknologi |
|---|---|
| Bahasa | Kotlin |
| Sinkronisasi real-time & pairing | Firebase Realtime Database |
| Notifikasi push | Firebase Cloud Messaging |
| Screen mirroring & remote control | WebRTC (`org.webrtc`) — video via `MediaProjection` + `TextureViewRenderer`, kontrol via `DataChannel`/Realtime Database |
| Kunci aplikasi & deteksi app aktif | `AccessibilityService` |
| Kunci perangkat | `DevicePolicyManager` (Device Admin) |
| Blokir notifikasi | `NotificationListenerService` |
| Peta & lokasi | osmdroid (OpenStreetMap) |
| Layanan latar belakang | `Service` + `BroadcastReceiver` (boot, screen state, watchdog) |

### Struktur Folder

```
src/main/java/com/familyguard/
├── admin/       # Device Admin & lock manager
├── model/       # Data model (AppInfo, dll.)
├── receiver/    # BroadcastReceiver (boot, screen state, watchdog)
├── service/     # Accessibility service, screen capture, notification blocker, guard service, FCM
├── sync/        # Sinkronisasi Firebase & WebRTC (FamilyLink, ICE config)
├── ui/          # Semua Activity & Adapter (dashboard, dll.)
└── utils/       # Helper (lokasi, tracking penggunaan, animasi, dll.)
```

---

## 🚀 Menjalankan Proyek

1. Clone repo ini.
2. Buat proyek Firebase baru, aktifkan **Realtime Database** dan **Cloud Messaging**, lalu unduh `google-services.json` dan letakkan di folder `app/`.
3. Buka proyek dengan Android Studio (terbaru disarankan).
4. Sync Gradle, lalu jalankan (`Run`) ke perangkat/emulator Android.
5. Pilih peran **Orang Tua** di satu perangkat, dan **Anak** di perangkat lain, lalu hubungkan keduanya menggunakan family code yang ditampilkan.

### Izin yang dibutuhkan di sisi HP anak
Beberapa fitur memerlukan izin manual yang harus diaktifkan pengguna lewat pengaturan sistem Android (tidak bisa diaktifkan otomatis oleh aplikasi karena kebijakan keamanan Android):
- Accessibility Service (untuk App Lock & deteksi aplikasi aktif)
- Device Admin (untuk Device Lock)
- Akses notifikasi (untuk Notification Blocker)
- Izin perekaman layar / MediaProjection (untuk Screen Mirroring, diminta ulang tiap sesi sesuai kebijakan Android)
- Izin lokasi (untuk Pelacakan Lokasi)

---

## 📄 Lisensi

Tambahkan lisensi pilihan Anda di sini (misalnya MIT License) sebelum repo dipublikasikan.
