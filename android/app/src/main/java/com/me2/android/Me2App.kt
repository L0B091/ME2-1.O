package com.me2.android

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.util.Log
import com.me2.android.data.Me2MemoryDatabase
import com.me2.android.data.SecurePreferences
import kotlin.concurrent.thread

/**
 * Arranque liviano: el trabajo pesado (Keystore/EncryptedSharedPreferences y apertura de Room) se precalienta en un
 * hilo de fondo apenas arranca el proceso, para que Login/Main no lo hagan en el hilo principal (ANR en arranque en
 * frío). En debug, StrictMode registra en logcat cualquier lectura/escritura de disco o red en el hilo principal.
 */
class Me2App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Reloj propio de ME2 (hora del servidor + elapsedRealtime). Si una sincronización corrige el reloj, el instante
        // de cada alarma/recordatorio/iniciativa en el reloj del teléfono cambió: se re-arma todo desde disco.
        com.me2.android.time.Me2Clock.init(this) { thread(name = "me2-clock-rearm") { rearmAll() } }
        val underTest = Build.FINGERPRINT == "robolectric"
        if (BuildConfig.DEBUG && !underTest) enableStrictMode()
        if (!underTest) prewarm()
        // Ante un 401 la sesión se recupera en silencio (nunca se vuelve a pedir login salvo cierre explícito).
        com.me2.android.net.Me2SessionRecovery.renewer = com.me2.android.auth.GoogleSilentRenewer(this)
    }

    private fun rearmAll() {
        runCatching { com.me2.android.notifications.Me2AlarmScheduler(this).restoreAll() }
            .onFailure { Log.w(TAG, "re-armado de alarmas falló: ${it.javaClass.simpleName}") }
        runCatching {
            val session = com.me2.android.data.SessionStorage(this).loadUser() ?: return@runCatching
            if (com.me2.android.notifications.Me2InitiativeStore(this).isEnabled(session.id)) {
                com.me2.android.notifications.Me2InitiativeTimer(this).restore(session.id)
            }
        }.onFailure { Log.w(TAG, "re-armado de iniciativa falló: ${it.javaClass.simpleName}") }
    }

    private fun prewarm() {
        thread(name = "me2-prewarm", priority = Thread.NORM_PRIORITY) {
            val started = System.currentTimeMillis()
            runCatching { SecurePreferences.open(this, SESSION_PREFS, listOf("me2_session")) }
            runCatching { SecurePreferences.open(this, ALARM_PREFS) }
            runCatching { com.me2.android.media.MediaLibrary(this).recursos() }
                .onFailure { Log.w(TAG, "prewarm MediaLibrary falló", it) }
            runCatching { Me2MemoryDatabase.getInstance(this).openHelper.writableDatabase }
                .onFailure { Log.w(TAG, "prewarm Room falló", it) }
            Log.i(TAG, "prewarm listo en ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build()
        )
    }

    companion object {
        private const val TAG = "Me2App"
        const val SESSION_PREFS = "me2_session_secure"
        const val ALARM_PREFS = "me2_alarm_store"
    }
}
