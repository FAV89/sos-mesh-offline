package com.sos.meshoffline

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.nio.ByteBuffer
import kotlin.random.Random

class MeshForegroundService : Service() {

    companion object {
        const val ACTION_SOS_START = "com.sos.meshoffline.SOS_START"
        const val ACTION_SOS_STOP = "com.sos.meshoffline.SOS_STOP"
        const val ACTION_QUIT = "com.sos.meshoffline.QUIT"
        const val EXTRA_TYPE = "type"
        private const val CHANNEL = "mesh"
        private const val ALERT_CHANNEL = "alerts"
        private const val NOTIF_ID = 1
        private const val MANUFACTURER_ID = 0xFFFF
        private const val MAX_HOPS = 10
        // Pachet v3: ID(2) Hops(1) Tip(1) Lat(4) Lon(4) Vârstă-min(1) UID(3) Poreclă(0-8) => 16..24 bytes
        private const val HEADER_SIZE = 16
        private const val MAX_SIZE = 24
    }

    private class Packet(
        val id: Int, val hops: Int, val type: Int, val lat: Float, val lon: Float,
        val uid: String, val name: String, val ageAtRx: Int, val rxAt: Long
    ) {
        fun age(now: Long): Int = ageAtRx + ((now - rxAt) / 60000L).toInt()
        @Volatile var uploaded = false
        @Volatile var uploading = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val relayQueue = LinkedHashMap<Int, Packet>()      // doar pe firul principal
    private val lastUploadAt = HashMap<Int, Long>()
    private var cloudIds = HashSet<Int>()
    private var rotateIndex = 0

    @Volatile private var ownUploading = false
    @Volatile private var polling = false
    @Volatile private var presenceBusy = false
    private var lastOwnUpload = 0L
    private var lastPoll = 0L
    private var lastPresence = 0L
    private var firstPollDone = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var locationManager: LocationManager? = null
    private var started = false

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            MeshBus.location(loc.latitude, loc.longitude)
            if (!MeshBus.sosActive) {
                MeshBus.status("Poziție: %.5f, %.5f\nMesh activ în fundal.".format(loc.latitude, loc.longitude))
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val raw = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (raw.size < HEADER_SIZE || raw.size > MAX_SIZE) return
            val buf = ByteBuffer.wrap(raw)
            val id = buf.short.toInt() and 0xFFFF
            val hops = buf.get().toInt() and 0xFF
            val type = buf.get().toInt() and 0xFF
            val lat = buf.float
            val lon = buf.float
            val age = buf.get().toInt() and 0xFF
            val u = ByteArray(3)
            buf.get(u)
            val uid = Profile.bytesToUid(u)
            val name = String(raw, HEADER_SIZE, raw.size - HEADER_SIZE, Charsets.US_ASCII)

            if (MeshBus.sosActive && id == MeshBus.mySosId) return
            if (lat.isNaN() || lon.isNaN() || lat !in -90f..90f || lon !in -180f..180f) return

            val now = System.currentTimeMillis()
            if (age >= MeshBus.MAX_AGE_MIN) return                               // prea veche: moare în mesh
            if (now / 1000 - age * 60L <= MeshBus.clearAtSec) return              // ștearsă de admin

            val known = relayQueue[id]
            val moved = known != null && (known.lat != lat || known.lon != lon) && age <= known.age(now)
            val fresher = known == null || age < known.age(now) - 1 || hops + 1 < known.hops || moved
            if (!fresher) return

            if (hops + 1 <= MAX_HOPS) relayQueue[id] = Packet(id, hops + 1, type, lat, lon, uid, name, age, now)

            val alert = MeshBus.Alert(id, hops, type, lat.toDouble(), lon.toDouble(), uid, name, age, now, false)
            if (MeshBus.putAlert(alert, true) == 2) {
                notifyAlert(alert)
                MeshBus.status("ALARMĂ PRIMITĂ de la ${alert.label}\n${alert.typeName} • salturi BT: $hops")
            }
        }
    }

