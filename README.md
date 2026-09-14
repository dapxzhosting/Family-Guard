# FamilyGuard 👨‍👩‍👧: Aplikasi Kontrol Orang Tua (Parental Control)

FamilyGuard adalah aplikasi Android (Kotlin) untuk membantu orang tua memantau dan
mengatur penggunaan HP anak: penguncian aplikasi, laporan screen time, lokasi,
kunci layar jarak jauh, hingga live-view layar HP anak lewat WebRTC. Semua
fitur ini disinkronkan real-time via Firebase.

Aplikasi ini punya dua "peran" (role) yang login di family code yang sama:
- **Parent (Orang Tua)**: memantau & mengirim perintah dari `ParentDashboardActivity`.
- **Child (Anak)**: device yang dipantau, tampil di `ChildDashboardActivity` dan
  menjalankan service-service monitoring di background.

---

## ✨ Fitur

| Fitur | Keterangan |
|---|---|
| **Family Code** | Orang tua membuat "keluarga", menghasilkan kode unik. Anak join lewat kode ini (`FamilyCodeActivity`, `FamilyNameActivity`). |
| **App Lock** | Mengunci aplikasi tertentu di HP anak via `AppLockAccessibilityService` + `LockManager`. Orang tua memilih app dari `AppListActivity`. |
| **Screen Time Report** | Merekam waktu pemakaian aplikasi (`UsageTracker`) dan menampilkan laporan (`ScreenTimeReportActivity`, `ScreenTimeReportGenerator`). |
| **Lokasi HP Anak** | Melacak lokasi anak real-time di peta OSM (`LocationMapActivity`, `LocationHelper`), dikirim ke node `location` per device. |
| **Lock Screen Jarak Jauh** | Orang tua bisa mengunci layar HP anak dari jarak jauh (`LockScreenActivity`), termasuk dengan PIN. |
| **Live Screen View (WebRTC)** | Orang tua bisa melihat layar HP anak secara real-time (streaming, bukan polling gambar) via `ScreenCaptureService` + `stream-webrtc-android`, sinyal WebRTC (offer/answer/ICE candidate) lewat Realtime Database. |
| **Notification Blocker** | `NotificationBlockerService` (Notification Listener) untuk memfilter/memblokir notifikasi tertentu di HP anak. |
| **Perintah Jarak Jauh (Commands)** | Orang tua mengirim command (lock app, kirim pesan, kick anggota, dll) lewat node `commands`, dieksekusi oleh `GuardService` di HP anak. |
| **Pesan/Inbox Keluarga** | `MessageInboxActivity`, untuk kirim pesan singkat antar anggota keluarga. |
| **Device Admin** | `FamilyDeviceAdminReceiver` mencegah uninstall sepihak / memberi kontrol tambahan (mis. lock device). |
| **Boot & Watchdog** | `BootReceiver` menyalakan ulang service setelah reboot; `GuardWatchdogReceiver` menjaga service tetap hidup. |
| **Kick Member** | Orang tua dapat mengeluarkan anggota dari keluarga; tercatat di `kickLog`. |
| **Privacy Policy Screen** | Ada layar kebijakan privasi terpisah untuk orang tua (`PrivacyPolicyActivity`) dan anak (`activity_privacy_policy_child.xml`), penting untuk transparansi ke anak yang dipantau. |

---

## 🧱 Struktur Project

```
app/src/main/java/com/familyguard/
├── admin/       # Device Admin Receiver & LockManager
├── model/       # Data class (AppInfo, dll)
├── receiver/    # BootReceiver, GuardWatchdogReceiver, ScreenStateReceiver
├── service/     # GuardService, AppLockAccessibilityService, ScreenCaptureService,
│                # NotificationBlockerService, FamilyFirebaseMessagingService
├── sync/        # FamilyLink (semua akses Firebase Realtime Database), WebRtcIceConfig
├── ui/          # Semua Activity (Splash, Login, Dashboard, Settings, dst)
│   ├── adapter/ # RecyclerView adapters
│   └── widget/  # Custom view (AspectRatioFrameLayout)
└── utils/       # Helper (LocationHelper, UsageTracker, AppLockPrefs, dll)
```

**Tech stack:**
- Kotlin + AndroidX, ViewBinding
- Firebase: Auth, Realtime Database, Firestore, Cloud Messaging (FCM)
- Coroutines (`kotlinx-coroutines-android` & `-play-services`)
- OSMDroid (peta lokasi, gratis tanpa API key Google Maps)
- `stream-webrtc-android` untuk live screen streaming
- WorkManager untuk tugas berkala di background

---

## ⚙️ Setup Project

