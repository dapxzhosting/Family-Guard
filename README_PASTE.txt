FAMILYGUARD - READY TO PASTE KE MyApplication

1. Tutup Android Studio.
2. Backup folder MyApplication\app terlebih dahulu.
3. Extract isi ZIP ini langsung ke folder MyApplication.
4. Pilih Replace jika Windows meminta penggantian file.
5. Buka MyApplication lagi di Android Studio.
6. Sync Project with Gradle Files.
7. Run app.

Catatan:
- google-services.json tidak disertakan karena harus berasal dari Firebase milikmu.
- Tanpa google-services.json, FCM remote belum aktif; aplikasi menampilkan "FCM: belum dikonfigurasi".
- Untuk FCM, buat Android app Firebase dengan package com.familyguard, lalu taruh google-services.json di folder app/.
