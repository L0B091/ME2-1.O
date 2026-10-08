package com.me2.android.time

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import java.util.Calendar
import java.util.TimeZone

/**
 * Reloj propio de ME2: la hora del celular NO es la fuente de verdad.
 *
 * - Fuente confiable: la hora del servidor de ME2 (header `X-ME2-Server-Time` en cada respuesta, o `Date` como
 *   respaldo), compensando la mitad del tiempo de ida y vuelta.
 * - Entre sincronizaciones avanza con `SystemClock.elapsedRealtime()` (monotónico: no lo mueve un cambio manual de
 *   hora ni de zona del teléfono). Tras un reinicio (otro BOOT_COUNT) usa el último desfase conocido (funciona sin red).
 * - Zona fija de ME2: America/Argentina/Buenos_Aires (no la del teléfono) para "hoy", "mañana", HH:mm, descanso, etc.
 * - AlarmManager dispara con el reloj de pared del teléfono (RTC): [toDeviceWall] convierte un instante ME2 a ese reloj.
 */
class Me2ClockState(
    private val prefs: SharedPreferences?,
    private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val bootCount: () -> Int = { -1 }
) {
    @Volatile private var serverAtSync = prefs?.getLong(K_SERVER, 0L) ?: 0L
    @Volatile private var elapsedAtSync = prefs?.getLong(K_ELAPSED, 0L) ?: 0L
    @Volatile private var bootAtSync = prefs?.getInt(K_BOOT, Int.MIN_VALUE) ?: Int.MIN_VALUE
    @Volatile private var offset = prefs?.getLong(K_OFFSET, 0L) ?: 0L

    val isSynced: Boolean get() = serverAtSync > 0L

    /** Ahora según ME2 (epoch ms real, corregido contra el servidor). */
    fun now(): Long {
        if (serverAtSync > 0L) {
            val e = elapsed()
            val sameBoot = bootAtSync == bootCount() && e >= elapsedAtSync
            if (sameBoot) return serverAtSync + (e - elapsedAtSync)
            return wall() + offset
        }
        return wall()
    }

    /** Desfase actual (ME2 − reloj del teléfono). Positivo: el teléfono atrasa. */
    fun offsetMillis(): Long = now() - wall()

    /** Instante ME2 → valor del reloj de pared del teléfono (para AlarmManager RTC_WAKEUP / setAlarmClock). */
    fun toDeviceWall(me2Millis: Long): Long = me2Millis - offsetMillis()

    /**
     * Muestra del servidor: [serverMillis] leída en la respuesta; [sentElapsed]/[receivedElapsed] = elapsedRealtime
     * antes de enviar y al recibir. Devuelve cuánto se corrigió el reloj ME2 (ms, valor absoluto).
     */
    fun onServerTime(serverMillis: Long, sentElapsed: Long, receivedElapsed: Long, resolutionMs: Long = 0L): Long {
        val rtt = receivedElapsed - sentElapsed
        if (serverMillis <= 0L || rtt < 0L || rtt > MAX_RTT_MS) return 0L
        val before = now()
        val estimate = serverMillis + rtt / 2 + resolutionMs / 2
        val drift = estimate - (before + (elapsed() - receivedElapsed).coerceAtLeast(0L))
        serverAtSync = estimate
        elapsedAtSync = receivedElapsed
        bootAtSync = bootCount()
        offset = estimate - (wall() - (elapsed() - receivedElapsed).coerceAtLeast(0L))
        prefs?.edit()?.putLong(K_SERVER, serverAtSync)?.putLong(K_ELAPSED, elapsedAtSync)?.putInt(K_BOOT, bootAtSync)
            ?.putLong(K_OFFSET, offset)?.apply()
        return kotlin.math.abs(drift)
    }

    companion object {
        private const val K_SERVER = "server_at_sync"
        private const val K_ELAPSED = "elapsed_at_sync"
        private const val K_BOOT = "boot_at_sync"
        private const val K_OFFSET = "offset_ms"
        /** Muestras con más ida y vuelta que esto se descartan (imprecisas). */
        const val MAX_RTT_MS = 20_000L
    }
}

object Me2Clock {
    const val ZONE_ID = "America/Argentina/Buenos_Aires"
    val ZONE: TimeZone get() = TimeZone.getTimeZone(ZONE_ID)
    /** Corrección a partir de la cual se re-arman alarmas/recordatorios/iniciativa (el instante en el teléfono cambió). */
    const val RESCHEDULE_THRESHOLD_MS = 30_000L

    @Volatile private var state: Me2ClockState = Me2ClockState(null)
    @Volatile private var onCorrected: (() -> Unit)? = null

    fun init(context: Context, onCorrected: (() -> Unit)? = null) {
        val app = context.applicationContext
        state = Me2ClockState(
            app.getSharedPreferences("me2_clock", Context.MODE_PRIVATE),
            bootCount = { runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1) }
        )
        this.onCorrected = onCorrected
    }

    /** Tests: reloj controlado. */
    fun install(newState: Me2ClockState, onCorrected: (() -> Unit)? = null) { state = newState; this.onCorrected = onCorrected }

    fun now(): Long = state.now()
    fun offsetMillis(): Long = state.offsetMillis()
    fun toDeviceWall(me2Millis: Long): Long = state.toDeviceWall(me2Millis)

    /** Calendario en la zona de ME2 posicionado en [atMillis] (por defecto, ahora ME2). */
    fun calendar(atMillis: Long = now()): Calendar = Calendar.getInstance(ZONE).apply { timeInMillis = atMillis }

    fun onServerTime(serverMillis: Long, sentElapsed: Long, receivedElapsed: Long, resolutionMs: Long = 0L) {
        val drift = runCatching { state.onServerTime(serverMillis, sentElapsed, receivedElapsed, resolutionMs) }.getOrDefault(0L)
        if (drift >= RESCHEDULE_THRESHOLD_MS) runCatching { onCorrected?.invoke() }
    }
}