### 1. Prasyarat
- Android Studio (terbaru, mendukung AGP `9.1.1` & Kotlin `1.9.22`)
- JDK 17+
- Akun Firebase (Blaze/Spark plan, keduanya bisa untuk development)
- minSdk **26** (Android 8.0), targetSdk **34**, compileSdk **37**

### 2. Clone & buka project
```bash
git clone https://github.com/dapxzhosting/Family-Guard
cd Family-Guard
```
Buka dengan Android Studio, biarkan Gradle sync otomatis mengunduh dependency.

### 3. Setup Firebase
1. Buat project baru di [Firebase Console](https://console.firebase.google.com/).
2. Tambahkan aplikasi Android dengan **package name**: `com.familyguard`.
3. Download `google-services.json` dan letakkan di `app/google-services.json`
   (menggantikan file contoh yang ada). **Jangan commit file asli berisi kredensial produksi ke repo publik.**
4. Aktifkan service berikut di Firebase Console:
  - **Authentication** → aktifkan minimal 1 metode sign-in (Email/Password atau Google Sign-In, sesuai `LoginActivity`).
  - **Realtime Database** → buat database baru, lalu pasang rules (lihat bagian [Firebase Rules](#-firebase-realtime-database-rules) di bawah).
  - **Firestore** (jika dipakai untuk data tambahan di luar RTDB).
  - **Cloud Messaging (FCM)** → otomatis aktif dari `google-services.json`, dipakai `FamilyFirebaseMessagingService`.

### 4. Setup `local.properties`
File `local.properties` (tidak boleh di-commit ke git) perlu berisi path SDK Android
**dan** kredensial TURN server untuk WebRTC (dipakai saat kedua device di jaringan
NAT berbeda saat live screen view):

```properties
sdk.dir=/path/ke/Android/Sdk

turn.host=<host-turn-server-kamu>
turn.username=<username-turn>
turn.credential=<credential/password-turn>
```
> Nilai TURN ini dibaca lewat `BuildConfig.TURN_HOST`, `TURN_USERNAME`, `TURN_CREDENTIAL`
> (lihat `app/build.gradle.kts`). Kamu bisa memakai layanan gratis seperti
> [Metered TURN](https://www.metered.ca/tools/openrelay/) atau menjalankan `coturn`
> sendiri. Tanpa ini, fitur live screen view bisa gagal connect di jaringan tertentu.

### 5. Build & Run
```bash
./gradlew assembleDebug
```
atau langsung tekan **Run** di Android Studio. Install ke 2 device (atau 1 device +
1 emulator) untuk simulasi peran **Parent** dan **Child**.

### 6. Alur pemakaian pertama kali
1. Buka app → `SplashActivity` → `LoginActivity` (login/daftar).
2. Isi nama (`NameInputActivity`) → pilih peran di `RoleSelectionActivity`.
3. **Parent**: buat keluarga baru → dapat kode keluarga → bagikan kode ke anak.
4. **Child**: masukkan kode keluarga di `FamilyCodeActivity` → join.
5. Di HP anak, ikuti alur permission (`PermissionActivity`, `SetupActivity`): aktifkan
   Accessibility Service, Notification Listener, Device Admin, izin lokasi
   (termasuk *Allow all the time* untuk background location), overlay
   (`SYSTEM_ALERT_WINDOW`), dan matikan battery optimization untuk app ini.

---

## 🔥 Firebase Realtime Database Rules

Rules yang kamu berikan:

```json
{
  "rules": {
    "families": {
      "$familyCode": {
        ".read": "auth != null && data.child('members').child(auth.uid).exists()",
        ".write": "auth != null && (data.child('members').child(auth.uid).exists() || !data.exists())",

        "members": {
          "$uid": {
            ".write": "auth != null && (auth.uid === $uid || data.parent().child(auth.uid).exists())"
          }
        },

        "kickLog": {
          "$uid": {
            ".read": "auth != null && auth.uid === $uid"
          }
        }
      }
    },
    "users": {
      "$uid": {
        ".read": "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid"
      }
    }
  }
}
```

### Penjelasan per bagian

**`families/$familyCode` (level keluarga)**
- `.read`: hanya user yang sudah login **dan** UID-nya sudah tercatat sebagai
  `members/<uid>` di keluarga tersebut yang boleh membaca seluruh data keluarga itu
  (termasuk `devices`, `commands`, `location`, `screen_stream`, dll, karena di RTDB,
  rule di suatu path otomatis berlaku untuk semua node di bawahnya kecuali ada rule
  lebih spesifik yang menimpanya).
- `.write`: dua kondisi (OR):
  1. User sudah jadi member keluarga itu → boleh menulis apa saja di keluarga tsb (kirim command, update lokasi/device, dsb).
  2. **`!data.exists()`** → jika node `families/$familyCode` **belum ada sama sekali**,
     siapa pun yang login boleh menulis. Ini yang mengizinkan **pembuatan keluarga baru**
     (orang tua membuat family code baru pertama kali). Setelah node ini ada isinya,
     kondisi ini otomatis tidak berlaku lagi.

  ⚠️ **Catatan keamanan**: karena `!data.exists()` mengizinkan siapa saja yang login untuk
  membuat keluarga baru dengan kode apa pun yang dia tebak, pastikan **generate family
  code di sisi klien menggunakan string acak yang cukup panjang/sulit ditebak** (mis.
  6-8 karakter alfanumerik acak, bukan angka urut), supaya orang lain tidak bisa
  "merebut" family code yang belum dipakai atau menebak kode milik keluarga lain.

**`families/$familyCode/members/$uid`**
- `.write` lebih spesifik dari rule keluarga di atasnya, jadi ini yang menentukan
  siapa boleh menulis ke node member tertentu:
  - `auth.uid === $uid` → user boleh menulis **data dirinya sendiri** ke daftar member
    (dipakai saat *join* keluarga pertama kali; join = menulis `members/<uid_sendiri>`).
  - `data.parent().child(auth.uid).exists()` → atau, user yang **sudah** jadi member
    keluarga tsb boleh menulis/mengubah/menghapus entri member **siapa pun**. Ini
    yang dipakai saat orang tua melakukan **kick** anggota (`familyRef(code).child("members").child(targetUid).removeValue()`).

  Catatan: `.read` node `members` mengikuti rule level keluarga (semua member bisa
  saling melihat daftar anggota), tidak ada rule `.read` khusus di sini.

**`families/$familyCode/kickLog/$uid`**
- `.read`: hanya user dengan UID yang sama yang boleh membaca log kick-nya sendiri,
  jadi anak yang baru saja di-kick bisa tahu (mis. untuk menampilkan notifikasi "kamu
  dikeluarkan dari keluarga"), tapi tidak bisa melihat log kick anggota lain.
- Tidak ada `.write` eksplisit di sini → **mewarisi** rule `.write` dari level
  `families/$familyCode` (yaitu: member mana pun dari keluarga tsb boleh menulis
  kick log, sesuai kode `familyRef(code).child("kickLog").child(targetUid)`).

**`users/$uid`**
- `.read` & `.write`: hanya pemilik akun (`auth.uid === $uid`) yang boleh membaca/menulis
  profilnya sendiri (nama, `familyCode` yang sedang diikuti, dll). Tidak ada yang lain
  bisa mengintip/mengubah data user lain di sini.

### Ringkasan alur akses vs kode aplikasi
| Aksi di kode | Path RTDB | Diizinkan oleh rule |
|---|---|---|
| Buat keluarga baru | `families/$code` | `!data.exists()` |
| Join keluarga | `families/$code/members/$myUid` | `auth.uid === $uid` |
| Kick anggota | `families/$code/members/$targetUid` (removeValue) + `kickLog/$targetUid` | `data.parent().child(auth.uid).exists()` (yang kick sudah jadi member) |
| Update lokasi, device, kirim command, screen_stream | `families/$code/...` | Warisan `.write` level keluarga (harus sudah jadi member) |
| Baca kick log sendiri | `families/$code/kickLog/$myUid` | `auth.uid === $uid` |
| Baca/tulis profil sendiri | `users/$myUid` | `auth.uid === $uid` |

### Rekomendasi tambahan (opsional, belum ada di rules kamu)
- Pertimbangkan menambah `.validate` pada `members/$uid` supaya isinya minimal punya
  field `role` (`parent`/`child`) yang valid, mencegah data asal-asalan ditulis.
- Pertimbangkan membatasi ukuran `screen_stream/frame` (base64 JPEG) lewat `.validate`
  agar tidak ada yang mengirim payload raksasa dan membengkakkan biaya RTDB.
- Karena `families/$familyCode` bisa dibuat siapa saja saat belum ada, tambahkan
  validasi format `$familyCode` (mis. lewat regex panjang karakter) agar tidak ada
  yang membuat family code yang terlalu pendek/mudah ditebak.

---

## 🛠️ Troubleshooting

**1. Build gagal: `Could not resolve` / dependency Firebase**
- Pastikan koneksi internet stabil dan `google-services.json` sudah ada di `app/`.
- Jalankan `./gradlew clean build` untuk build ulang dari nol.

**2. App crash saat start dengan error terkait `google-services.json` / `FirebaseApp not initialized`**
- File `google-services.json` belum ada/salah tempat, atau `applicationId` di Firebase
  Console tidak cocok dengan `com.familyguard` di `build.gradle.kts`.

**3. Live Screen View (WebRTC) gagal connect / layar hitam terus**
- Cek `local.properties`: `turn.host`, `turn.username`, `turn.credential` harus benar.
  Tanpa TURN server yang valid, koneksi WebRTC hanya berhasil jika kedua device satu
  jaringan lokal (STUN saja tidak cukup untuk NAT ketat/simetris).
- Pastikan HP anak sudah memberi izin **Media Projection** (`ScreenCaptureRequestActivity`)
  dan tidak menutup/membunuh app secara manual (lihat poin battery optimization di bawah).

**4. Command dari orang tua tidak sampai / lokasi anak tidak update**
- Pastikan HP anak: Accessibility Service, Notification Listener, dan Device Admin
  sudah **aktif** (dicek lewat field `accessibilityEnabled`, `notifListenerActive`,
  `deviceAdminActive` di node device).
- HP anak sering mematikan service background karena **battery optimization**.
  minta user set app ini ke "Unrestricted"/"Tidak dibatasi" dan matikan optimasi baterai
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` sudah diminta di manifest, tapi user tetap
  harus approve manual di beberapa merk HP seperti Xiaomi/Oppo/Vivo yang punya battery
  manager tambahan sendiri).
- Cek `GuardWatchdogReceiver`/`BootReceiver`, pastikan tidak diblokir oleh "autostart
  permission" di HP custom ROM (Xiaomi MIUI, dsb).

**5. App Lock / Accessibility Service tidak mengunci app target**
- Buka Settings → Accessibility di HP anak, pastikan **FamilyGuard App Lock** statusnya ON.
  Beberapa Android versi baru (13+) otomatis mematikan accessibility service pihak
  ketiga yang di-*sideload* setelah beberapa waktu. Perlu diaktifkan ulang, atau app perlu
  di-install dari Play Store (bukan sideload APK) untuk menghindari pembatasan ini.

**6. Notifikasi tidak terblokir (`NotificationBlockerService`)**
- Perlu izin **Notification Access** yang diaktifkan manual dari Settings → Notification
  access. Ini permission khusus yang tidak bisa diminta lewat dialog runtime biasa.

**7. `PERMISSION_DENIED` saat baca/tulis Realtime Database**
- Biasanya karena: user belum login (`auth == null`), atau UID belum tercatat di
  `families/$code/members`. Cek urutan: user harus **join** (menulis `members/<uid>`)
  dulu sebelum bisa membaca/menulis node lain di keluarga tsb. Jangan panggil listener
  data keluarga sebelum proses join selesai.
- Kalau ingin membuat keluarga baru tapi selalu kena `PERMISSION_DENIED`, cek apakah
  family code yang digenerate ternyata **sudah dipakai** keluarga lain (`data.exists()`
  jadi `true`, sehingga kondisi `!data.exists()` gagal, dan user belum jadi member di
  situ juga). Pastikan proses generate kode mengecek keunikan dulu sebelum menulis.

**8. Lokasi tidak akurat / tidak update di background**
- Android 10+ membutuhkan izin **Allow all the time** (`ACCESS_BACKGROUND_LOCATION`),
  bukan cuma "Allow only while using the app". Arahkan user ke Settings App Info secara
  manual karena permission ini tidak selalu bisa di-*grant* langsung dari dialog runtime
  di semua versi Android.

**9. Gradle sync error terkait versi AGP/Kotlin**
- Sesuaikan versi Android Studio dengan AGP `9.1.1` (butuh Android Studio versi terbaru).
  Jika Android Studio kamu lebih lama, turunkan versi `agp` di
  `gradle/libs.versions.toml` sesuai versi Android Studio yang dipakai.

---

## ⚠️ Catatan Etika & Legal

Aplikasi seperti ini punya akses sangat sensitif (lokasi, layar, notifikasi, kunci
device). Pastikan:
- Digunakan sesuai hukum yang berlaku di wilayah masing-masing (biasanya hanya sah
  untuk memantau **anak di bawah umur yang menjadi tanggung jawab hukum orang tua/wali**,
  bukan untuk memantau orang dewasa tanpa persetujuan).
- Ada transparansi ke anak (sudah ada `PrivacyPolicyActivity` untuk anak). Pastikan ini
  benar-benar ditampilkan & dijelaskan, bukan dipasang diam-diam/tersembunyi).
- Data sensitif (lokasi, screen stream) tidak disimpan lebih lama dari yang diperlukan,
  dan diamankan sesuai rules di atas.