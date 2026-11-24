package com.sos.meshoffline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Legătura cu Firebase Realtime Database (REST). Totul stă sub /g/{COD_GRUP}/..., deci doar cei care
 * știu codul de grup pot citi sau scrie. Dacă DB_URL sau codul de grup lipsesc, cloud-ul e oprit (rămâne doar Bluetooth).
 */
object CloudGateway {
    const val DB_URL = "https://sos-offline-mash-default-rtdb.europe-west1.firebasedatabase.app/"

    class Snapshot(
        val alerts: List<MeshBus.Alert>,
        val people: List<MeshBus.Person>,
        val clearAt: Long,
        val responders: List<String>
    )

    private val exec = Executors.newSingleThreadExecutor()

    val enabled: Boolean get() = DB_URL.isNotBlank() && Profile.cloudReady

    private fun base() = "${DB_URL.trimEnd('/')}/g/${Profile.group}"

    fun hasInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun http(method: String, url: String, body: String? = null): Pair<Int, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000     // internet slab (3G): timeout generos
            conn.readTimeout = 15000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val txt = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            conn.disconnect()
            code to txt
        } catch (_: Exception) { -1 to "" }
    }

    private fun parseObj(t: String): JSONObject? =
        try { if (t.isBlank() || t.trim() == "null") null else JSONObject(t) } catch (_: Exception) { null }

    // ---------- scriere ----------

    fun uploadAlert(
        ctx: Context, id: Int, type: Int, lat: Double, lon: Double, hops: Int,
        name: String, uid: String, tsSec: Long, onDone: (Boolean) -> Unit
    ) {
        if (!enabled || !hasInternet(ctx.applicationContext)) { onDone(false); return }
        exec.execute {
            val body = JSONObject().apply {
                put("sosId", id)
                put("type", MeshBus.TYPES.getOrElse(type) { MeshBus.TYPES[0] })
                put("lat", lat); put("lon", lon); put("hops", hops)
                put("name", name); put("uid", uid)
                put("timestamp", tsSec)
            }.toString()
            onDone(http("PUT", "${base()}/sos/$id.json", body).first in 200..299)
        }
    }

    fun deleteAlert(ctx: Context, id: Int) {
        if (!enabled || !hasInternet(ctx.applicationContext)) return
        exec.execute {
            http("DELETE", "${base()}/sos/$id.json")
            http("DELETE", "${base()}/resp/$id.json")
        }
    }

    fun uploadPresence(ctx: Context, onDone: (Boolean) -> Unit) {
        if (!enabled || !Profile.presence || Profile.name.length < 3 || MeshBus.myLat == 0.0 || !hasInternet(ctx.applicationContext)) {
            onDone(false); return
        }
        exec.execute {
            val body = JSONObject().apply {
                put("name", Profile.name); put("uid", Profile.uid)
                put("lat", MeshBus.myLat); put("lon", MeshBus.myLon)
                put("src", "apk")
                put("timestamp", System.currentTimeMillis() / 1000)
            }.toString()
            onDone(http("PUT", "${base()}/presence/${Profile.uid}.json", body).first in 200..299)
        }
    }

    fun removePresence(ctx: Context) {
        if (!enabled || !hasInternet(ctx.applicationContext)) return
        exec.execute { http("DELETE", "${base()}/presence/${Profile.uid}.json") }
    }

    /** "Vin spre tine": cel care răspunde se înscrie la alarmă; victima vede numele lui. */
    fun respond(ctx: Context, alertId: Int, onDone: (Boolean) -> Unit) {
        if (!enabled || Profile.name.length < 3 || !hasInternet(ctx.applicationContext)) { onDone(false); return }
        exec.execute {
            val body = JSONObject().apply {
                put("name", Profile.name)
                put("timestamp", System.currentTimeMillis() / 1000)
            }.toString()
            onDone(http("PUT", "${base()}/resp/$alertId/${Profile.uid}.json", body).first in 200..299)
        }
    }

    /**
     * ADMIN: scrie un marcaj „clearAt" (codul e verificat de regulile Firebase față de /config/adminCode)
     * și șterge alarmele din cloud. Fără cod corect, Firebase refuză scrierea.
     */
    fun adminClear(ctx: Context, code: String, onDone: (Boolean, String) -> Unit) {
        if (!enabled) { onDone(false, "Setează codul de grup și internetul."); return }
        if (!hasInternet(ctx.applicationContext)) { onDone(false, "Nu există internet."); return }
        exec.execute {
            val nowSec = System.currentTimeMillis() / 1000
            val body = JSONObject().apply { put("ts", nowSec); put("code", code) }.toString()
            val (c, _) = http("PUT", "${base()}/clearAt.json", body)
            if (c !in 200..299) {
                onDone(false, if (c == 401 || c == 403) "Cod admin greșit (sau lipsește /config/adminCode în Firebase)." else "Eroare de rețea ($c).")
                return@execute
            }
            val (_, t) = http("GET", "${base()}/sos.json")
            parseObj(t)?.let { o -> for (k in o.keys()) { http("DELETE", "${base()}/sos/$k.json"); http("DELETE", "${base()}/resp/$k.json") } }
            MeshBus.applyClear(nowSec)
            onDone(true, "Alarmele au fost șterse.")
        }
    }

    // ---------- citire ----------

    fun fetchAll(ctx: Context, myActiveId: Int, onResult: (Snapshot?) -> Unit) {
        if (!enabled || !hasInternet(ctx.applicationContext)) { onResult(null); return }
        exec.execute {
            val b = base()
            val now = System.currentTimeMillis()
            val nowSec = now / 1000

            val (c1, t1) = http("GET", "$b/sos.json")
            if (c1 !in 200..299) { onResult(null); return@execute }

            val alerts = ArrayList<MeshBus.Alert>()
            parseObj(t1)?.let { o ->
                for (k in o.keys()) {
                    val a = o.optJSONObject(k) ?: continue
                    val id = a.optInt("sosId", -1)
                    val lat = a.optDouble("lat", Double.NaN)
                    val lon = a.optDouble("lon", Double.NaN)
                    val ts = a.optLong("timestamp", 0L)
                    if (id < 0 || lat.isNaN() || lon.isNaN() || ts <= 0L) continue
                    val age = ((nowSec - ts) / 60).toInt().coerceAtLeast(0)
                    if (age >= MeshBus.MAX_AGE_MIN) continue
                    val t = MeshBus.TYPES.indexOf(a.optString("type")).coerceAtLeast(0)
                    alerts.add(MeshBus.Alert(id, a.optInt("hops"), t, lat, lon, a.optString("uid"), a.optString("name"), age, now, true))
                }
            }

            val people = ArrayList<MeshBus.Person>()
            val (_, t2) = http("GET", "$b/presence.json")
            parseObj(t2)?.let { o ->
                for (k in o.keys()) {
                    val p = o.optJSONObject(k) ?: continue
                    val lat = p.optDouble("lat", Double.NaN)
                    val lon = p.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    people.add(MeshBus.Person(k, p.optString("name"), lat, lon, p.optString("src", "apk"), p.optLong("timestamp", 0L)))
                }
            }

            val clearAt = http("GET", "$b/clearAt/ts.json").second.trim().toLongOrNull() ?: 0L

            val responders = ArrayList<String>()
            if (myActiveId > 0) {
                parseObj(http("GET", "$b/resp/$myActiveId.json").second)?.let { o ->
                    for (k in o.keys()) o.optJSONObject(k)?.let { responders.add("${it.optString("name")}#$k") }
                }
            }
            onResult(Snapshot(alerts, people, clearAt, responders))
        }
    }
}
