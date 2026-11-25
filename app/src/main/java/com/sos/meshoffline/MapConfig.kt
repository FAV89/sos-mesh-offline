package com.sos.meshoffline

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.io.File

/** Configurarea hărții, comună ecranului și serviciului de fundal (ca să folosească aceeași memorie cache). */
object MapConfig {
    /** Cheie CARTO gratuită (carto.com/basemaps/apikey). Goală = OpenStreetMap standard. */
    private const val CARTO_KEY = "cb1_5dkf_1_57d40daf6b5b373875d498da"
    private const val PREFS = "mapcfg"

    class State(val lat: Double, val lon: Double, val at: Long)

    fun configure(ctx: Context) {
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = "com.sos.meshoffline/1.0 (SOS Mesh Offline; Android)"
        cfg.osmdroidBasePath = File(ctx.applicationContext.filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")
    }

    fun tileSource(): OnlineTileSourceBase {
        if (CARTO_KEY.isBlank()) return TileSourceFactory.MAPNIK
        return object : XYTileSource(
            "CartoVoyagerKey", 0, 19, 256, ".png",
            arrayOf("https://basemaps.cartocdn.com/rastertiles/voyager/"),
            "© OpenStreetMap contributors, © CARTO"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                super.getTileURLString(pMapTileIndex) + "?key=" + CARTO_KEY
        }
    }

    /** Ultima zonă descărcată automat (centrul și momentul). at = 0 înseamnă „încă nu s-a descărcat nimic". */
    fun state(ctx: Context): State {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            p.getString("lat", "0")?.toDoubleOrNull() ?: 0.0,
            p.getString("lon", "0")?.toDoubleOrNull() ?: 0.0,
            p.getLong("at", 0L)
        )
    }

    fun saveState(ctx: Context, lat: Double, lon: Double) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", lat.toString()).putString("lon", lon.toString())
            .putLong("at", System.currentTimeMillis()).apply()
    }
}
