package com.familyguard.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguard.databinding.ActivityScreenTimeReportBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.ui.adapter.UsageAppAdapter
import com.familyguard.utils.AppLockPrefs
import com.familyguard.utils.ScreenTimeReportGenerator
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Laporan screen time mingguan untuk HP anak tertentu -- dibuka dari
 * ParentDashboardActivity (EXTRA_DEVICE_ID + EXTRA_CHILD_NAME, pola sama
 * dengan AppListActivity). Datanya berasal dari UsageTracker (numpang di
 * polling AppLockAccessibilityService, lihat file itu) yang disinkron ke
 * families/{code}/devices/{deviceId}/usage/{date}/{package}=menit, diambil
 * lewat FamilyLink.fetchUsageHistory() lalu diolah oleh
 * ScreenTimeReportGenerator (total, trend %, top 5 aplikasi).
 *
 * Nama & ikon aplikasi diambil dari node appList yang sudah disinkron
 * duluan oleh fitur "Aplikasi HP Anak" (AppListActivity) -- tidak perlu
 * sinkronisasi terpisah khusus laporan ini.
 */
class ScreenTimeReportActivity : BaseActivity() {

    private lateinit var binding: ActivityScreenTimeReportBinding
    private val generator = ScreenTimeReportGenerator()
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenTimeReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        binding.tvReportChildName.text =
            intent.getStringExtra(EXTRA_CHILD_NAME)?.takeIf { it.isNotBlank() } ?: "HP Anak"

        binding.btnReportBack.setOnClickListener { onBackPressed() }

        if (deviceId == null) {
            binding.tvReportEmpty.visibility = View.VISIBLE
            return
        }

        loadAppNamesThenReport(deviceId)
    }

    /** Ambil dulu node appList (buat resolve nama & ikon aplikasi), baru
     *  ambil riwayat pemakaian -- supaya pas laporan dirender, nama
     *  aplikasinya sudah siap (bukan tampil package name mentah). */
    private fun loadAppNamesThenReport(deviceId: String) {
        val code = AppLockPrefs.getFamilyCode(this)
        if (code.isNullOrBlank()) {
            binding.tvReportEmpty.visibility = View.VISIBLE
            return
        }

        FirebaseDatabase.getInstance().reference
            .child("families").child(code).child("devices").child(deviceId).child("appList")
            .get()
            .addOnSuccessListener { snapshot ->
                val nameLookup = mutableMapOf<String, String>()
                val iconLookup = mutableMapOf<String, String>()
                for (appSnap in snapshot.children) {
                    val pkg = appSnap.child("packageName").getValue(String::class.java) ?: continue
                    appSnap.child("appName").getValue(String::class.java)?.let { nameLookup[pkg] = it }
                    appSnap.child("icon").getValue(String::class.java)?.takeIf { it.isNotEmpty() }
                        ?.let { iconLookup[pkg] = it }
                }
                loadReport(deviceId, nameLookup, iconLookup)
            }
            .addOnFailureListener {
                loadReport(deviceId, emptyMap(), emptyMap())
            }
    }

    private fun loadReport(deviceId: String, nameLookup: Map<String, String>, iconLookup: Map<String, String>) {
        FamilyLink.fetchUsageHistory(this, deviceId, DAYS_TOTAL) { allUsage ->
            runOnUiThread {
                renderReport(allUsage, nameLookup, iconLookup)
            }
        }
    }

    private fun renderReport(
        allUsage: List<ScreenTimeReportGenerator.DailyUsage>,
        nameLookup: Map<String, String>,
        iconLookup: Map<String, String>
    ) {
        val calendar = Calendar.getInstance()
        val currentWeekDates = (0 until 7).map {
            val d = sdf.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            d
        }.toSet()
        val previousWeekDates = (0 until 7).map {
            val d = sdf.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            d
        }.toSet()

        val currentWeekUsage = allUsage.filter { it.date in currentWeekDates }
        val previousWeekUsage = allUsage.filter { it.date in previousWeekDates }
        val previousWeekTotal = generator.calculateTotalMinutes(previousWeekUsage)

        val report = generator.generateWeeklyReport(currentWeekUsage, previousWeekTotal)

        val hours = report.totalMinutes / 60
        val minutes = report.totalMinutes % 60
        binding.tvReportTotal.text = "${hours} jam ${minutes} menit"
        binding.tvReportPeriod.text = "Periode: ${report.startDate} s/d ${report.endDate}"

        binding.tvReportTrend.text = when {
            previousWeekTotal == 0L -> "Belum ada data minggu lalu untuk dibandingkan"
            report.trendPercentage >= 0 -> "Dibanding minggu lalu: naik ${"%.0f".format(report.trendPercentage)}%"
            else -> "Dibanding minggu lalu: turun ${"%.0f".format(-report.trendPercentage)}%"
        }

        if (report.topApps.isEmpty()) {
            binding.tvReportEmpty.visibility = View.VISIBLE
            binding.rvTopApps.visibility = View.GONE
        } else {
            binding.tvReportEmpty.visibility = View.GONE
            binding.rvTopApps.visibility = View.VISIBLE
            binding.rvTopApps.layoutManager = LinearLayoutManager(this)
            binding.rvTopApps.adapter = UsageAppAdapter(report.topApps, nameLookup, iconLookup)
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_CHILD_NAME = "extra_child_name"
        private const val DAYS_TOTAL = 14 // 7 hari ini + 7 hari sebelumnya (buat trend)
    }
}
