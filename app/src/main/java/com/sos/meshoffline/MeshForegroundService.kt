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
        private const val NOTIF_ID = 1
        private const val MANUFACTURER_ID = 0xFFFF
        private const val MAX_HOPS = 10
        private const val PACKET_SIZE = 12   // ID(2) Hops(1) Tip(1) Lat(4) Lon(4)
        private const val KEEP_MS = 30 * 60 * 1000L
    }

    private class Packet(val id: Int, val hops: Int, val type: Int, val lat: Float, val lon: Float) {
        val at = System.currentTimeMillis()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val relayQueue = LinkedHashMap<Int, Packet>()
    private var rotateIndex = 0

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
            if (raw.size != PACKET_SIZE) return
            val buf = ByteBuffer.wrap(raw)
            val id = buf.short.toInt() and 0xFFFF
            val hops = buf.get().toInt() and 0xFF
            val type = buf.get().toInt() and 0xFF
            val lat = buf.float
            val lon = buf.float

            if (MeshBus.sosActive && id == MeshBus.mySosId) return
            if (lat.isNaN() || lon.isNaN() || lat !in -90f..90f || lon !in -180f..180f) return

            val known = relayQueue[id]
            if (known != null && known.hops <= hops + 1) return   // am deja o cale la fel de scurtă

            if (hops + 1 <= MAX_HOPS) relayQueue[id] = Packet(id, hops + 1, type, lat, lon)

            MeshBus.alert(MeshBus.Alert(id, hops, type, lat.toDouble(), lon.toDouble()), known == null)
            MeshBus.status("ALARMĂ PRIMITĂ #$id (${MeshBus.TYPES.getOrElse(type) { "SOS" }})\nSalturi BT: $hops")
            CloudGateway.upload(this@MeshForegroundService, id, type, lat.toDouble(), lon.toDouble(), hops)
        }
    }

    // O singură buclă: la 3 secunde emite următorul pachet (al meu + cele retransmise)
    private val loop = object : Runnable {
        override fun run() {
            wakeLock?.acquire(10 * 60 * 1000L)   // timeout de siguranță, reînnoit la fiecare ciclu
            val now = System.currentTimeMillis()
            relayQueue.values.removeAll { now - it.at > KEEP_MS }

            val list = ArrayList<Packet>()
            if (MeshBus.sosActive && MeshBus.myLat != 0.0) {
                list.add(Packet(MeshBus.mySosId, 0, MeshBus.mySosType, MeshBus.myLat.toFloat(), MeshBus.myLon.toFloat()))
                CloudGateway.upload(this@MeshForegroundService, MeshBus.mySosId, MeshBus.mySosType, MeshBus.myLat, MeshBus.myLon, 0)
            }
            list.addAll(relayQueue.values)

            if (list.isNotEmpty()) {
                rotateIndex = (rotateIndex + 1) % list.size
                val p = list[rotateIndex]
                advertise(p)
            } else {
                stopAdvertising()
            }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
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
                MeshBus.status("EMITERE SOS ACTIVĂ #${MeshBus.mySosId}\n${MeshBus.TYPES.getOrElse(MeshBus.mySosType) { "SOS" }}")
            }
            ACTION_SOS_STOP -> {
                MeshBus.sosActive = false
                MeshBus.status("Alarma oprită. Mesh activ în fundal.")
            }
            ACTION_QUIT -> {
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
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        val filter = ScanFilter.Builder().setManufacturerData(MANUFACTURER_ID, ByteArray(0)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner?.startScan(listOf(filter), settings, scanCallback) } catch (_: Exception) {}

        handler.post(loop)
    }

    @SuppressLint("MissingPermission")
    private fun advertise(p: Packet) {
        stopAdvertising()
        val buf = ByteBuffer.allocate(PACKET_SIZE)
        buf.putShort(p.id.toShort())
        buf.put(p.hops.toByte())
        buf.put(p.type.toByte())
        buf.putFloat(p.lat)
        buf.putFloat(p.lon)
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "SOS Mesh", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val quit = PendingIntent.getService(
            this, 1, Intent(this, MeshForegroundService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("SOS Mesh Activ")
            .setContentText("Scanare și retransmisie în fundal")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(open)
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