    // O singură buclă la 3 secunde: curăță, sincronizează cu cloud-ul și emite următorul pachet
    private val loop = object : Runnable {
        override fun run() {
            wakeLock?.acquire(10 * 60 * 1000L)   // timeout de siguranță, reînnoit la fiecare ciclu
            val now = System.currentTimeMillis()
            val online = CloudGateway.enabled && CloudGateway.hasInternet(this@MeshForegroundService)

            // 1. Curățenie: alarme expirate (30 min) sau șterse de admin
            MeshBus.prune()
            relayQueue.values.removeAll { it.age(now) >= MeshBus.MAX_AGE_MIN || now / 1000 - it.age(now) * 60L <= MeshBus.clearAtSec }

            // 2. Pachetele de emis: al meu + cele retransmise
            val list = ArrayList<Packet>()
            if (MeshBus.sosActive && MeshBus.myLat != 0.0) {
                list.add(Packet(MeshBus.mySosId, 0, MeshBus.mySosType, MeshBus.myLat.toFloat(), MeshBus.myLon.toFloat(), Profile.uid, Profile.name, 0, now))
                if (online && !ownUploading && now - lastOwnUpload > 10_000) {
                    ownUploading = true
                    CloudGateway.uploadAlert(this@MeshForegroundService, MeshBus.mySosId, MeshBus.mySosType, MeshBus.myLat, MeshBus.myLon, 0,
                        Profile.name, Profile.uid, now / 1000) { ok ->
                        if (ok) lastOwnUpload = System.currentTimeMillis()
                        ownUploading = false
                    }
                }
                val help = MeshBus.responders
                MeshBus.status("EMITERE SOS ACTIVĂ #${MeshBus.mySosId}\n${MeshBus.TYPES.getOrElse(MeshBus.mySosType) { "SOS" }}" +
                    if (help.isNotEmpty()) "\nAJUTOR ÎN DRUM: ${help.joinToString(", ")}" else "")
            }
            list.addAll(relayQueue.values)

            // 3. Citire din cloud la 20 s (alarme de pe web / alte telefoane, persoane, marcaj admin)
            if (online && !polling && now - lastPoll > 20_000) {
                polling = true
                lastPoll = now
                CloudGateway.fetchAll(this@MeshForegroundService, if (MeshBus.sosActive) MeshBus.mySosId else -1) { snap ->
                    handler.post {
                        polling = false
                        if (snap != null) applyCloud(snap)
                    }
                }
            }

            // 4. Prezența mea pe hartă (poziție + poreclă), la 60 s, doar dacă e permis
            if (online && Profile.presence && !MeshBus.sosActive && !presenceBusy && now - lastPresence > 60_000 && MeshBus.myLat != 0.0) {
                presenceBusy = true
                lastPresence = now
                CloudGateway.uploadPresence(this@MeshForegroundService) { presenceBusy = false }
            }

            // 5. PUNTE: alarmele primite prin Bluetooth se urcă în cloud (și se reîncearcă până reușește)
            if (online) {
                for (p in relayQueue.values) {
                    val last = lastUploadAt[p.id] ?: 0L
                    if (!p.uploaded && !p.uploading && now - last > 30_000) {
                        p.uploading = true
                        lastUploadAt[p.id] = now
                        // timestamp = momentul estimat al alarmei originale, ca să nu "învie" alarme vechi
                        CloudGateway.uploadAlert(this@MeshForegroundService, p.id, p.type, p.lat.toDouble(), p.lon.toDouble(), p.hops - 1,
                            p.name, p.uid, now / 1000 - p.age(now) * 60L) { ok ->
                            if (ok) p.uploaded = true
                            p.uploading = false
                        }
                    }
                }
            }

            // 6. Emisie Bluetooth: un pachet pe ciclu, în rotație
            if (list.isNotEmpty()) {
                rotateIndex = (rotateIndex + 1) % list.size
                advertise(list[rotateIndex], now)
            } else {
                stopAdvertising()
            }
            handler.postDelayed(this, 3000)
        }
    }

