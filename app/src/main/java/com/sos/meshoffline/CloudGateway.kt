package com.sos.meshoffline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Legătura cu Firebase Realtime Database (REST).
 *  /g/{GRUP}/...     datele grupului (alarme, prezență, răspunsuri, marcaj admin)
 *  /meta/{GRUP}/...  registrul grupurilor (creat, proprietar, șters)
 *  /owners/{JETON}/groups/{GRUP}  grupurile create de un proprietar (max 3, impus de regulile Firebase)
 *  /adminCheck/{COD}  se poate citi doar dacă COD = /config/adminCode (verificare admin fără a stoca nimic)
 */
object CloudGateway {
    const val DB_URL = "https://sos-offline-mash-default-rtdb.europe-west1.firebasedatabase.app/"

    const val GROUP_OK = 0
    const val GROUP_MISSING = 1
    const val GROUP_DELETED = 2
    const val GROUP_ERROR = 3
    const val MAX_OWNED_GROUPS = 3

    class Snapshot(
        val alerts: List<MeshBus.Alert>,
        val people: List<MeshBus.Person>,
        val clearAt: Long,
        val responders: List<String>,
        val groupDeleted: Boolean
    )

    private val exec = Executors.newSingleThreadExecutor()

    val enabled: Boolean get() = DB_URL.isNotBlank() && Profile.cloudReady

    private fun root() = DB_URL.trimEnd('/')
    private fun base() = "${root()}/g/${Profile.group}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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

    private fun isNull(t: String) = t.isBlank() || t.trim() == "null"
    private fun parseObj(t: String): JSONObject? = try { if (isNull(t)) null else JSONObject(t) } catch (_: Exception) { null }

    /** Șterge răspunsurile „vin spre tine" ale unei alarme (frunză cu frunză, cum cer regulile). */
    private fun delResp(b: String, aid: String) {
        parseObj(http("GET", "$b/resp/$aid.json").second)?.let { for (k in it.keys()) http("DELETE", "$b/resp/$aid/$k.json") }
    }

    /** Golește alarmele și prezența unui grup. */
    private fun purge(code: String) {
        val b = "${root()}/g/$code"
        parseObj(http("GET", "$b/sos.json").second)?.let { o -> for (k in o.keys()) { http("DELETE", "$b/sos/$k.json"); delResp(b, k) } }
        parseObj(http("GET", "$b/presence.json").second)?.let { o -> for (k in o.keys()) http("DELETE", "$b/presence/$k.json") }
    }

    // ---------- scriere în grupul curent ----------

    fun uploadAlert(
        ctx: Context, id: Int, type: Int, lat: Double, lon: Double, hops: Int,
        name: String, uid: String, tsSec: Long, onDone: (Boolean) -> Unit
    ) {
        if (!enabled || !hasInternet(ctx.applicationContext)) { onDone(false); return }
        val b = base()
        exec.execute {
            val body = JSONObject().apply {
                put("sosId", id)
                put("type", MeshBus.TYPES.getOrElse(type) { MeshBus.TYPES[0] })
                put("lat", lat); put("lon", lon); put("hops", hops)
                put("name", name); put("uid", uid)
                put("timestamp", tsSec)
            }.toString()
            onDone(http("PUT", "$b/sos/$id.json", body).first in 200..299)
        }
    }

    fun deleteAlert(ctx: Context, id: Int) {
        if (!enabled || !hasInternet(ctx.applicationContext)) return
        val b = base()
        exec.execute {
            http("DELETE", "$b/sos/$id.json")
            delResp(b, id.toString())
        }
    }

    fun uploadPresence(ctx: Context, onDone: (Boolean) -> Unit) {
        if (!enabled || !Profile.sharePresence || Profile.name.length < 3 || MeshBus.myLat == 0.0 || !hasInternet(ctx.applicationContext)) {
            onDone(false); return
        }
        val b = base()
        val body = JSONObject().apply {
            put("name", Profile.name); put("uid", Profile.uid)
            put("lat", MeshBus.myLat); put("lon", MeshBus.myLon)
            put("src", "apk")
            put("timestamp", System.currentTimeMillis() / 1000)
        }.toString()
        exec.execute { onDone(http("PUT", "$b/presence/${Profile.uid}.json", body).first in 200..299) }
    }

