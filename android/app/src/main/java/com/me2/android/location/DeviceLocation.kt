package com.me2.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import com.me2.android.data.SecurePreferences
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fix del teléfono cifrado en disco (EncryptedSharedPreferences): la ubicación es dato sensible. */
class DeviceLocationStore(context: Context) {
    private val prefs = SecurePreferences.open(context.applicationContext, "me2_device_location")

    fun load(): DeviceFix? = runCatching { DeviceFix.fromJson(prefs.getString(KEY, null)?.let(::JSONObject)) }.getOrNull()
    fun save(fix: DeviceFix) { prefs.edit().putString(KEY, fix.toJson().toString()).apply() }

    private companion object { const val KEY = "fix" }
}

/**
 * Ubicación aproximada con LocationManager (sin Play Services). Todas las llamadas son bloqueantes: usar SIEMPRE
 * desde un hilo de fondo (Dispatchers.IO). Sin permiso devuelve null y la app sigue con la ciudad contada en el chat.
 */
class DeviceLocationProvider(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Refresca si corresponde (30 min / 5 km). Devuelve el fix nuevo o null si no cambió / no hay permiso. */
    fun refreshIfNeeded(store: DeviceLocationStore = DeviceLocationStore(app), now: Long = System.currentTimeMillis()): DeviceFix? {
        if (!hasPermission()) return null
        val last = store.load()
        val reading = readCoarse() ?: return null
        if (!DeviceLocationPolicy.shouldUpdate(last, reading.latitude, reading.longitude, now)) return null
        val city = reverseCity(reading.latitude, reading.longitude)
            ?: last?.takeIf { DeviceLocationPolicy.distanceKm(it.lat, it.lon, reading.latitude, reading.longitude) <= DeviceLocationPolicy.MOVED_KM }?.city
        // Zona horaria del sistema: Android la actualiza sola al viajar (red/operador).
        val fix = DeviceFix(reading.latitude, reading.longitude, city, TimeZone.getDefault().id, now)
        store.save(fix)
        Log.i(TAG, "ubicación del teléfono actualizada")
        return fix
    }

    @SuppressLint("MissingPermission")
    private fun readCoarse(): Location? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) || it == LocationManager.PASSIVE_PROVIDER }.getOrDefault(false) }
        val lastKnown = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val recent = lastKnown?.takeIf { System.currentTimeMillis() - it.time <= DeviceLocationPolicy.REFRESH_AFTER_MS }
        if (recent != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return recent ?: lastKnown
        val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: return lastKnown
        // Lectura puntual (API 30+), con tope de 10 s; si no llega, el último conocido.
        // Executor directo (nunca se apaga: el sistema puede entregar null más tarde por su propio timeout) y
        // cancelación explícita si no llega a tiempo.
        val latch = CountDownLatch(1)
        val cancel = CancellationSignal()
        val current = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        runCatching {
            lm.getCurrentLocation(provider, cancel, { it.run() }) { loc -> current.set(loc); latch.countDown() }
            if (!latch.await(10, TimeUnit.SECONDS)) cancel.cancel()
        }
        return current.get() ?: lastKnown
    }

    @Suppress("DEPRECATION")
    private fun reverseCity(lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(app, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }?.trim()?.take(80)
    }.getOrNull()

    private companion object { const val TAG = "Me2Location" }
}
