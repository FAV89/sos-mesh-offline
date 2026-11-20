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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
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

    // ID-ul unic al aplicatiei noastre pentru pachetele Bluetooth (0xFFFF = test/custom)
    private val MANUFACTURER_ID = 0xFFFF
    private val seenPackets = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configurare OpenStreetMap (fara Google)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = "SOSMeshOffline/1.0 (contact: emailul-tau@example.com)"

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

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            // Centrare initiala aproximativa (Romania)
            controller.setCenter(GeoPoint(45.9432, 24.9668))
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
        // 1. Pornire GPS hardware direct (fara Google Play Services)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    myLat = loc.latitude
                    myLon = loc.longitude
                    updateStatus("GPS Activ: %.5f, %.5f\nScanare Mesh activa...".format(myLat, myLon))
                    val myPoint = GeoPoint(myLat, myLon)
                    map.controller.animateTo(myPoint)
                    addMarker(myPoint, "Locatia mea", true)
                }
            })
        } catch (e: Exception) {
            updateStatus("Eroare GPS: Porneste locatia din telefon!")
        }

        // 2. Pornire Bluetooth Low Energy (Mesh)
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            updateStatus("ATENTIE: Porneste Bluetooth-ul!")
            return
        }

        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        startMeshScanning()
        updateStatus("Sistem pregatit (Offline Mesh & GPS)")
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
            // Trimite pachetul: ID(2 bytes) + Hops(1 byte) + Lat(4 bytes) + Lon(4 bytes) = 11 bytes
            broadcastSosPacket(mySosId, 0, myLat.toFloat(), myLon.toFloat())
            updateStatus("EMITERE SOS ACTIVA! Semnalul se propaga prin BT...")
        } else {
            sosButton.text = "TRIMITE ALARMA SOS"
            sosButton.setBackgroundColor(Color.parseColor("#D32F2F"))
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
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
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

                // Ignoram propriul semnal
                if (sosId == mySosId) return

                val packetKey = "$sosId-$hops"
                if (seenPackets.contains(packetKey)) return
                seenPackets.add(packetKey)

                // Afisam pe harta OpenStreetMap persoana care cere ajutor
                runOnUiThread {
                    val victimPoint = GeoPoint(lat.toDouble(), lon.toDouble())
                    addMarker(victimPoint, "SOS #$sosId (Sarituri BT: $hops)", false)
                    map.controller.animateTo(victimPoint)
                    updateStatus("ALARMA PRIMITA! ID: #$sosId | Sarituri BT: $hops\nCoord: $lat, $lon")
                }

                // RETRANSMISIE AUTOMATA (MESH HOP) daca nu suntem noi cei cu SOS activ si hops < 10
                if (!isSosActive && hops < 10) {
                    val nextHop = (hops + 1).toByte()
                    broadcastSosPacket(sosId, nextHop, lat, lon)
                    // Retransmite timp de 15 secunde apoi revine la ascultare
                    handler.postDelayed({
                        if (!isSosActive) stopAdvertising()
                    }, 15000)
                }
            }
        })
    }
private fun addMarker(point: GeoPoint, title: String, isSelf: Boolean) {
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