    /** Apelat ÎNAINTE de schimbarea grupului (adresa se calculează acum, nu mai târziu). */
    fun removePresence(ctx: Context) {
        if (!enabled || !hasInternet(ctx.applicationContext)) return
        val url = "${base()}/presence/${Profile.uid}.json"
        exec.execute { http("DELETE", url) }
    }

    /** „Vin spre tine": cel care răspunde se înscrie la alarmă; victima vede numele lui. */
    fun respond(ctx: Context, alertId: Int, onDone: (Boolean) -> Unit) {
        if (!enabled || Profile.name.length < 3 || !hasInternet(ctx.applicationContext)) { onDone(false); return }
        val b = base()
        val body = JSONObject().apply {
            put("name", Profile.name)
            put("timestamp", System.currentTimeMillis() / 1000)
        }.toString()
        exec.execute { onDone(http("PUT", "$b/resp/$alertId/${Profile.uid}.json", body).first in 200..299) }
    }

    // ---------- grupuri ----------

    /** Verifică dacă un cod de grup există și nu e șters. */
    fun checkGroup(ctx: Context, code: String, onResult: (Int) -> Unit) {
        if (!hasInternet(ctx.applicationContext)) { onResult(GROUP_ERROR); return }
        val r = root()
        exec.execute {
            val (c1, t1) = http("GET", "$r/meta/$code/created.json")
            if (c1 !in 200..299) { onResult(GROUP_ERROR); return@execute }
            if (isNull(t1)) { onResult(GROUP_MISSING); return@execute }
            val (c2, t2) = http("GET", "$r/meta/$code/deleted/ts.json")
            onResult(if (c2 in 200..299 && !isNull(t2)) GROUP_DELETED else GROUP_OK)
        }
    }

    /** Grupurile create de mine: lista de (identificator, nume). */
    fun listOwned(ctx: Context, onResult: (List<Pair<String, String>>?) -> Unit) {
        if (!hasInternet(ctx.applicationContext)) { onResult(null); return }
        val r = root(); val token = Profile.ownerToken
        exec.execute {
            val (c, t) = http("GET", "$r/owners/$token/groups.json")
            if (c !in 200..299) { onResult(null); return@execute }
            val o = parseObj(t)
            val out = ArrayList<Pair<String, String>>()
            if (o != null) for (k in o.keys()) o.optJSONObject(k)?.let { out.add(it.optString("id") to it.optString("name")) }
            onResult(out)
        }
    }

    /** Creează un grup privat (nume min 2 + parolă min 6). Regulile Firebase permit maximum 3 grupuri per proprietar. */
    fun createGroup(ctx: Context, name: String, password: String, onDone: (Boolean, String, String) -> Unit) {
        if (!hasInternet(ctx.applicationContext)) { onDone(false, "Nu există internet.", ""); return }
        val r = root(); val token = Profile.ownerToken
        val gid = Profile.groupId(name, password)
        exec.execute {
            val (c0, t0) = http("GET", "$r/owners/$token/groups.json")
            if (c0 !in 200..299) { onDone(false, "Eroare de rețea ($c0).", ""); return@execute }
            // limita de 3: proprietarul are 3 sloturi (1, 2, 3); un grup nou ocupă primul slot liber
            val used = parseObj(t0)
            val slot = listOf("1", "2", "3").firstOrNull { used?.has(it) != true }
            if (slot == null) {
                onDone(false, "Ai deja $MAX_OWNED_GROUPS grupuri create. Șterge unul ca să creezi altul.", ""); return@execute
            }
            val entry = JSONObject().apply { put("id", gid); put("name", name) }.toString()
            if (http("PUT", "$r/owners/$token/groups/$slot.json", entry).first !in 200..299) {
                onDone(false, "Nu am putut înregistra grupul.", ""); return@execute
            }
            val created = http("PUT", "$r/meta/$gid/created.json", (System.currentTimeMillis() / 1000).toString())
            if (created.first !in 200..299) {
                http("DELETE", "$r/owners/$token/groups/$slot.json")
                onDone(false, "Există deja un grup cu acest nume și această parolă. Alege altă parolă.", ""); return@execute
            }
            http("PUT", "$r/meta/$gid/owner.json", JSONObject.quote(token))
            http("PUT", "$r/meta/$gid/name.json", JSONObject.quote(name))
            onDone(true, "Grup creat.", gid)
        }
    }

