package com.me2.android.location

import com.me2.android.data.LocalLocation
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Última ubicación aproximada del teléfono (ACCESS_COARSE_LOCATION), guardada localmente. */
data class DeviceFix(val lat: Double, val lon: Double, val city: String?, val timeZone: String?, val fixedAt: Long) {
    fun toLocalLocation() = LocalLocation(city, lat, lon, timeZone)

    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).put("fixedAt", fixedAt).apply {
        city?.let { put("ciudad", it) }
        timeZone?.let { put("zonaHoraria", it) }
    }

    companion object {
        fun fromJson(json: JSONObject?): DeviceFix? {
            json ?: return null
            val lat = json.optDouble("lat").takeIf { !it.isNaN() && it in -90.0..90.0 } ?: return null
            val lon = json.optDouble("lon").takeIf { !it.isNaN() && it in -180.0..180.0 } ?: return null
            val city = json.optString("ciudad").trim().takeIf { it.isNotEmpty() && it != "null" }?.take(80)
            val tz = json.optString("zonaHoraria").trim().takeIf { it.isNotEmpty() && it != "null" }?.take(64)
            return DeviceFix(lat, lon, city, tz, json.optLong("fixedAt", 0L))
        }
    }
}

/**
 * Reglas puras (testeables): cuándo refrescar la ubicación del teléfono y cuál usar para clima/hora/noticias.
 * - Refresco: si no hay fix, si tiene más de 30 min o si la lectura nueva está a más de 5 km.
 * - Precedencia: el fix del teléfono "fresco" (≤ 6 h) gana sobre la ciudad contada en el chat; si no está fresco,
 *   manda la ciudad del chat; sin ciudad del chat, el último fix conocido.
 */
object DeviceLocationPolicy {
    const val REFRESH_AFTER_MS = 30 * 60 * 1000L
    const val MOVED_KM = 5.0
    const val FRESH_MS = 6 * 60 * 60 * 1000L

    fun shouldUpdate(last: DeviceFix?, candidateLat: Double, candidateLon: Double, now: Long): Boolean =
        last == null || now - last.fixedAt >= REFRESH_AFTER_MS || now < last.fixedAt ||
            distanceKm(last.lat, last.lon, candidateLat, candidateLon) > MOVED_KM

    fun isFresh(fix: DeviceFix?, now: Long): Boolean = fix != null && now >= fix.fixedAt && now - fix.fixedAt <= FRESH_MS

    fun effective(device: DeviceFix?, chat: LocalLocation?, now: Long): LocalLocation? = when {
        device != null && isFresh(device, now) -> device.toLocalLocation()
        chat != null -> chat
        else -> device?.toLocalLocation()
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
