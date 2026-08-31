package com.familyguard.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityLocationMapBinding
import com.familyguard.sync.FamilyLink
import com.google.firebase.database.ValueEventListener
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationMapBinding
    private var locationListener: ValueEventListener? = null

    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private val sdf = SimpleDateFormat("HH:mm:ss, dd MMM yyyy", Locale("id", "ID"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, android.preference.PreferenceManager.getDefaultSharedPreferences(this))

        binding = ActivityLocationMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupButtons()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(16.0)

        binding.mapView.controller.setCenter(GeoPoint(-6.2088, 106.8456))
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnRecenter.setOnClickListener {
            val lat = lastLat
            val lng = lastLng
            if (lat != null && lng != null) {
                binding.mapView.controller.animateTo(GeoPoint(lat, lng))
                binding.mapView.controller.setZoom(16.0)
            } else {
                toast("Belum ada data lokasi anak")
            }
        }

        binding.btnOpenExternal.setOnClickListener {
            val lat = lastLat
            val lng = lastLng
            if (lat != null && lng != null) {
                val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Lokasi Anak)")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    toast("Tidak ada aplikasi peta yang terpasang")
                }
            } else {
                toast("Belum ada data lokasi anak")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        startObservingLocation()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        locationListener?.let { FamilyLink.removeLocationObserver(this, it) }
        locationListener = null
    }

    private fun startObservingLocation() {
        locationListener = FamilyLink.observeChildLocation(
            context = this,
            onUpdate = { lat, lng, timestamp ->
                lastLat = lat
                lastLng = lng
                updateMarker(lat, lng)
                binding.tvCoords.text = "Koordinat: %.6f, %.6f".format(lat, lng)
                binding.tvLastUpdate.text = if (timestamp > 0) {
                    "Update terakhir: ${sdf.format(Date(timestamp))}"
                } else {
                    "Update terakhir: baru saja"
                }
            },
            onNoData = {
                binding.tvLastUpdate.text = "Belum ada data lokasi dari HP anak"
                binding.tvCoords.text = "Koordinat: —"
            }
        )
    }

    private fun updateMarker(lat: Double, lng: Double) {
        val point = GeoPoint(lat, lng)

        binding.mapView.overlays.clear()
        val marker = Marker(binding.mapView)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = "Lokasi Anak"
        binding.mapView.overlays.add(marker)
        binding.mapView.invalidate()

        if (isFirstUpdate) {
            binding.mapView.controller.setCenter(point)
            isFirstUpdate = false
        }
    }

    private var isFirstUpdate = true

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        locationListener?.let { FamilyLink.removeLocationObserver(this, it) }
    }
}