    /** Proprietarul șterge grupul: golește datele, apoi îl marchează „șters" (cu jetonul lui). */
    fun deleteOwnGroup(ctx: Context, code: String, onDone: (Boolean, String) -> Unit) {
        if (!hasInternet(ctx.applicationContext)) { onDone(false, "Nu există internet."); return }
        val r = root(); val token = Profile.ownerToken
        exec.execute {
            purge(code)
            val body = JSONObject().apply { put("ts", System.currentTimeMillis() / 1000); put("code", token) }.toString()
            if (http("PUT", "$r/meta/$code/deleted.json", body).first in 200..299) {
                val mine = parseObj(http("GET", "$r/owners/$token/groups.json").second)
                val slot = mine?.keys()?.asSequence()?.firstOrNull { mine.optJSONObject(it)?.optString("id") == code }
                if (slot != null) http("DELETE", "$r/owners/$token/groups/$slot.json")
                onDone(true, "Grupul a fost șters.")
            } else onDone(false, "Nu ești proprietarul acestui grup.")
        }
    }

    // ---------- admin ----------

    /** Întoarce codul HTTP: 2xx = cod corect, 401/403 = cod greșit, -1 = fără rețea. Nu stochează nimic. */
    fun adminVerify(ctx: Context, code: String, onResult: (Int) -> Unit) {
        if (!hasInternet(ctx.applicationContext)) { onResult(-1); return }
        val r = root()
        exec.execute { onResult(http("GET", "$r/adminCheck/${enc(code)}.json").first) }
    }

    /** ADMIN: șterge orice grup, după cod. */
    fun adminDeleteGroup(ctx: Context, adminCode: String, code: String, onDone: (Boolean, String) -> Unit) {
        if (!hasInternet(ctx.applicationContext)) { onDone(false, "Nu există internet."); return }
        val r = root()
        exec.execute {
            if (http("GET", "$r/adminCheck/${enc(adminCode)}.json").first !in 200..299) { onDone(false, "Cod admin greșit."); return@execute }
            purge(code)
            val body = JSONObject().apply { put("ts", System.currentTimeMillis() / 1000); put("code", adminCode) }.toString()
            onDone(http("PUT", "$r/meta/$code/deleted.json", body).first in 200..299, "Grupul $code a fost șters.")
        }
    }

    /** ADMIN: șterge toate alarmele din grupul curent (marcaj „clearAt" + ștergere). */
    fun adminClear(ctx: Context, code: String, onDone: (Boolean, String) -> Unit) {
        if (!enabled) { onDone(false, "Intră întâi într-un grup."); return }
        if (!hasInternet(ctx.applicationContext)) { onDone(false, "Nu există internet."); return }
        val b = base()
        exec.execute {
            val nowSec = System.currentTimeMillis() / 1000
            val body = JSONObject().apply { put("ts", nowSec); put("code", code) }.toString()
            val (c, _) = http("PUT", "$b/clearAt.json", body)
            if (c !in 200..299) {
                onDone(false, if (c == 401 || c == 403) "Cod admin greșit (sau lipsește /config/adminCode în Firebase)." else "Eroare de rețea ($c).")
                return@execute
            }
            parseObj(http("GET", "$b/sos.json").second)?.let { o -> for (k in o.keys()) { http("DELETE", "$b/sos/$k.json"); delResp(b, k) } }
            MeshBus.applyClear(nowSec)
            onDone(true, "Alarmele au fost șterse.")
        }
    }

    // ---------- citire ----------

    fun fetchAll(ctx: Context, myActiveId: Int, onResult: (Snapshot?) -> Unit) {
        if (!enabled || !hasInternet(ctx.applicationContext)) { onResult(null); return }
        val b = base()
        val metaDeleted = "${root()}/meta/${Profile.group}/deleted/ts.json"
        val isGlobal = Profile.isGlobal
        exec.execute {
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
            parseObj(http("GET", "$b/presence.json").second)?.let { o ->
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
            val deleted = if (isGlobal) false else http("GET", metaDeleted).let { it.first in 200..299 && !isNull(it.second) }
            onResult(Snapshot(alerts, people, clearAt, responders, deleted))
        }
    }
}
