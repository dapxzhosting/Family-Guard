package com.familyguard.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import com.familyguard.model.AppInfo
import java.io.ByteArrayOutputStream

object InstalledAppsHelper {

    /**
     * Convert icon aplikasi jadi Base64 PNG kecil (48x48) supaya bisa
     * disimpan & dikirim lewat Firebase Realtime Database sebagai String.
     * Di-resize kecil supaya ukuran data tetap ringan (icon asli app
     * biasanya 512x512+, terlalu besar buat RTDB kalau dikirim mentah).
     */
    fun iconToBase64(icon: Drawable, size: Int = 48): String {
        val bitmap = if (icon is BitmapDrawable && icon.bitmap != null) {
            Bitmap.createScaledBitmap(icon.bitmap, size, size, true)
        } else {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            bmp
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun base64ToDrawable(context: Context, base64: String): Drawable? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            BitmapDrawable(context.resources, bitmap)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ambil semua aplikasi yang terinstall (bukan system app utama).
     * Filter keluar: launcher, system core, app kita sendiri.
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val skipPackages = setOf(
            "com.familyguard",
            "android",
            "com.android.systemui",
            "com.android.settings"
        )

        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                // Hanya user-installed apps atau yang punya launcher icon
                val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                val hasLaunchIntent = pm.getLaunchIntentForPackage(app.packageName) != null
                (isUserApp || hasLaunchIntent) && !skipPackages.contains(app.packageName)
            }
            .mapNotNull { app ->
                try {
                    AppInfo(
                        packageName = app.packageName,
                        appName = pm.getApplicationLabel(app).toString(),
                        icon = pm.getApplicationIcon(app.packageName)
                    )
                } catch (e: Exception) { null }
            }
            .sortedBy { it.appName.lowercase() }
    }
}
