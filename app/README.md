# 🛡️ FamilyGuard — Android Parental Control App

Aplikasi parental control untuk Android dengan 4 fitur utama:

## ✨ Fitur

| Fitur | Cara Kerja | Permission Dibutuhkan |
|-------|-----------|----------------------|
| **🔒 Lock HP** | Kunci layar langsung via Device Admin | Device Policy Manager |
| **📩 Kirim Pesan** | Notifikasi prioritas tinggi dari orang tua via FCM | POST_NOTIFICATIONS + Firebase |
| **🚫 Lock Aplikasi** | Detect pergantian app via Accessibility, tampilkan PIN screen | AccessibilityService |
| **🔕 Blokir Notifikasi** | Intercept & hapus notifikasi app tertentu | NotificationListenerService |

---

## 🚀 Setup Awal

### 1. Firebase Setup
1. Buat project di [Firebase Console](https://console.firebase.google.com)
2. Tambah Android app dengan package `com.familyguard`
3. Download `google-services.json` → letakkan di `app/`
4. Aktifkan **Firebase Cloud Messaging** dan **Firestore**

### 2. Buka di Android Studio
```
File → Open → pilih folder FamilyGuard
```

### 3. Sync & Build
```
Tools → Android → Sync Project with Gradle Files
```

### 4. Install ke HP
```
Run → Run 'app' (atau Shift+F10)
```

---

## 📱 Aktivasi Permission di HP

Setelah install, buka aplikasi dan aktifkan **3 izin utama**:

### A. Device Admin (untuk Lock HP)
- Tap tombol **"Aktifkan" di baris Device Admin**
- Sistem Android akan meminta konfirmasi → tap "Aktifkan"

### B. Accessibility Service (untuk Lock Aplikasi)
- Tap tombol **"Aktifkan" di baris Accessibility Service**
- Settings → Accessibility → Installed Services → **FamilyGuard** → ON
- Konfirmasi dengan tap "Allow"

### C. Notification Listener (untuk Blokir Notifikasi)
- Tap tombol **"Aktifkan" di baris Notification Listener**  
- Settings → Notifications → Notification Access → **FamilyGuard** → ON

---

## 🌐 Kirim Perintah Remote via FCM

### Lock HP dari Jarak Jauh
Kirim FCM message ke token device anak:
```json
{
  "to": "<FCM_TOKEN_HP_ANAK>",
  "data": {
    "command": "lock_screen"
  }
}
```

### Kirim Pesan ke HP Anak
```json
{
  "to": "<FCM_TOKEN_HP_ANAK>",
  "data": {
    "command": "send_message",
    "title": "Ibu",
    "message": "Nak, sudah makan siang?"
  }
}
```

### Lock Aplikasi Tertentu
```json
{
  "to": "<FCM_TOKEN_HP_ANAK>",
  "data": {
    "command": "lock_app",
    "package_name": "com.instagram.android",
    "action": "add"
  }
}
```

### Blokir Notifikasi Aplikasi
```json
{
  "to": "<FCM_TOKEN_HP_ANAK>",
  "data": {
    "command": "block_notif",
    "package_name": "com.whatsapp",
    "action": "add"
  }
}
```

> **Tip:** FCM token HP anak ditampilkan di header aplikasi. Salin token ini untuk dikirim dari aplikasi orang tua atau server.

---

## 📁 Struktur Project

```
FamilyGuard/
├── app/src/main/
│   ├── java/com/familyguard/
│   │   ├── admin/
│   │   │   ├── DeviceAdminReceiver.kt   ← Receiver untuk Device Admin
│   │   │   └── LockManager.kt          ← Logic kunci layar
│   │   ├── service/
│   │   │   ├── AppLockAccessibilityService.kt  ← Deteksi & kunci app
│   │   │   ├── NotificationBlockerService.kt   ← Blokir notifikasi
│   │   │   ├── FamilyFirebaseMessagingService.kt ← Terima perintah FCM
│   │   │   └── GuardService.kt         ← Foreground service (keep alive)
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt         ← Auto-start setelah reboot
│   │   ├── ui/
│   │   │   ├── MainActivity.kt         ← Dashboard utama
│   │   │   ├── LockScreenActivity.kt   ← PIN screen untuk buka app
│   │   │   └── adapter/AppListAdapter.kt
│   │   ├── model/AppInfo.kt
│   │   └── utils/
│   │       ├── AppLockPrefs.kt         ← SharedPreferences helper
│   │       └── InstalledAppsHelper.kt  ← Ambil daftar app terinstall
│   └── res/
│       ├── layout/                     ← XML layouts
│       └── xml/
│           ├── device_admin_policies.xml
│           └── accessibility_service_config.xml
```

---

## ⚠️ Catatan Penting

- **PIN default belum diset** — pertama kali buka lock screen, pengguna diminta buat PIN
- **FCM butuh internet** untuk perintah remote; fitur lokal (toggle app) tetap bekerja offline
- Beberapa ROM custom (MIUI, ColorOS) punya pembatasan tambahan untuk Accessibility Service
- Untuk production: tambahkan enkripsi PIN dan autentikasi parent dengan Firebase Auth

---

## 🔧 Development Lanjutan

Fitur yang bisa ditambahkan:
- [ ] Aplikasi parent terpisah untuk kontrol remote via UI
- [ ] Laporan aktivitas screen time
- [ ] Jadwal waktu pemakaian (screen time schedule)
- [ ] Filter konten web
- [ ] Location tracking
