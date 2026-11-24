package com.sos.meshoffline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

/** Ecranul afișează harta, busola și controlează lanterna. Mesh-ul rulează în MeshForegroundService. */
class MainActivity : AppCompatActivity(), MeshBus.Listener, SensorEventListener {

    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var sosButton: Button
    private lateinit var flashButton: Button
    private lateinit var typeSpinner: Spinner
    private lateinit var arrow: ArrowView
    private lateinit var compassText: TextView
    private lateinit var profileText: TextView

    private var myMarker: Marker? = null
    private val alertMarkers = HashMap<Int, Marker>()

    // ----- Busolă -----
    private lateinit var sensorManager: SensorManager
    private var azimuth = 0f        // grade față de nordul adevărat
    private var hasAzimuth = false

    // ----- Lanternă Morse -----
    private val uiHandler = Handler(Looper.getMainLooper())
    private var torchId: String? = null
    private var flashOn = false
    private var morseIndex = 0
    private val morseSteps: List<Pair<Boolean, Long>> = buildList {
        val u = 250L
        for (letter in listOf(listOf(1, 1, 1), listOf(3, 3, 3), listOf(1, 1, 1))) { // S O S
            for (s in letter) { add(true to s * u); add(false to u) }
            add(false to 2 * u)
        }
        add(false to 4 * u)   // pauză între repetări
    }
    private val morseRunner = object : Runnable {
        override fun run() {
            if (!flashOn) return
            val (on, ms) = morseSteps[morseIndex]
            setTorch(on)
            morseIndex = (morseIndex + 1) % morseSteps.size
            uiHandler.postDelayed(this, ms)
        }
    }

    // Săgeată care arată direcția spre cea mai apropiată alarmă
    private class ArrowView(ctx: Context) : View(ctx) {
        var angle = 0f
        var active = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) * 0.9f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.GRAY
            c.drawCircle(cx, cy, r, paint)
            if (!active) return
            c.save(); c.rotate(angle, cx, cy)
            path.reset()
            path.moveTo(cx, cy - r * 0.85f)
            path.lineTo(cx + r * 0.35f, cy + r * 0.5f)
            path.lineTo(cx, cy + r * 0.25f)
            path.lineTo(cx - r * 0.35f, cy + r * 0.5f)
            path.close()
            paint.style = Paint.Style.FILL; paint.color = Color.parseColor("#E5393B")
            c.drawPath(path, paint)
            c.restore()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = Configuration.getInstance()
        cfg.userAgentValue = "com.sos.meshoffline/1.0 (SOS Mesh Offline; Android)"
        cfg.osmdroidBasePath = File(filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")

        Profile.load(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        findTorch()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupUI()
        checkPermissions()
        if (Profile.name.length < 3) showNameDialog(true)
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        profileText = TextView(this).apply {
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 14f
            setPadding(24, 20, 24, 0)
            gravity = Gravity.CENTER
            setOnClickListener { showNameDialog(false) }   // atingere = schimbă porecla
        }
        refreshProfile()

        statusText = TextView(this).apply {
            text = MeshBus.status
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 24, 24, 8)
            gravity = Gravity.CENTER
        }

        // Rând busolă: săgeată + text distanță/azimut
        arrow = ArrowView(this)
        compassText = TextView(this).apply {
            text = "Busolă: nicio alarmă primită"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 16, 0)
        }
        val compassRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            addView(arrow, LinearLayout.LayoutParams(160, 160))
            addView(compassText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }

