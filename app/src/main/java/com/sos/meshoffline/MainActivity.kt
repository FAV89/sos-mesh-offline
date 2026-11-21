package com.sos.meshoffline

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.preference.PreferenceManager
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.nio.ByteBuffer
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var sosButton: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var locationManager: LocationManager? = null

    private var myLat = 0.0
    private var myLon = 0.0
    private var isSosActive = false
    private var mySosId: Short = Random.nextInt(1, 32000).toShort()
    private var myMarker: Marker? = null

    private val MANUFACTURER_ID = 0xFFFF
    private val seenPackets = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    // Re-emite semnalul SOS la fiecare 10 secunde cat timp alarma este activa
    private val sosLoopRunnable = object : Runnable {
        override fun run() {
            if (isSosActive && myLat != 0.0 && myLon != 0.0) {
                broadcastSosPacket(mySosId, 0, myLat.toFloat(), myLon.toFloat())
                handler.postDelayed(this, 10000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().load(this, prefs)
        Configuration.getInstance().userAgentValue = "SOSMeshOfflineApp/1.0"

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        statusText = TextView(this).apply {
            text = "Initializare GPS si Bluetooth Mesh..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(24, 32, 24, 24)
            gravity = Gravity.CENTER
        }

        val cartoTileSource = XYTileSource(
            "CartoVoyager",
            0, 19, 256, ".png",
            arrayOf(
                "https://cartocdn.com",
                "https://cartocdn.com",
                "https://cartocdn.com"
            ),
            "© OpenStreetMap contributors, © CARTO"
        )

        map = MapView(this).apply {
            setTileSource(cartoTileSource)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(47.6567, 23.5850))
        }

        sosButton = Button(this).apply {
            text = "TRIMITE ALARMA SOS"
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(20, 40, 20, 40)
            setOnClickListener { toggleSos() }
        }

        val mapParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        layout.addView(statusText)
        layout.addView(map, mapParams)
        layout.addView(sosButton)

        setContentView(layout)
    }

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            initHardware()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        initHardware()
    }

    @SuppressLint("MissingPermission")
    private fun initHardware() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val locListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                myLat = loc.latitude
                myLon = loc.longitude
                if (!isSosActive) {
                    updateStatus("Pozitie fixa: %.5f, %.5f\nScanare Mesh activa...".format(myLat, myLon))
                }
                val myPoint = GeoPoint(myLat, myLon)
                updateSelfMarker(myPoint)
            }
        }

        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, locListener)
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, locListener)
            }
            val lastLoc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastLoc != null) {
                locListener.onLocationChanged(lastLoc)
                map.controller.animateTo(GeoPoint(lastLoc.latitude, lastLoc.longitude))
            }
        } catch (e: Exception) {
            updateStatus("Eroare Locatie: Porneste GPS-ul din telefon!")
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            updateStatus("ATENTIE: Porneste Bluetooth-ul!")
            return
        }

        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        startMeshScanning()
    }

    private fun toggleSos() {
        if (myLat == 0.0 || myLon == 0.0) {
            Toast.makeText(this, "Asteapta fixarea semnalului GPS!", Toast.LENGTH_SHORT).show()
            return
        }

        isSosActive = !isSosActive
        if (isSosActive) {
            mySosId = Random.nextInt(1, 32000).toShort()
            sosButton.text = "OPRESTE ALARMA SOS"
            sosButton.setBackgroundColor(Color.parseColor("#388E3C"))
            handler.post(sosLoopRunnable)
            updateStatus("EMITERE SOS ACTIVA (#$mySosId)!\nSemnalul se propaga prin BT...")
        } else {
            sosButton.text = "TRIMITE ALARMA SOS"
            sosButton.setBackgroundColor(Color.parseColor("#D32F2F"))
            handler.removeCallbacks(sosLoopRunnable)
            stopAdvertising()
            updateStatus("Alarma oprita. Scanare Mesh activa...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun broadcastSosPacket(sosId: Short, hops: Byte, lat: Float, lon: Float) {
        stopAdvertising()

        val buffer = ByteBuffer.allocate(11)
        buffer.putShort(sosId)
        buffer.put(hops)
        buffer.putFloat(lat)
        buffer.putFloat(lon)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            ..setTxPowerLevel(3)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, buffer.array())
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    @SuppressLint("MissingPermission")
    private fun startMeshScanning() {
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rawData = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
                if (rawData.size != 11) return

                val buffer = ByteBuffer.wrap(rawData)
                val sosId = buffer.short
                val hops = buffer.get()
                val lat = buffer.float
                val lon = buffer.float

                if (sosId == mySosId) return

                val packetKey = "$sosId-$hops"
if (seenPackets.contains(packetKey)) return
seenPackets.add(packetKey)
runOnUiThread {
val victimPoint = GeoPoint(lat.toDouble(), lon.toDouble())
addAlertMarker(victimPoint, "SOS #$sosId (Sarituri BT: $hops)")
map.controller.animateTo(victimPoint)
updateStatus("ALARMA PRIMITA! ID: #$sosId | Sarituri BT: $hops\nCoord: $lat, $lon")
}
if (!isSosActive && hops < 10) {
val nextHop = (hops + 1).toByte()
broadcastSosPacket(sosId, nextHop, lat, lon)
handler.postDelayed({
if (!isSosActive) stopAdvertising()
}, 15000)
}
}
})
}
private fun updateSelfMarker(point: GeoPoint) {
if (myMarker == null) {
myMarker = Marker(map).apply {
setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
title = "Locatia mea"
}
map.overlays.add(myMarker)
}
myMarker?.position = point
map.invalidate()
}
private fun addAlertMarker(point: GeoPoint, title: String) {
val marker = Marker(map)
marker.position = point
marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
marker.title = title
map.overlays.add(marker)
map.invalidate()
}
private fun updateStatus(msg: String) {
runOnUiThread { statusText.text = msg }
}
}
