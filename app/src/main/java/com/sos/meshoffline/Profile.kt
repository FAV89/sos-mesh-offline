package com.sos.meshoffline

import android.content.Context
import java.security.SecureRandom

/** Profil local: poreclă (3-8), ID unic (4), grup curent (6-20), vizibilitate pe hartă și jetonul de proprietar al grupurilor create. */
object Profile {
    private const val PREFS = "profile"
    private const val UID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // 32 de caractere, fără 0/O/1/I
    private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    var name = ""
        private set
    var uid = ""
        private set
    var group = ""
        private set
    var presence = true
        private set
    /** Secret, rămâne doar pe telefon. Dovedește că ești proprietarul grupurilor create (max 3 per jeton). */
    var ownerToken = ""
        private set

    val cloudReady: Boolean get() = group.length >= 6

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        name = p.getString("name", "") ?: ""
        group = p.getString("group", "") ?: ""
        presence = p.getBoolean("presence", true)
        uid = p.getString("uid", "") ?: ""
        ownerToken = p.getString("token", "") ?: ""
        val r = SecureRandom()
        val e = p.edit()
        if (uid.length != 4) { uid = (1..4).map { UID_ALPHABET[r.nextInt(32)] }.joinToString(""); e.putString("uid", uid) }
        if (ownerToken.length < 20) { ownerToken = (1..24).map { TOKEN_ALPHABET[r.nextInt(TOKEN_ALPHABET.length)] }.joinToString(""); e.putString("token", ownerToken) }
        e.apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setName(ctx: Context, n: String) {
        name = sanitizeName(n)
        prefs(ctx).edit().putString("name", name).apply()
    }

    fun setGroup(ctx: Context, g: String) {
        group = sanitizeGroup(g)
        prefs(ctx).edit().putString("group", group).apply()
    }

    fun setPresence(ctx: Context, v: Boolean) {
        presence = v
        prefs(ctx).edit().putBoolean("presence", v).apply()
    }

    /** Doar litere/cifre ASCII (1 byte pe caracter în pachetul Bluetooth), max 8. */
    fun sanitizeName(s: String): String =
        s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(8)

    fun sanitizeGroup(s: String): String =
        s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(20)

    /** Cod de grup propus automat (8 caractere). */
    fun randomGroupCode(): String {
        val r = SecureRandom()
        return (1..8).map { UID_ALPHABET[r.nextInt(32)] }.joinToString("")
    }

    fun label(): String = "$name#$uid"

    /** 4 caractere x 5 biți = 20 de biți = 3 bytes. */
    fun uidToBytes(u: String): ByteArray {
        var v = 0
        for (c in u.padEnd(4, '2').take(4)) v = (v shl 5) or UID_ALPHABET.indexOf(c).coerceAtLeast(0)
        return byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    fun bytesToUid(b: ByteArray): String {
        val v = ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        val sb = StringBuilder()
        for (i in 3 downTo 0) sb.append(UID_ALPHABET[(v shr (5 * i)) and 31])
        return sb.toString()
    }
}