    /** Aplică datele din cloud (pe firul principal). */
    private fun applyCloud(snap: CloudGateway.Snapshot) {
        val now = System.currentTimeMillis()
        if (snap.groupDeleted) {
            // grupul a fost șters de proprietar sau de admin: ieșim din el
            Profile.setGroup(this, "")
            MeshBus.dropCloud()
            cloudIds = HashSet()
            MeshBus.status("Grupul a fost șters. Ești doar pe Bluetooth. (Meniu → Grupuri)")
            return
        }
        MeshBus.applyClear(snap.clearAt)

        val ids = HashSet<Int>()
        for (a in snap.alerts) {
            if (MeshBus.sosActive && a.id == MeshBus.mySosId) continue
            ids.add(a.id)
            val r = MeshBus.putAlert(a, firstPollDone)
            if (r == 2 && firstPollDone) notifyAlert(a)
            // retransmisie prin Bluetooth, ca telefoanele fără internet din jur să vadă alarma din cloud
            val known = relayQueue[a.id]
            if (r != 0 && (known == null || a.age(now) < known.age(now) - 1)) {
                val p = Packet(a.id, (a.hops + 1).coerceAtMost(MAX_HOPS), a.type, a.lat.toFloat(), a.lon.toFloat(), a.uid, a.name, a.age(now), now)
                p.uploaded = true      // vine din cloud, nu o urcăm înapoi
                relayQueue[a.id] = p
            }
        }
        // alarme care au dispărut din cloud (oprite sau șterse) dispar și de pe hartă
        for (old in cloudIds) {
            if (old in ids) continue
            val ex = MeshBus.alerts[old]
            if (ex == null || ex.fromCloud || ex.age(now) >= 2) {
                MeshBus.removeAlert(old)
                relayQueue.remove(old)
            }
        }
        cloudIds = ids
        MeshBus.setPeople(snap.people)
        MeshBus.responders = snap.responders
        firstPollDone = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Profile.load(this)
        createChannels()
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            MeshBus.status("Serviciul nu a putut porni: lipsesc permisiuni.")
            stopSelf()
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sosmesh:scan").apply { setReferenceCounted(false) }
        startHardware()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SOS_START -> {
                MeshBus.mySosId = Random.nextInt(1, 32000)
                MeshBus.mySosType = intent.getIntExtra(EXTRA_TYPE, 0)
                MeshBus.sosActive = true
                lastOwnUpload = 0L
                MeshBus.status("EMITERE SOS ACTIVĂ #${MeshBus.mySosId}\n${MeshBus.TYPES.getOrElse(MeshBus.mySosType) { "SOS" }}")
            }
            ACTION_SOS_STOP -> {
                CloudGateway.deleteAlert(this, MeshBus.mySosId)
                MeshBus.sosActive = false
                MeshBus.responders = emptyList()
                MeshBus.status("Alarma oprită. Mesh activ în fundal.")
            }
            ACTION_QUIT -> {
                if (MeshBus.sosActive) CloudGateway.deleteAlert(this, MeshBus.mySosId)
                CloudGateway.removePresence(this)
                MeshBus.sosActive = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startHardware() {
        if (started) return
        started = true
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, locListener)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, locListener)
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { locListener.onLocationChanged(it) }
        } catch (e: Exception) {
            MeshBus.status("Eroare locație: pornește GPS-ul!")
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            MeshBus.status("ATENȚIE: pornește Bluetooth-ul!")
        } else {
            advertiser = adapter.bluetoothLeAdvertiser
            scanner = adapter.bluetoothLeScanner
            val filter = ScanFilter.Builder().setManufacturerData(MANUFACTURER_ID, ByteArray(0)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try { scanner?.startScan(listOf(filter), settings, scanCallback) } catch (_: Exception) {}
        }
        handler.post(loop)    // bucla rulează și fără Bluetooth (cloud, prezență)
    }

    @SuppressLint("MissingPermission")
    private fun advertise(p: Packet, now: Long) {
        stopAdvertising()
        val nameBytes = p.name.toByteArray(Charsets.US_ASCII).take(8).toByteArray()
        val buf = ByteBuffer.allocate(HEADER_SIZE + nameBytes.size)
        buf.putShort(p.id.toShort())
        buf.put(p.hops.toByte())
        buf.put(p.type.toByte())
        buf.putFloat(p.lat)
        buf.putFloat(p.lon)
        buf.put(p.age(now).coerceIn(0, 255).toByte())
        buf.put(Profile.uidToBytes(p.uid.ifEmpty { "AAAA" }))
        buf.put(nameBytes)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder().addManufacturerData(MANUFACTURER_ID, buf.array()).build()
        try { advertiser?.startAdvertising(settings, data, advertiseCallback) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "SOS Mesh", NotificationManager.IMPORTANCE_LOW))
            val alerts = NotificationChannel(ALERT_CHANNEL, "Alarme SOS primite", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
            }
            nm.createNotificationChannel(alerts)
        }
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Sunet + vibrație când vine o alarmă nouă, chiar cu ecranul stins. */
    private fun notifyAlert(a: MeshBus.Alert) {
        try {
            val n = Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS: ${a.label}")
                .setContentText("${a.typeName} • %.5f, %.5f".format(a.lat, a.lon))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1000 + a.id, n)
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val quit = PendingIntent.getService(
            this, 1, Intent(this, MeshForegroundService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("SOS Mesh Activ")
            .setContentText("Scanare și retransmisie în fundal")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Oprește", quit).build())
            .build()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        stopAdvertising()
        try { locationManager?.removeUpdates(locListener) } catch (_: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        started = false
        super.onDestroy()
    }
}
