package com.familyguard.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

class LockManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent: ComponentName = FamilyDeviceAdminReceiver.getComponentName(context)

    val isAdminActive: Boolean
        get() = dpm.isAdminActive(adminComponent)

    fun lockScreen(): Result<Unit> {
        return try {
            if (!isAdminActive) {
                Result.failure(Exception("Device Admin belum aktif. Aktifkan terlebih dahulu."))
            } else {
                dpm.lockNow()

                Result.success(Unit)
            }
        } catch (e: Exception) {

            Result.failure(e)
        }
    }

    fun setPasswordMinLength(length: Int) {
        if (isAdminActive) {
            dpm.setPasswordMinimumLength(adminComponent, length)
        }
    }

    fun getActivationIntent(): android.content.Intent {
        return android.content.Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "FamilyGuard membutuhkan Device Admin untuk mengunci layar dari jarak jauh."
            )
        }
    }

    companion object {
    }
}

