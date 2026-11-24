package com.sos.meshoffline

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/** Legătura dintre serviciul de fundal și ecran (același proces). */
object MeshBus {
    val TYPES = arrayOf("SOS GENERAL", "URGENTA MEDICALA", "INCENDIU / PERICOL")

    class Alert(
        val id: Int, val hops: Int, val type: Int, val lat: Double, val lon: Double,
        val uid: String = "", val name: String = ""
    ) {
        val label: String get() = if (name.isBlank()) "Necunoscut" else "$name#$uid"
    }

    interface Listener {
        fun onLocation(lat: Double, lon: Double)
        fun onAlert(a: Alert, isNew: Boolean)
        fun onAlertRemoved(id: Int)
        fun onStatus(text: String)
    }

    @Volatile var myLat = 0.0
    @Volatile var myLon = 0.0
    @Volatile var sosActive = false
    @Volatile var mySosId = 0
    @Volatile var mySosType = 0
    @Volatile var status = "Pornesc serviciul Mesh..."
    val alerts = ConcurrentHashMap<Int, Alert>()
    @Volatile var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())

    fun location(lat: Double, lon: Double) {
        myLat = lat; myLon = lon
        main.post { listener?.onLocation(lat, lon) }
    }

    fun alert(a: Alert, isNew: Boolean) {
        alerts[a.id] = a
        main.post { listener?.onAlert(a, isNew) }
    }

    fun remove(id: Int) {
        alerts.remove(id)
        main.post { listener?.onAlertRemoved(id) }
    }

    fun status(text: String) {
        status = text
        main.post { listener?.onStatus(text) }
    }
}
