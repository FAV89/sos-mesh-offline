package com.sos.meshoffline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Punte către internet: dacă telefonul are net (chiar și slab), urcă SOS-ul în Firebase Realtime Database (REST).
 * Dacă DB_URL e gol, funcția e dezactivată. Reîncercarea o face serviciul (MeshForegroundService).
 */
object CloudGateway {
    const val DB_URL = "https://sos-offline-mash-default-rtdb.europe-west1.firebasedatabase.app/"

    private val exec = Executors.newSingleThreadExecutor()

    val enabled: Boolean get() = DB_URL.isNotBlank()

    fun hasInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** onDone(true) doar dacă serverul a confirmat scrierea; altfel serviciul reîncearcă mai târziu. */
    fun upload(
        ctx: Context, id: Int, type: Int, lat: Double, lon: Double, hops: Int,
        name: String, uid: String, onDone: (Boolean) -> Unit
    ) {
        if (!enabled || !hasInternet(ctx.applicationContext)) { onDone(false); return }
        exec.execute {
            var ok = false
            try {
                val body = JSONObject().apply {
                    put("sosId", id)
                    put("type", MeshBus.TYPES.getOrElse(type) { MeshBus.TYPES[0] })
                    put("lat", lat)
                    put("lon", lon)
                    put("hops", hops)
                    put("name", name)
                    put("uid", uid)
                    put("timestamp", System.currentTimeMillis() / 1000)
                }.toString()
                val conn = URL("${DB_URL.trimEnd('/')}/sos/$id.json").openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"            // PUT = idempotent, același ID se suprascrie
                conn.connectTimeout = 15000           // internet slab (3G): timeout generos
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                ok = conn.responseCode in 200..299
                conn.disconnect()
            } catch (_: Exception) {}
            onDone(ok)
        }
    }

    /** Citește alarmele active din cloud (ultimele 6 ore), inclusiv cele trimise de pe pagina web. */
    fun fetchAlerts(ctx: Context, onResult: (List<MeshBus.Alert>?) -> Unit) {
        if (!enabled || !hasInternet(ctx.applicationContext)) { onResult(null); return }
        exec.execute {
            var res: List<MeshBus.Alert>? = null
            try {
                val conn = URL("${DB_URL.trimEnd('/')}/sos.json").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                if (conn.responseCode in 200..299) {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val list = ArrayList<MeshBus.Alert>()
                    if (txt.isNotBlank() && txt.trim() != "null") {
                        val o = JSONObject(txt)
                        val cutoff = System.currentTimeMillis() / 1000 - 6 * 3600
                        for (k in o.keys()) {
                            val a = o.optJSONObject(k) ?: continue
                            if (a.optLong("timestamp") < cutoff) continue
                            val id = a.optInt("sosId", -1)
                            val lat = a.optDouble("lat", Double.NaN)
                            val lon = a.optDouble("lon", Double.NaN)
                            if (id < 0 || lat.isNaN() || lon.isNaN()) continue
                            val t = MeshBus.TYPES.indexOf(a.optString("type")).coerceAtLeast(0)
                            list.add(MeshBus.Alert(id, a.optInt("hops"), t, lat, lon, a.optString("uid"), a.optString("name")))
                        }
                    }
                    res = list
                }
                conn.disconnect()
            } catch (_: Exception) {}
            onResult(res)
        }
    }

    /** Șterge alarma din cloud (când SOS-ul se oprește). Dacă nu e internet, rămâne până expiră. */
    fun delete(ctx: Context, id: Int) {
        if (!enabled || !hasInternet(ctx.applicationContext)) return
        exec.execute {
            try {
                val conn = URL("${DB_URL.trimEnd('/')}/sos/$id.json").openConnection() as HttpURLConnection
                conn.requestMethod = "DELETE"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }
}
