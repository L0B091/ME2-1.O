package com.me2.android.notifications

import android.content.Context
import android.util.Log
import com.me2.android.time.Me2Clock
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Rastro de diagnóstico del despertador en el teléfono (sin texto del usuario): acción recibida, instante armado,
 * permisos, disparo del receptor, notificación publicada o bloqueada y errores. Se guarda local (últimos [MAX]) y viaja
 * al backend en el próximo /chat (contexto.diagAlarmas), que lo escribe en su log. Nunca rompe el flujo de la alarma.
 */
object Me2AlarmDiag {
    private const val PREFS = "me2_alarm_diag"
    private const val KEY = "eventos"
    const val MAX = 40
    private val lock = Any()

    fun hhmmss(millis: Long): String =
        SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).apply { timeZone = Me2Clock.ZONE }.format(millis)

    fun log(context: Context, evento: String) {
        runCatching {
            val linea = "${hhmmss(Me2Clock.now())} ${evento.take(220)}"
            Log.i("Me2AlarmDiag", linea)
            synchronized(lock) {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
                arr.put(linea)
                val desde = maxOf(0, arr.length() - MAX)
                val out = JSONArray().apply { for (i in desde until arr.length()) put(arr.optString(i)) }
                prefs.edit().putString(KEY, out.toString()).apply()
            }
        }
    }

    /** Copia de lo pendiente de enviar (null si no hay nada). */
    fun pending(context: Context): JSONArray? = runCatching {
        synchronized(lock) {
            JSONArray(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        }.takeIf { it.length() > 0 }
    }.getOrNull()

    /** Tras un /chat exitoso: se descartan los [count] primeros (los que se enviaron). */
    fun ack(context: Context, count: Int) {
        if (count <= 0) return
        runCatching {
            synchronized(lock) {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val arr = JSONArray(prefs.getString(KEY, "[]"))
                val out = JSONArray().apply { for (i in minOf(count, arr.length()) until arr.length()) put(arr.optString(i)) }
                prefs.edit().putString(KEY, out.toString()).apply()
            }
        }
    }
}
