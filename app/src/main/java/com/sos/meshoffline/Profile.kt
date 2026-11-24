package com.sos.meshoffline

import android.content.Context
import java.security.SecureRandom

/** Profilul utilizatorului: poreclă (3-8 caractere) + ID unic de 4 caractere generat o singură dată. */
object Profile {
    private const val PREFS = "profile"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // 32 de caractere, fără 0/O/1/I

    var name = ""
        private set
    var uid = ""
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        name = p.getString("name", "") ?: ""
        uid = p.getString("uid", "") ?: ""
        if (uid.length != 4) {
            val r = SecureRandom()
            uid = (1..4).map { ALPHABET[r.nextInt(32)] }.joinToString("")
            p.edit().putString("uid", uid).apply()
        }
    }

    fun setName(ctx: Context, n: String) {
        name = sanitizeName(n)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("name", name).apply()
    }

    /** Doar litere/cifre ASCII (1 byte pe caracter în pachetul Bluetooth), max 8. */
    fun sanitizeName(s: String): String =
        s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(8)

    fun label(): String = "$name#$uid"

    /** 4 caractere x 5 biți = 20 de biți = 3 bytes. */
    fun uidToBytes(u: String): ByteArray {
        var v = 0
        for (c in u.padEnd(4, '2').take(4)) v = (v shl 5) or ALPHABET.indexOf(c).coerceAtLeast(0)
        return byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    fun bytesToUid(b: ByteArray): String {
        val v = ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        val sb = StringBuilder()
        for (i in 3 downTo 0) sb.append(ALPHABET[(v shr (5 * i)) and 31])
        return sb.toString()
    }
}
