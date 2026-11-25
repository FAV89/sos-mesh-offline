package com.sos.meshoffline

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import java.io.File

/** Ecranul: hartă, busolă, lanternă, profil, admin. Mesh-ul și cloud-ul rulează în MeshForegroundService. */
class MainActivity : AppCompatActivity(), MeshBus.Listener, SensorEventListener {

    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var sosButton: Button
    private lateinit var flashButton: Button
    private lateinit var typeSpinner: Spinner
    private lateinit var arrow: ArrowView
    private lateinit var compassText: TextView
    private lateinit var profileText: TextView
    private lateinit var euButton: Button
    private var followMe = false          // „EU" apăsat: harta rămâne centrată pe mine
    private var adminCode: String? = null  // cod admin verificat în această sesiune (doar în memorie)

    private val uiHandler = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ----- Busolă -----
    private lateinit var sensorManager: SensorManager
    private var azimuth = 0f
    private var hasAzimuth = false

    // ----- Lanternă Morse -----
    private var torchId: String? = null
    private var flashOn = false
    private var morseIndex = 0
    private val morseSteps: List<Pair<Boolean, Long>> = buildList {
        val u = 250L
        for (letter in listOf(listOf(1, 1, 1), listOf(3, 3, 3), listOf(1, 1, 1))) { // S O S
            for (s in letter) { add(true to s * u); add(false to u) }
            add(false to 2 * u)
        }
        add(false to 4 * u)
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

    // ----- Animație (unde de șoc) -----
    private var animating = false
    private val animRunner = object : Runnable {
        override fun run() {
            if (needAnim()) {
                map.invalidate()
                uiHandler.postDelayed(this, 66)
            } else animating = false
        }
    }
    private fun needAnim() = MeshBus.sosActive || MeshBus.alerts.isNotEmpty()
    private fun ensureAnim() {
        if (!animating && needAnim()) { animating = true; uiHandler.post(animRunner) }
    }

    private class Hit(val x: Float, val y: Float, val alert: MeshBus.Alert?, val person: MeshBus.Person?)

    // Săgeata busolei
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

    /**
     * Desenează tot pe hartă: eu (albastru, roșu când emit SOS), alți utilizatori (albastru = aplicație,
     * turcoaz = web), alarme (roșu cu unde de șoc), toate cu porecla deasupra.
     */
    private inner class PeopleOverlay : Overlay() {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f) }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); textAlign = Paint.Align.CENTER; color = Color.WHITE; isFakeBoldText = true }
        private val txtStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(13f); textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE
            strokeWidth = dp(3.5f); color = Color.argb(220, 0, 0, 0); isFakeBoldText = true
        }
        private val hits = ArrayList<Hit>()

        private val RED = Color.parseColor("#E5393B")
        private val BLUE = Color.parseColor("#2F7DE1")
        private val CYAN = Color.parseColor("#00ACC1")

        private val sym = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        private fun point(c: Canvas, x: Float, y: Float, color: Int, label: String, pulse: Boolean, now: Long, big: Boolean, symbol: String = "") {
            val r = dp(if (big) 9f else 7f)
            if (pulse) {
                for (i in 0..2) {   // 3 unde de șoc care se extind și dispar, repetate continuu
                    val t = ((now % 2400L) / 2400f + i / 3f) % 1f
                    ring.color = color
                    ring.alpha = ((1f - t) * 180).toInt()
                    c.drawCircle(x, y, r + t * dp(70f), ring)
                }
            }
            dot.color = Color.WHITE; c.drawCircle(x, y, r + dp(2f), dot)
            dot.color = color; c.drawCircle(x, y, r, dot)
            if (symbol.isNotEmpty()) {            // simbolul tipului de urgență, în interiorul punctului
                sym.textSize = r * 1.3f
                sym.color = if (color == MeshBus.TYPE_COLORS[3]) Color.BLACK else Color.WHITE
                c.drawText(symbol, x, y + sym.textSize * 0.35f, sym)
            }
            if (label.isNotEmpty()) {
                val ty = y - r - dp(8f)
                c.drawText(label, x, ty, txtStroke)
                c.drawText(label, x, ty, txt)
            }
        }

        override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val proj = mapView.projection
            val now = System.currentTimeMillis()
            hits.clear()

            val alertUids = HashSet<String>()           // cine are alarmă activă apare DOAR ca punct roșu, nu și ca punct albastru
            for (a in MeshBus.alerts.values) if (a.age(now) < MeshBus.MAX_AGE_MIN && a.uid.isNotEmpty()) alertUids.add(a.uid)

            for (p in MeshBus.people.values) {          // alți utilizatori (activi în ultimele 10 min)
                if (p.uid == Profile.uid || p.uid in alertUids || now / 1000 - p.tsSec > 600) continue
                val pt = proj.toPixels(GeoPoint(p.lat, p.lon), null)
                point(c, pt.x.toFloat(), pt.y.toFloat(), if (p.src == "web") CYAN else BLUE, p.name, false, now, false)
                hits.add(Hit(pt.x.toFloat(), pt.y.toFloat(), null, p))
            }
            for (a in MeshBus.alerts.values) {           // alarme
                if (a.age(now) >= MeshBus.MAX_AGE_MIN) continue
                val pt = proj.toPixels(GeoPoint(a.lat, a.lon), null)
                val ti = a.type.coerceIn(0, MeshBus.TYPE_COLORS.size - 1)
                point(c, pt.x.toFloat(), pt.y.toFloat(), MeshBus.TYPE_COLORS[ti], a.label, true, now, true, MeshBus.TYPE_SYMBOLS[ti])
                hits.add(Hit(pt.x.toFloat(), pt.y.toFloat(), a, null))
            }
            if (MeshBus.myLat != 0.0) {                   // eu
                val pt = proj.toPixels(GeoPoint(MeshBus.myLat, MeshBus.myLon), null)
                val sos = MeshBus.sosActive
                val mi = MeshBus.mySosType.coerceIn(0, MeshBus.TYPE_COLORS.size - 1)
                point(c, pt.x.toFloat(), pt.y.toFloat(), if (sos) MeshBus.TYPE_COLORS[mi] else BLUE,
                    if (Profile.name.isNotEmpty()) "Tu: ${Profile.name}" else "Tu", sos, now, true,
                    if (sos) MeshBus.TYPE_SYMBOLS[mi] else "")
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            var best: Hit? = null
            var bestD = dp(44f)
            for (h in hits) {
                val d = Math.hypot((h.x - e.x).toDouble(), (h.y - e.y).toDouble()).toFloat()
                if (d < bestD) { bestD = d; best = h }
            }
            val h = best ?: return false
            if (h.alert != null) showAlertDialog(h.alert)
            else h.person?.let {
                Toast.makeText(this@MainActivity, "${it.name}#${it.uid} (${if (it.src == "web") "web" else "aplicație"})", Toast.LENGTH_SHORT).show()
            }
            return true
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

    private fun roundBg(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp)
    }

    private fun mapButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        background = roundBg(Color.parseColor("#D91F3A5F"), 12f)
        setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        profileText = TextView(this).apply {
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 12f
            setPadding(24, 10, 24, 0)
            gravity = Gravity.CENTER
            setOnClickListener { showSettingsMenu() }
        }

        statusText = TextView(this).apply {
            text = MeshBus.status
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 3
            setPadding(24, 2, 24, 8)
            gravity = Gravity.CENTER
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
            overlays.add(PeopleOverlay())
        }

        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        val mapBox = FrameLayout(this)
        mapBox.addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // --- Busolă: mică, sus-stânga, peste hartă ---
        arrow = ArrowView(this)
        compassText = TextView(this).apply {
            text = "Busolă: nicio alarmă activă"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8f).toInt(), 0, 0, 0)
        }
        val compassBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(Color.argb(150, 0, 0, 0), 12f)
            setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
            addView(arrow, LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()))
            addView(compassText)
        }
        mapBox.addView(compassBox, FrameLayout.LayoutParams(wrap, wrap, Gravity.START or Gravity.TOP)
            .apply { setMargins(dp(8f).toInt(), dp(8f).toInt(), 0, 0) })

        // --- Dreapta: ⚙ Meniu, EU, TOATE, HARTĂ ---
        euButton = mapButton("📍 EU") { toggleFollow() }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gap = { LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(8f).toInt() } }
            addView(mapButton("⚙ MENIU") { showSettingsMenu() })
            addView(euButton, gap())
            addView(mapButton("⤢ TOATE") { followMe = false; updateFollowUi(); fitAll() }, gap())
            addView(mapButton("⬇ HARTĂ") { downloadMap() }, gap())
        }
        mapBox.addView(rightCol, FrameLayout.LayoutParams(wrap, wrap, Gravity.END or Gravity.TOP)
            .apply { setMargins(0, dp(8f).toInt(), dp(8f).toInt(), 0) })

        // --- Stânga jos: tip urgență (mic) + SOS rotunjit + lanternă ---
        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, MeshBus.TYPES)
            background = roundBg(Color.parseColor("#EEFFFFFF"), 10f)
        }
        sosButton = Button(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setOnClickListener { toggleSos() }
        }
        flashButton = Button(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setOnClickListener { toggleFlash() }
        }
        val sosRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sosButton, LinearLayout.LayoutParams(dp(96f).toInt(), dp(54f).toInt()))
            addView(flashButton, LinearLayout.LayoutParams(dp(54f).toInt(), dp(54f).toInt()).apply { leftMargin = dp(8f).toInt() })
        }
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(typeSpinner, LinearLayout.LayoutParams(dp(190f).toInt(), dp(38f).toInt()).apply { bottomMargin = dp(8f).toInt() })
            addView(sosRow)
        }
        mapBox.addView(leftCol, FrameLayout.LayoutParams(wrap, wrap, Gravity.START or Gravity.BOTTOM)
            .apply { setMargins(dp(10f).toInt(), 0, 0, dp(22f).toInt()) })

        layout.addView(profileText)
        layout.addView(statusText)
        layout.addView(mapBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        refreshProfile()
        refreshButtons()
        updateFollowUi()
    }

    // ---------- EU: urmărire (harta rămâne centrată pe mine) ----------
    private fun toggleFollow() {
        if (MeshBus.myLat == 0.0) { Toast.makeText(this, "Aștept fixarea GPS...", Toast.LENGTH_SHORT).show(); return }
        followMe = !followMe
        updateFollowUi()
        if (followMe) {
            if (map.zoomLevelDouble < 16.5) map.controller.setZoom(18.0)
            map.controller.animateTo(GeoPoint(MeshBus.myLat, MeshBus.myLon))
        }
    }

    private fun updateFollowUi() {
        euButton.text = if (followMe) "📍 EU ✓" else "📍 EU"
        euButton.background = roundBg(Color.parseColor(if (followMe) "#2E9E5B" else "#D91F3A5F"), 12f)
    }

    // ---------- Profil ----------
    private fun refreshProfile() {
        profileText.text = if (Profile.name.length >= 3)
            "Tu: ${Profile.label()}  •  " + (if (Profile.cloudReady) "grup ${Profile.groupName}" else "fără grup (doar Bluetooth)") + "  •  atinge pentru meniu"
        else "Setează-ți porecla (atinge)"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun askText(title: String, hintText: String, initial: String, maxLen: Int, positive: String,
                        secret: Boolean = false, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hintText
            setText(initial)
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(16f).toInt())
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(positive) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Anulează", null).show()
    }

    /** Poreclă / nume (un singur câmp, 3-8 litere sau cifre). */
    private fun showNameDialog(force: Boolean) {
        val input = EditText(this).apply {
            hint = "Poreclă (3-8 litere/cifre, fără diacritice)"
            setText(Profile.name)
            filters = arrayOf(InputFilter.LengthFilter(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(16f).toInt())
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Cum te cheamă?")
            .setMessage("ID-ul tău unic: ${Profile.uid}")
            .setView(input)
            .setCancelable(!force)
            .setPositiveButton("Salvează", null)
        if (!force) b.setNegativeButton("Anulează", null)
        val d = b.create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = Profile.sanitizeName(input.text.toString())
            if (n.length < 3) input.error = "Minim 3 caractere (litere sau cifre)"
            else { Profile.setName(this, n); refreshProfile(); d.dismiss() }
        }
    }

    // ---------- Meniu setări ----------
    private fun showSettingsMenu() {
        val items = arrayOf(
            "Schimbă porecla (${Profile.name})",
            "Grupuri",
            "Vizibil pe hartă în ${if (Profile.isGlobal) "GLOBAL" else "acest grup"}: ${if (Profile.sharePresence) "DA" else "NU"}",
            if (adminCode == null) "Admin" else "Admin (activ)"
        )
        AlertDialog.Builder(this).setTitle("Meniu").setItems(items) { _, which ->
            when (which) {
                0 -> showNameDialog(false)
                1 -> showGroupsMenu()
                2 -> {
                    val v = !Profile.sharePresence
                    if (!v) CloudGateway.removePresence(this)
                    Profile.setSharePresence(this, v)
                    toast(if (v) "Ceilalți din grup te văd pe hartă." else "Nu mai ești vizibil pe hartă. Alarmele tale rămân vizibile.")
                }
                3 -> showAdminMenu()
            }
        }.show()
    }

    // ---------- Grupuri ----------
    private fun switchGroup(gid: String, name: String) {
        CloudGateway.removePresence(this)       // mai întâi ies din grupul vechi
        Profile.setGroup(this, gid, name)
        MeshBus.dropCloud()
        refreshProfile()
    }

    private fun copyText(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Dialog cu două câmpuri: nume de grup + parolă. */
    private fun askNamePassword(title: String, positive: String, message: String?, onOk: (String, String) -> Unit) {
        val pad = dp(20f).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val nameIn = EditText(this).apply {
            hint = "Nume grup (min 2 litere/cifre)"
            filters = arrayOf(InputFilter.LengthFilter(20))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val passIn = EditText(this).apply {
            hint = "Parolă grup (min 6 caractere)"
            filters = arrayOf(InputFilter.LengthFilter(30))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(nameIn); box.addView(passIn)
        val b = AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(positive, null).setNegativeButton("Anulează", null)
        if (message != null) b.setMessage(message)
        val d = b.create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = Profile.sanitizeGroupName(nameIn.text.toString())
            val p = passIn.text.toString()
            when {
                n.length < 2 -> nameIn.error = "Minim 2 caractere (litere/cifre)"
                p.length < 6 -> passIn.error = "Parola are minim 6 caractere"
                else -> { d.dismiss(); onOk(n, p) }
            }
        }
    }

    private fun showGroupsMenu() {
        val cur = if (Profile.cloudReady) "Grup curent: ${Profile.groupName}" else "Fără grup (doar Bluetooth)"
        val items = arrayOf(cur, "Comunitatea GLOBAL (toți utilizatorii)", "Intră într-un grup privat (nume + parolă)",
            "Creează grup privat (max ${CloudGateway.MAX_OWNED_GROUPS})", "Șterge un grup creat de mine", "Ieși din grupul curent")
        AlertDialog.Builder(this).setTitle("Grupuri").setItems(items) { _, which ->
            when (which) {
                1 -> { switchGroup(Profile.GLOBAL, Profile.GLOBAL); toast("Ești în comunitatea GLOBAL. Aici se văd alarmele tuturor.") }
                2 -> joinGroupDialog()
                3 -> createGroupDialog()
                4 -> deleteOwnGroupDialog()
                5 -> { switchGroup("", ""); toast("Ai ieșit din grup. Rămâne doar Bluetooth.") }
            }
        }.show()
    }

    private fun joinGroupDialog() {
        askNamePassword("Intră într-un grup privat", "Intră", null) { name, pass ->
            val gid = Profile.groupId(name, pass)
            toast("Verific grupul...")
            CloudGateway.checkGroup(this, gid) { st ->
                runOnUiThread {
                    when (st) {
                        CloudGateway.GROUP_OK -> { switchGroup(gid, name); toast("Ai intrat în grupul $name.") }
                        CloudGateway.GROUP_MISSING -> toast("Nu există un grup cu acest nume și această parolă.")
                        CloudGateway.GROUP_DELETED -> toast("Acest grup a fost șters.")
                        else -> toast("Nu am putut verifica (fără internet?).")
                    }
                }
            }
        }
    }

    private fun createGroupDialog() {
        toast("Verific câte grupuri ai...")
        CloudGateway.listOwned(this) { owned ->
            runOnUiThread {
                when {
                    owned == null -> toast("Nu am putut verifica (fără internet?).")
                    owned.size >= CloudGateway.MAX_OWNED_GROUPS ->
                        toast("Ai deja ${CloudGateway.MAX_OWNED_GROUPS} grupuri create. Șterge unul ca să creezi altul.")
                    else -> askNamePassword("Creează grup privat (${CloudGateway.MAX_OWNED_GROUPS - owned.size} rămase)", "Creează",
                        "Alege un nume și o parolă. Pe cei pe care vrei să-i vezi îi inviți dându-le ambele. Parola nu se poate recupera.") { name, pass ->
                        CloudGateway.createGroup(this, name, pass) { ok, msg, gid ->
                            runOnUiThread {
                                if (ok) {
                                    switchGroup(gid, name)
                                    copyText("grup", "Grup: $name\nParolă: $pass")
                                    toast("Grup creat: $name. Invitația (nume + parolă) e copiată, o poți lipi într-un mesaj.")
                                } else toast(msg)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun deleteOwnGroupDialog() {
        CloudGateway.listOwned(this) { owned ->
            runOnUiThread {
                if (owned == null) { toast("Nu am putut verifica (fără internet?)."); return@runOnUiThread }
                if (owned.isEmpty()) { toast("Nu ai grupuri create."); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("Șterge ce grup?").setItems(owned.map { it.second }.toTypedArray()) { _, i ->
                    val (gid, name) = owned[i]
                    AlertDialog.Builder(this).setTitle("Ștergi grupul $name?")
                        .setMessage("Toate alarmele și pozițiile din grup dispar, iar toți membrii sunt mutați în GLOBAL.")
                        .setPositiveButton("ȘTERGE") { _, _ ->
                            CloudGateway.deleteOwnGroup(this, gid) { ok, msg ->
                                runOnUiThread {
                                    toast(msg)
                                    if (ok && gid == Profile.group) switchGroup(Profile.GLOBAL, Profile.GLOBAL)
                                }
                            }
                        }.setNegativeButton("Anulează", null).show()
                }.show()
            }
        }
    }

    // ---------- Admin ----------
    private fun showAdminMenu() {
        if (adminCode == null) {
            askText("Admin", "Cod admin", "", 40, "Intră", secret = true) { raw ->
                toast("Verific codul...")
                CloudGateway.adminVerify(this, raw) { http ->
                    runOnUiThread {
                        when {
                            http in 200..299 -> { adminCode = raw; toast("Mod admin activ."); showAdminMenu() }
                            http == 401 || http == 403 -> toast("Refuzat (HTTP $http): codul nu coincide cu /config/adminCode din Firebase, sau regulile nu sunt publicate.")
                            http == -1 -> toast("Nu am putut verifica (fără internet?).")
                            else -> toast("Eroare Firebase (HTTP $http).")
                        }
                    }
                }
            }
            return
        }
        val items = arrayOf("Șterge toate alarmele din grupul curent", "Șterge un grup (după ID din consola Firebase)", "Ieși din modul admin")
        AlertDialog.Builder(this).setTitle("Admin").setItems(items) { _, which ->
            val code = adminCode ?: return@setItems
            when (which) {
                0 -> AlertDialog.Builder(this).setTitle("Ștergi alarmele?")
                    .setMessage("Se șterg alarmele active din grupul ${Profile.groupName}. Cine emite încă un SOS îl va reporni.")
                    .setPositiveButton("ȘTERGE") { _, _ ->
                        CloudGateway.adminClear(this, code) { ok, msg -> runOnUiThread { toast(msg) } }
                    }.setNegativeButton("Anulează", null).show()
                1 -> askText("Șterge grup", "ID grup (din Firebase → meta)", "", 20, "ȘTERGE") { raw ->
                    val g = Profile.sanitizeGroup(raw)
                    if (g.length < 6 || g == Profile.GLOBAL) { toast("ID invalid (GLOBAL nu se poate șterge)."); return@askText }
                    CloudGateway.adminDeleteGroup(this, code, g) { ok, msg ->
                        runOnUiThread {
                            toast(if (ok) msg else "Nu am putut șterge grupul.")
                            if (ok && g == Profile.group) switchGroup(Profile.GLOBAL, Profile.GLOBAL)
                        }
                    }
                }
                2 -> { adminCode = null; toast("Ai ieșit din modul admin.") }
            }
        }.show()
    }

    private fun fitAll() {
        val now = System.currentTimeMillis()
        val pts = ArrayList<GeoPoint>()
        if (MeshBus.myLat != 0.0) pts.add(GeoPoint(MeshBus.myLat, MeshBus.myLon))
        MeshBus.alerts.values.forEach { if (it.age(now) < MeshBus.MAX_AGE_MIN) pts.add(GeoPoint(it.lat, it.lon)) }
        MeshBus.people.values.forEach { if (it.uid != Profile.uid && now / 1000 - it.tsSec <= 600) pts.add(GeoPoint(it.lat, it.lon)) }
        if (pts.isEmpty()) { Toast.makeText(this, "Nu există poziții de afișat.", Toast.LENGTH_SHORT).show(); return }
        val box = BoundingBox.fromGeoPoints(pts)
        if (pts.size == 1 || (box.latitudeSpan < 1e-5 && box.longitudeSpan < 1e-5)) {
            map.controller.setZoom(17.0)
            map.controller.animateTo(pts[0])
        } else {
            map.zoomToBoundingBox(box.increaseByScale(1.4f), true)
        }
    }

    /** Descarcă dalele hărții pentru zona vizibilă, ca să se vadă și fără internet. */
    private fun downloadMap() {
        val box = map.boundingBox
        val zMin = 12
        val zMax = 17
        val cm = CacheManager(map)
        val count = cm.possibleTilesInArea(box, zMin, zMax)
        if (count > 3000) {
            Toast.makeText(this, "Zona e prea mare ($count dale). Apropie harta și încearcă din nou.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hartă offline")
            .setMessage("Descarc zona vizibilă (zoom $zMin–$zMax): $count dale, aprox. ${count * 15 / 1024 + 1} MB. Ai nevoie de internet.")
            .setPositiveButton("Descarcă") { _, _ ->
                try {
                    cm.downloadAreaAsync(this, box, zMin, zMax, object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() { runOnUiThread { Toast.makeText(this@MainActivity, "Harta offline e gata.", Toast.LENGTH_LONG).show() } }
                        override fun onTaskFailed(errors: Int) { runOnUiThread { Toast.makeText(this@MainActivity, "Descărcare incompletă ($errors erori).", Toast.LENGTH_LONG).show() } }
                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
                        override fun downloadStarted() {}
                        override fun setPossibleTilesInArea(total: Int) {}
                    })
                    Toast.makeText(this, "Descarc harta...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Nu pot descărca: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showAlertDialog(a: MeshBus.Alert) {
        val now = System.currentTimeMillis()
        val dist = if (MeshBus.myLat != 0.0) {
            val r = FloatArray(1)
            Location.distanceBetween(MeshBus.myLat, MeshBus.myLon, a.lat, a.lon, r)
            if (r[0] >= 1000f) "%.2f km".format(r[0] / 1000f) else "${r[0].toInt()} m"
        } else "necunoscută"
        val d = AlertDialog.Builder(this)
            .setTitle("SOS: ${a.label}")
            .setMessage("${a.typeName}\nDistanță: $dist\nVechime: ${a.age(now)} min\nSalturi: ${a.hops}\n%.5f, %.5f".format(a.lat, a.lon))
            .setNegativeButton("Închide", null)
        if (adminCode != null) {
            d.setNeutralButton("ȘTERGE (admin)") { _, _ ->
                CloudGateway.deleteAlert(this, a.id)
                MeshBus.removeAlert(a.id)
                toast("Alarma #${a.id} a fost ștearsă din cloud.")
            }
        }
        if (CloudGateway.enabled && Profile.name.length >= 3) {
            d.setPositiveButton("VIN SPRE TINE") { _, _ ->
                CloudGateway.respond(this, a.id) { ok ->
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Victima vede că vii spre ea." else "Nu am putut trimite (fără internet?).", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        d.show()
    }

    // ---------- SOS ----------
    private fun refreshButtons() {
        if (MeshBus.sosActive) {
            sosButton.text = "STOP"
            sosButton.background = roundBg(Color.parseColor("#2E9E5B"), 16f)
        } else {
            sosButton.text = "SOS"
            sosButton.background = roundBg(Color.parseColor("#D32F2F"), 16f)
        }
        flashButton.text = "🔦"
        when {
            torchId == null -> { flashButton.isEnabled = false; flashButton.background = roundBg(Color.DKGRAY, 16f) }
            flashOn -> flashButton.background = roundBg(Color.parseColor("#F9A825"), 16f)
            else -> flashButton.background = roundBg(Color.parseColor("#455A64"), 16f)
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
        map.invalidate()
        ensureAnim()
    }

    // ---------- Lanternă ----------
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

    // ---------- Permisiuni ----------
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
            if (!MeshBus.sosActive) MeshBus.status("Poziție: %.5f, %.5f".format(loc.latitude, loc.longitude))
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
                if (lm.isProviderEnabled(prov)) { lm.requestLocationUpdates(prov, 2000L, 1f, localListener); any = true }
            }
            if (!any) MeshBus.status("Locația e oprită în telefon. Pornește GPS-ul!")
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { localListener.onLocationChanged(it) }
        } catch (e: Exception) {
            MeshBus.status("Eroare locație: ${e.javaClass.simpleName}")
        }
    }

    // ---------- Busolă ----------
    private fun updateCompass() {
        val now = System.currentTimeMillis()
        val live = MeshBus.alerts.values.filter { it.age(now) < MeshBus.MAX_AGE_MIN }
        if (MeshBus.myLat == 0.0 || live.isEmpty()) {
            arrow.active = false; arrow.invalidate()
            compassText.text = "Busolă: nicio alarmă activă"
            return
        }
        val r = FloatArray(2)
        var best = live[0]
        var bestDist = Float.MAX_VALUE
        for (a in live) {
            Location.distanceBetween(MeshBus.myLat, MeshBus.myLon, a.lat, a.lon, r)
            if (r[0] < bestDist) { bestDist = r[0]; best = a }
        }
        Location.distanceBetween(MeshBus.myLat, MeshBus.myLon, best.lat, best.lon, r)
        val bearing = (r[1] + 360f) % 360f

        arrow.active = hasAzimuth
        arrow.angle = ((bearing - azimuth) + 360f) % 360f
        arrow.invalidate()

        val dist = if (bestDist >= 1000f) "%.2f km".format(bestDist / 1000f) else "${bestDist.toInt()} m"
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SV", "V", "NV")
        val dir = dirs[((bearing + 22.5f) / 45f).toInt() % 8]
        compassText.text = "${best.label} • SOS #${best.id}\n$dist • ${bearing.toInt()}° $dir" +
            if (hasAzimuth) "" else "\n(busola se calibrează...)"
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rot = FloatArray(9); val ori = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        var az = Math.toDegrees(ori[0].toDouble()).toFloat()
        if (MeshBus.myLat != 0.0) {
            az += GeomagneticField(MeshBus.myLat.toFloat(), MeshBus.myLon.toFloat(), 0f, System.currentTimeMillis()).declination
        }
        az = (az + 360f) % 360f
        if (!hasAzimuth) { azimuth = az; hasAzimuth = true }
        else {
            var d = az - azimuth
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            azimuth = (azimuth + 0.15f * d + 360f) % 360f
        }
        updateCompass()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- MeshBus.Listener ----------
    private var centeredOnce = false

    override fun onLocation(lat: Double, lon: Double) {
        if (!centeredOnce) {
            centeredOnce = true
            map.controller.setCenter(GeoPoint(lat, lon))
        }
        if (followMe) map.controller.setCenter(GeoPoint(lat, lon))   // EU activ: harta rămâne centrată pe mine
        map.invalidate()
        updateCompass()
    }

    override fun onChanged() {
        map.invalidate()
        updateCompass()
        refreshButtons()
        ensureAnim()
    }

    override fun onNewAlert(a: MeshBus.Alert) {
        map.controller.animateTo(GeoPoint(a.lat, a.lon))
    }

    override fun onStatus(text: String) { statusText.text = text }

    override fun onStart() {
        super.onStart()
        MeshBus.listener = this
        statusText.text = MeshBus.status
        refreshProfile()
        refreshButtons()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run { compassText.text = "Telefonul nu are senzor de busolă." }
        map.invalidate()
        ensureAnim()
    }

    override fun onStop() {
        if (MeshBus.listener === this) MeshBus.listener = null
        sensorManager.unregisterListener(this)
        stopFlash()
        uiHandler.removeCallbacks(animRunner)
        animating = false
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