        // Cheie CARTO gratuită (carto.com/basemaps/apikey). Goală = OpenStreetMap standard.
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
        } else TileSourceFactory.MAPNIK

        map = MapView(this).apply {
            setTileSource(tileSource)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(47.6567, 23.5850))
            overlays.add(CopyrightOverlay(this@MainActivity))
        }

        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, MeshBus.TYPES)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
        }

        sosButton = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setOnClickListener { toggleSos() }
        }
        flashButton = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setOnClickListener { toggleFlash() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sosButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(flashButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        refreshButtons()

        layout.addView(profileText)
        layout.addView(statusText)
        layout.addView(compassRow)
        // Butoane peste hartă: "EU" = centrează pe locația mea, "TOATE" = arată toate alarmele + pe mine
        fun mapButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC1F3A5F"))
            setPadding(24, 16, 24, 16)
            setOnClickListener { onClick() }
        }
        val fab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(mapButton("📍 EU") { centerOnMe() })
            addView(mapButton("⤢ TOATE") { fitAll() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
        }
        val mapBox = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(fab, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM
            ).apply { setMargins(0, 0, 20, 20) })
        }
        layout.addView(mapBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(typeSpinner)
        layout.addView(buttons)
        setContentView(layout)
    }

    private fun refreshButtons() {
        if (MeshBus.sosActive) {
            sosButton.text = "OPREȘTE SOS"
            sosButton.setBackgroundColor(Color.parseColor("#388E3C"))
        } else {
            sosButton.text = "TRIMITE ALARMA SOS"
            sosButton.setBackgroundColor(Color.parseColor("#D32F2F"))
        }
        if (torchId == null) {
            flashButton.text = "FĂRĂ LANTERNĂ"
            flashButton.isEnabled = false
            flashButton.setBackgroundColor(Color.DKGRAY)
        } else if (flashOn) {
            flashButton.text = "OPREȘTE\nLANTERNA"
            flashButton.setBackgroundColor(Color.parseColor("#F9A825"))
        } else {
            flashButton.text = "LANTERNĂ\nSOS"
            flashButton.setBackgroundColor(Color.parseColor("#455A64"))
        }
    }

    // ----- SOS -----
    private fun centerOnMe() {
        if (MeshBus.myLat == 0.0) {
            Toast.makeText(this, "Aștept fixarea GPS...", Toast.LENGTH_SHORT).show()
            return
        }
        map.controller.setZoom(18.0)
        map.controller.animateTo(GeoPoint(MeshBus.myLat, MeshBus.myLon))
    }

    private fun fitAll() {
        val pts = ArrayList<GeoPoint>()
        if (MeshBus.myLat != 0.0) pts.add(GeoPoint(MeshBus.myLat, MeshBus.myLon))
        MeshBus.alerts.values.forEach { pts.add(GeoPoint(it.lat, it.lon)) }
        if (pts.isEmpty()) {
            Toast.makeText(this, "Nu există poziții de afișat.", Toast.LENGTH_SHORT).show()
            return
        }
        val box = BoundingBox.fromGeoPoints(pts)
        if (pts.size == 1 || (box.latitudeSpan < 1e-5 && box.longitudeSpan < 1e-5)) {
            map.controller.setZoom(17.0)
            map.controller.animateTo(pts[0])
        } else {
            map.zoomToBoundingBox(box.increaseByScale(1.4f), true)
        }
    }

    private fun refreshProfile() {
        profileText.text = if (Profile.name.length >= 3) "Tu: ${Profile.label()}  (atinge ca să schimbi)" else "Setează-ți porecla"
    }

    private fun showNameDialog(force: Boolean) {
        val input = EditText(this).apply {
            hint = "Poreclă (3-8 litere/cifre)"
            setText(Profile.name)
            filters = arrayOf(InputFilter.LengthFilter(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(48, 32, 48, 32)
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Cine ești?")
            .setMessage("Porecla ta e vizibilă celorlalți. ID-ul tău unic: ${Profile.uid}\n(doar litere și cifre, fără diacritice)")
            .setView(input)
            .setCancelable(!force)
            .setPositiveButton("Salvează", null)
        if (!force) b.setNegativeButton("Anulează", null)
        val d = b.create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = Profile.sanitizeName(input.text.toString())
            if (n.length < 3) {
                input.error = "Minim 3 caractere (litere sau cifre, fără diacritice)"
            } else {
                Profile.setName(this, n)
                refreshProfile()
                d.dismiss()
            }
        }
    }

    private fun toggleSos() {
        if (Profile.name.length < 3) { showNameDialog(true); return }
        if (!MeshBus.sosActive && MeshBus.myLat == 0.0) {
            Toast.makeText(this, "Așteaptă fixarea semnalului GPS!", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, MeshForegroundService::class.java)
        if (MeshBus.sosActive) {
            i.action = MeshForegroundService.ACTION_SOS_STOP
            MeshBus.sosActive = false
        } else {
            i.action = MeshForegroundService.ACTION_SOS_START
            i.putExtra(MeshForegroundService.EXTRA_TYPE, typeSpinner.selectedItemPosition)
            MeshBus.sosActive = true
        }
        startService(i)
        refreshButtons()
    }

    // ----- Lanternă -----
    private fun findTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { torchId = null }
    }

    private fun setTorch(on: Boolean) {
        try {
            val id = torchId ?: return
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
        } catch (_: Exception) {}
    }

    private fun toggleFlash() {
        if (flashOn) stopFlash() else {
            flashOn = true
            morseIndex = 0
            uiHandler.post(morseRunner)
        }
        refreshButtons()
    }

    private fun stopFlash() {
        flashOn = false
        uiHandler.removeCallbacks(morseRunner)
        setTorch(false)
    }

    // ----- Permisiuni -----
    private fun neededPermissions(): List<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN)
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p
    }

    private fun checkPermissions() {
        val missing = neededPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        else startMeshService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val core = neededPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        if (core.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startMeshService()
        } else {
            statusText.text = "Permisiunile (locație + Bluetooth) sunt necesare pentru SOS."
        }
    }

    private fun startMeshService() {
        startLocalLocation()   // poziția apare pe hartă chiar dacă serviciul de fundal nu pornește
        try {
            ContextCompat.startForegroundService(this, Intent(this, MeshForegroundService::class.java))
        } catch (e: Exception) {
            statusText.text = "Serviciul de fundal nu a pornit: ${e.javaClass.simpleName}\nVerifică AndroidManifest.xml (declarația <service> și permisiunile FOREGROUND_SERVICE)."
        }
    }

    // ----- Locație proprie (plasă de siguranță, independentă de serviciu) -----
    private var locationManager: LocationManager? = null
    private val localListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            MeshBus.location(loc.latitude, loc.longitude)
            if (!MeshBus.sosActive) {
                MeshBus.status("Poziție: %.5f, %.5f".format(loc.latitude, loc.longitude))
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocalLocation() {
        if (locationManager != null) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            var any = false
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(prov)) {
                    lm.requestLocationUpdates(prov, 2000L, 1f, localListener)
                    any = true
                }
            }
            if (!any) MeshBus.status("Locația e oprită în telefon. Pornește GPS-ul!")
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { localListener.onLocationChanged(it) }
        } catch (e: Exception) {
            MeshBus.status("Eroare locație: ${e.javaClass.simpleName}")
        }
    }

    // ----- Busolă: calcul -----
    private fun updateCompass() {
        if (MeshBus.myLat == 0.0 || MeshBus.alerts.isEmpty()) {
            arrow.active = false; arrow.invalidate()
            compassText.text = "Busolă: nicio alarmă primită"
            return
        }
        var best: MeshBus.Alert? = null
        var bestDist = Float.MAX_VALUE
        val r = FloatArray(2)
        for (a in MeshBus.alerts.values) {
            Location.distanceBetween(MeshBus.myLat, MeshBus.myLon, a.lat, a.lon, r)
            if (r[0] < bestDist) { bestDist = r[0]; best = a }
        }
        val a = best ?: return
        Location.distanceBetween(MeshBus.myLat, MeshBus.myLon, a.lat, a.lon, r)
        val bearing = (r[1] + 360f) % 360f   // azimut către victimă, 0..360

        arrow.active = hasAzimuth
        arrow.angle = ((bearing - azimuth) + 360f) % 360f
        arrow.invalidate()

        val dist = if (bestDist >= 1000f) "%.2f km".format(bestDist / 1000f) else "${bestDist.toInt()} m"
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SV", "V", "NV")
        val dir = dirs[((bearing + 22.5f) / 45f).toInt() % 8]
        compassText.text = "${a.label} • SOS #${a.id}\n$dist • ${bearing.toInt()}° $dir" +
            if (hasAzimuth) "" else "\n(busola se calibrează...)"
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rot = FloatArray(9); val ori = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        var az = Math.toDegrees(ori[0].toDouble()).toFloat()
        if (MeshBus.myLat != 0.0) {   // corecție declinație magnetică → nord adevărat
            az += GeomagneticField(MeshBus.myLat.toFloat(), MeshBus.myLon.toFloat(), 0f, System.currentTimeMillis()).declination
        }
        az = (az + 360f) % 360f
        if (!hasAzimuth) { azimuth = az; hasAzimuth = true }
        else {   // netezire pe cerc
            var d = az - azimuth
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            azimuth = (azimuth + 0.15f * d + 360f) % 360f
        }
        updateCompass()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ----- MeshBus.Listener -----
    override fun onLocation(lat: Double, lon: Double) {
        val p = GeoPoint(lat, lon)
        if (myMarker == null) {
            myMarker = Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Locația mea"
            }
            map.overlays.add(myMarker)
            map.controller.animateTo(p)
        }
        myMarker?.position = p
        map.invalidate()
        updateCompass()
    }

    override fun onAlert(a: MeshBus.Alert, isNew: Boolean) {
        val p = GeoPoint(a.lat, a.lon)
        val m = alertMarkers.getOrPut(a.id) {
            Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
        m.position = p
        m.title = "${a.label} – SOS #${a.id} – ${MeshBus.TYPES.getOrElse(a.type) { "SOS" }} (salturi: ${a.hops})"
        if (isNew) map.controller.animateTo(p)
        map.invalidate()
        updateCompass()
    }

    override fun onAlertRemoved(id: Int) {
        alertMarkers.remove(id)?.let {
            map.overlays.remove(it)
            map.invalidate()
        }
        updateCompass()
    }

    override fun onStatus(text: String) { statusText.text = text }

    override fun onStart() {
        super.onStart()
        MeshBus.listener = this
        statusText.text = MeshBus.status
        refreshButtons()
        if (MeshBus.myLat != 0.0) onLocation(MeshBus.myLat, MeshBus.myLon)
        MeshBus.alerts.values.forEach { onAlert(it, false) }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run { compassText.text = "Telefonul nu are senzor de busolă." }
    }

    override fun onStop() {
        if (MeshBus.listener === this) MeshBus.listener = null
        sensorManager.unregisterListener(this)
        stopFlash()          // lanterna se oprește când ieși din aplicație
        refreshButtons()
        super.onStop()
    }

    override fun onDestroy() {
        try { locationManager?.removeUpdates(localListener) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
