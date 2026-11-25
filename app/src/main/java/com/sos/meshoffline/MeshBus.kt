package com.sos.meshoffline

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/** Legătura dintre serviciul de fundal și ecran (același proces) + starea comună (alarme, persoane). */
object MeshBus {
    val TYPES = arrayOf(
        "SOS GENERAL", "URGENTA MEDICALA", "INCENDIU / PERICOL",
        "ACCIDENT", "AM NEVOIE DE APA / MANCARE", "PIERDUT / DEZORIENTAT"
    )
    const val MAX_AGE_MIN = 30
    /** Fiecare tip de urgență are culoarea și simbolul lui pe hartă. */
    val TYPE_COLORS = intArrayOf(0xFFE5393B.toInt(), 0xFFE91E63.toInt(), 0xFFFF6D00.toInt(), 0xFFFFC107.toInt(), 0xFF7E57C2.toInt(), 0xFF8D6E63.toInt())
    val TYPE_SYMBOLS = arrayOf("!", "✚", "🔥", "⚠", "💧", "?")

    class Alert(
        val id: Int, val hops: Int, val type: Int, val lat: Double, val lon: Double,
        val uid: String = "", val name: String = "",
        val ageMin: Int = 0, val rxAt: Long = System.currentTimeMillis(), val fromCloud: Boolean = false
    ) {
        val label: String get() = if (name.isBlank()) "Necunoscut" else "$name#$uid"
        val typeName: String get() = TYPES.getOrElse(type) { TYPES[0] }
        /** Vechime în minute: cât a spus pachetul + cât a trecut de când l-am primit. */
        fun age(now: Long = System.currentTimeMillis()): Int = ageMin + ((now - rxAt) / 60000L).toInt()
        fun originSec(now: Long = System.currentTimeMillis()): Long = now / 1000 - age(now) * 60L
    }

    class Person(val uid: String, val name: String, val lat: Double, val lon: Double, val src: String, val tsSec: Long)

    interface Listener {
        fun onLocation(lat: Double, lon: Double)
        fun onChanged()
        fun onNewAlert(a: Alert)
        fun onStatus(text: String)
    }

    @Volatile var myLat = 0.0
    @Volatile var myLon = 0.0
    @Volatile var sosActive = false
    @Volatile var mySosId = 0
    @Volatile var mySosType = 0
    @Volatile var status = "Pornesc serviciul Mesh..."
    @Volatile var clearAtSec = 0L
    /** Setat de meniu: serviciul verifică și actualizează acum harta offline. */
    @Volatile var mapForce = false
    @Volatile var people: Map<String, Person> = emptyMap()
    @Volatile var responders: List<String> = emptyList()
    val alerts = ConcurrentHashMap<Int, Alert>()
    @Volatile var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())

    fun location(lat: Double, lon: Double) {
        myLat = lat; myLon = lon
        main.post { listener?.onLocation(lat, lon) }
    }

    /** 0 = respinsă (expirată / ștearsă de admin / mai veche), 1 = actualizată, 2 = nouă. */
    fun putAlert(a: Alert, announce: Boolean): Int {
        val now = System.currentTimeMillis()
        if (a.age(now) >= MAX_AGE_MIN) return 0
        if (a.originSec(now) <= clearAtSec) return 0
        val ex = alerts[a.id]
        if (ex != null && ex.age(now) < a.age(now)) return 0     // o copie mai proaspătă există deja
        alerts[a.id] = a
        main.post {
            listener?.onChanged()
            if (ex == null && announce) listener?.onNewAlert(a)
        }
        return if (ex == null) 2 else 1
    }

    fun removeAlert(id: Int) {
        if (alerts.remove(id) != null) main.post { listener?.onChanged() }
    }

    /** Admin: ascunde toate alarmele începute înainte de acest moment. */
    fun applyClear(sec: Long) {
        if (sec <= clearAtSec) return
        clearAtSec = sec
        val now = System.currentTimeMillis()
        var any = false
        for (a in alerts.values) if (a.originSec(now) <= sec) { alerts.remove(a.id); any = true }
        if (any) main.post { listener?.onChanged() }
    }

    fun prune() {
        val now = System.currentTimeMillis()
        var any = false
        for (a in alerts.values) if (a.age(now) >= MAX_AGE_MIN) { alerts.remove(a.id); any = true }
        if (any) main.post { listener?.onChanged() }
    }

    /** La schimbarea grupului: dispar alarmele și persoanele venite din cloud. */
    fun dropCloud() {
        for (a in alerts.values) if (a.fromCloud) alerts.remove(a.id)
        people = emptyMap()
        responders = emptyList()
        main.post { listener?.onChanged() }
    }

    fun setPeople(list: List<Person>) {
        people = list.associateBy { it.uid }
        main.post { listener?.onChanged() }
    }

    fun status(text: String) {
        status = text
        main.post { listener?.onStatus(text) }
    }
}
