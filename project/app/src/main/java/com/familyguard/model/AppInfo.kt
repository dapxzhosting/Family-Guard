package com.familyguard.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    var isLocked: Boolean = false,
    var isNotifBlocked: Boolean = false,
    val iconBase64: String? = null
)