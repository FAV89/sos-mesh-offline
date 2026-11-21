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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
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
    private var hardwareStarted = false

    private var myLat = 0.0
    private var myLon = 0.0
    private var isSosActive = false
    private var mySosId: Short = Random.nextInt(1, 32000).toShort()
    private var myMarker: Marker? = null

    private val MANUFACTURER_ID = 0xFFFF
    private val MAX_HOPS = 10
    private val handler = Handler(Looper.getMainLooper())

    // Pachetele pe care le retransmitem (SOS-urile altora). Cheia = sosId.
    private class Packet(val id: Short, var hops: Int, val lat: Float, val lon: Float)
    private val relayQueue = LinkedHashMap<Short, Packet>()
    private val alertMarkers = HashMap<Short, Marker>()
    private var rotateIndex = 0

    // Buclă unică de emisie: o dată la 3 secunde alege următorul pachet (al meu + cele relayate)
    private val broadcastLoop = object : Runnable {
        override fun run() {
            val toSend = ArrayList<Packet>()
            if (isSosActive && myLat != 0.0 && myLon != 0.0) {
                toSend.add(Packet(mySosId, 0, myLat.toFloat(), myLon.toFloat()))
            }
            toSend.addAll(relayQueue.values)
            if (toSend.isNotEmpty()) {
                rotateIndex = (rotateIndex + 1) % toSend.size
                val p = toSend[rotateIndex]
                advertise(p.id, p.hops, p.lat, p.lon)
            } else {
                stopAdvertising()
            }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // User-Agent descriptiv + cache în stocarea privată a aplicației
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = "com.sos.meshoffline/1.0 (SOS Mesh Offline; Android)"
        cfg.osmdroidBasePath = File(filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        statusText = TextView(this).apply {
            text = "Inițializare GPS și Bluetooth Mesh..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(24, 32, 24, 24)
            gravity = Gravity.CENTER
        }

        // Hartă: dacă ai cheie CARTO (gratuită, carto.com/basemaps/apikey) o pui aici.
        // Dacă rămâne goală, se folosesc tile-urile standard OpenStreetMap (nu necesită cheie).
        val cartoKey = "cb1_5dkf_1_57d40daf6b5b373875d498da"
        val tileSource = if (cartoKey.isNotBlank()) {
            object : XYTileSource(
                "CartoVoyagerKey", 0, 19, 256, ".png",
                arrayOf("https://basemaps.cartocdn.com/rastertiles/voyager/"),
                "© OpenStreetMap contributors, © CARTO"
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String =
                    super.getTileURLString(pMapTileIndex) + "?key=" + cartoKey
            }
        } else {
            TileSourceFactory.MAPNIK
        }

        map = MapView(this).apply {
            setTileSource(tileSource)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(47.6567, 23.5850))
            overlays.add(CopyrightOverlay(this@MainActivity)) // atribuirea hărții
        }

        sosButton = Button(this).apply {
            text = "TRIMITE ALARMA SOS"
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(20, 40, 20, 40)
            setOnClickListener { toggleSos() }
        }

        layout.addView(statusText)
        layout.addView(map, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(sosButton)
        setContentView(layout)
    }

    private fun neededPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return perms
    }

    private fun checkPermissions() {
        val missing = neededPermissions().filter {
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
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initHardware()
        } else {
            updateStatus("Permisiunile (locație + Bluetooth) sunt necesare pentru SOS.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun initHardware() {
        if (hardwareStarted) return
        hardwareStarted = true

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val locListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                myLat = loc.latitude
                myLon = loc.longitude
                if (!isSosActive) {
                    updateStatus("Poziție fixă: %.5f, %.5f\nScanare Mesh activă...".format(myLat, myLon))
                }
                updateSelfMarker(GeoPoint(myLat, myLon))
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
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
            updateStatus("Eroare locație: pornește GPS-ul din telefon!")
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            updateStatus("ATENȚIE: pornește Bluetooth-ul!")
            hardwareStarted = false
            return
        }

        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        startMeshScanning()
        handler.post(broadcastLoop)
    }

    private fun toggleSos() {
        if (myLat == 0.0 || myLon == 0.0) {
            Toast.makeText(this, "Așteaptă fixarea semnalului GPS!", Toast.LENGTH_SHORT).show()
            return
        }
        isSosActive = !isSosActive
        if (isSosActive) {
            mySosId = Random.nextInt(1, 32000).toShort()
            sosButton.text = "OPREȘTE ALARMA SOS"
            sosButton.setBackgroundColor(Color.parseColor("#388E3C"))
            updateStatus("EMITERE SOS ACTIVĂ (#$mySosId)!\nSemnalul se propagă prin Bluetooth...")
        } else {
            sosButton.text = "TRIMITE ALARMA SOS"
            sosButton.setBackgroundColor(Color.parseColor("#D32F2F"))
            updateStatus("Alarma oprită. Scanare Mesh activă...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun advertise(sosId: Short, hops: Int, lat: Float, lon: Float) {
        stopAdvertising()

        val buffer = ByteBuffer.allocate(11)
        buffer.putShort(sosId)
        buffer.put(hops.toByte())
        buffer.putFloat(lat)
        buffer.putFloat(lon)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, buffer.array())
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (_: Exception) {}
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

        // Filtru pe ID-ul nostru: Android permite astfel scanarea eficientă
        val filter = ScanFilter.Builder()
            .setManufacturerData(MANUFACTURER_ID, ByteArray(0))
            .build()

        bleScanner?.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val raw = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
                if (raw.size != 11) return

                val buf = ByteBuffer.wrap(raw)
                val sosId = buf.short
                val hops = buf.get().toInt()
                val lat = buf.float
                val lon = buf.float

                if (sosId == mySosId && isSosActive) return
                if (lat.isNaN() || lon.isNaN() || lat !in -90f..90f || lon !in -180f..180f) return

                // Cheia = sosId. Reținem doar cea mai scurtă cale; ignorăm duplicatele.
                val known = relayQueue[sosId]
                if (known != null && known.hops <= hops) return

                val nextHops = hops + 1
                if (nextHops <= MAX_HOPS) {
                    relayQueue[sosId] = Packet(sosId, nextHops, lat, lon)
                }
                runOnUiThread {
                    val point = GeoPoint(lat.toDouble(), lon.toDouble())
                    setAlertMarker(sosId, point, "SOS #$sosId (salturi BT: $hops)")
                    if (known == null) map.controller.animateTo(point)
                    updateStatus("ALARMĂ PRIMITĂ! ID: #$sosId | Salturi BT: $hops\nCoord: $lat, $lon")
                }
            }
        })
    }

    private fun updateSelfMarker(point: GeoPoint) {
        runOnUiThread {
            if (myMarker == null) {
                myMarker = Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Locația mea"
                }
                map.overlays.add(myMarker)
            }
            myMarker?.position = point
            map.invalidate()
        }
    }

    private fun setAlertMarker(id: Short, point: GeoPoint, title: String) {
        val marker = alertMarkers.getOrPut(id) {
            Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
        marker.position = point
        marker.title = title
        map.invalidate()
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
