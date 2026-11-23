package com.sos.meshoffline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Punte către internet: dacă telefonul are net, urcă SOS-ul în Firebase Realtime Database (REST).
 * Completează DB_URL cu adresa bazei tale, ex: https://PROIECT-default-rtdb.europe-west1.firebasedatabase.app
 * Dacă DB_URL e gol, funcția e dezactivată.
 */
object CloudGateway {
    const val DB_URL = ""

    private val exec = Executors.newSingleThreadExecutor()
    private val lastSent = HashMap<Int, Long>()

    fun hasInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun upload(ctx: Context, id: Int, type: Int, lat: Double, lon: Double, hops: Int) {
        if (DB_URL.isBlank()) return
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        synchronized(lastSent) {
            val prev = lastSent[id]
            if (prev != null && now - prev < 60_000) return   // max 1 urcare/minut per SOS
            if (!hasInternet(app)) return
            lastSent[id] = now
        }
        exec.execute {
            try {
                val body = JSONObject().apply {
                    put("sosId", id)
                    put("type", MeshBus.TYPES.getOrElse(type) { MeshBus.TYPES[0] })
                    put("lat", lat)
                    put("lon", lon)
                    put("hops", hops)
                    put("timestamp", now / 1000)
                }.toString()
                val conn = URL("${DB_URL.trimEnd('/')}/sos/$id.json").openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"            // PUT = idempotent, același ID se suprascrie
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
                synchronized(lastSent) { lastSent.remove(id) }  // reîncearcă la următorul pachet
            }
        }
    }
}
