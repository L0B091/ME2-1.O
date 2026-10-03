package com.me2.android.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.me2.android.net.AlarmRecord
import com.me2.android.net.Me2BackendClient
import com.me2.android.data.SessionStorage
import com.me2.android.offline.OfflinePhraseBank
import com.me2.android.offline.OfflinePhrases
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar
import java.util.UUID

/**
 * Alarmas y recordatorios 100 % locales: el estado (intento 1/2/3, próximo disparo, respondida, sync) vive en
 * [Me2AlarmStore] (disco) y cada intento se arma con AlarmManager (setAlarmClock / setExactAndAllowWhileIdle).
 * Nada depende de timers en memoria ni del backend: sobrevive muerte del proceso y, vía [Me2BootReceiver], reinicios
 * y cambios de hora. El backend solo se sincroniza cuando hay red ([Me2OfflineSync]).
 */
class Me2AlarmScheduler(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isOnline: () -> Boolean = { runCatching { Me2BackendClient().let { it.isConfigured() && it.isOnline(context) } }.getOrDefault(false) }
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = Me2AlarmStore(context)

    /** Alarma que viene del backend (o creada localmente). Conserva el progreso si es la misma alarma/horario. */
    fun schedule(record: AlarmRecord, baseTriggerAtMillis: Long? = null): StoredAlarmRecord {
        val triggerAtMillis = baseTriggerAtMillis ?: nextTriggerMillis(record.hour)
        // Una alarma creada en el teléfono y ya subida vuelve del backend con su id remoto: misma alarma, sin duplicar.
        val existing = store.find(record.id) ?: store.listAll().firstOrNull { it.remoteId == record.id }
        if (existing != null && existing.answered) return existing
        val stored = if (existing != null && existing.triggerAtMillis == triggerAtMillis) {
            existing.copy(title = record.title, message = record.message, dispatchPlan = record.dispatchPlan.ifEmpty { existing.dispatchPlan })
        } else {
            StoredAlarmRecord(
                id = record.id, userId = record.userId, hour = record.hour, title = record.title, message = record.message,
                state = record.state, triggerAtMillis = triggerAtMillis, dispatchPlan = record.dispatchPlan,
                remoteId = existing?.remoteId ?: record.id, syncState = existing?.syncState ?: StoredAlarmRecord.SYNC_OK
            )
        }
        store.upsert(stored)
        return arm(stored)
    }

    /** Alarma pedida en el chat con memoria local primaria: vive en el teléfono y se sube al backend al haber red. */
    fun createLocalAlarm(userId: String, hour: String, title: String): StoredAlarmRecord {
        val record = StoredAlarmRecord(
            id = "local-${UUID.randomUUID()}", userId = userId, hour = hour, title = title, message = "",
            state = "ACTIVE", triggerAtMillis = nextTriggerMillis(hour), dispatchPlan = emptyList(),
            remoteId = null, syncState = StoredAlarmRecord.SYNC_CREATE
        )
        store.upsert(record)
        return arm(record)
    }

    /** Recordatorio/evento de agenda guardado localmente (título = texto del usuario). Un solo intento. */
    fun scheduleReminder(userId: String, remoteId: String?, title: String, atMillis: Long): StoredAlarmRecord? {
        if (atMillis <= clock() - AlarmEscalation.STALE_MS) return null
        val id = "evt-${remoteId ?: UUID.randomUUID()}"
        val existing = store.find(id)
        val record = if (existing != null && existing.triggerAtMillis == atMillis) existing.copy(title = title)
        else StoredAlarmRecord(
            id = id, userId = userId, hour = SimpleDateFormat("HH:mm", Locale.ROOT).format(atMillis), title = title, message = "", state = "ACTIVE",
            triggerAtMillis = atMillis, dispatchPlan = emptyList(), kind = StoredAlarmRecord.KIND_REMINDER,
            remoteId = remoteId, syncState = StoredAlarmRecord.SYNC_OK
        )
        store.upsert(record)
        return arm(record)
    }

    /** Aplica la política al registro persistido: arma el próximo intento, dispara el vencido o archiva. */
    fun arm(record: StoredAlarmRecord): StoredAlarmRecord {
        return when (val plan = AlarmEscalation.plan(record, clock())) {
            is AlarmEscalation.Plan.ScheduleAt -> {
                setExact(record, plan.stage, plan.atMillis)
                store.update(record.id) { it.copy(nextFireAtMillis = plan.atMillis) } ?: record
            }
            is AlarmEscalation.Plan.FireNow -> fire(record.id, plan.stage) ?: record
            AlarmEscalation.Plan.AwaitingAnswer -> {
                cancelPendingIntent(record.id)
                store.update(record.id) { it.copy(nextFireAtMillis = 0L) } ?: record
            }
            AlarmEscalation.Plan.Expired, AlarmEscalation.Plan.Done -> {
                cancelPendingIntent(record.id)
                // Se conserva solo si falta sincronizar algo con el backend ([Me2OfflineSync] lo archiva después).
                if (record.syncState == StoredAlarmRecord.SYNC_OK) store.remove(record.id)
                else store.update(record.id) { it.copy(nextFireAtMillis = 0L) }
                record.copy(nextFireAtMillis = 0L)
            }
        }
    }

    /**
     * Disparo de un intento (desde [Me2AlarmReceiver] o por recuperación). Idempotente: si ya se respondió o el
     * intento ya salió, no hace nada. Persiste el intento ANTES de mostrar y luego arma el siguiente.
     */
    fun fire(alarmId: String, stage: Int, notify: (StoredAlarmRecord, AlarmDispatchStageSpec) -> Unit = ::notifyStage): StoredAlarmRecord? {
        val current = store.find(alarmId) ?: return null
        if (current.answered || current.firedStage >= stage) return current
        val updated = store.update(alarmId) { it.copy(firedStage = stage, nextFireAtMillis = 0L) } ?: return null
        runCatching { notify(updated, specFor(updated, stage)) }.onFailure { Log.w(TAG, "notify failed: ${it.javaClass.simpleName}") }
        return arm(updated)
    }

    /** INPUT del usuario en el Chat: responde las escalaciones en curso (persistido) y corta los intentos. */
    fun answerActive(userId: String): List<StoredAlarmRecord> {
        val now = clock()
        return store.listForUser(userId).filter(AlarmEscalation::answerable).mapNotNull { r ->
            cancelPendingIntent(r.id)
            store.update(r.id) {
                it.copy(
                    answered = true, answeredAtMillis = now, nextFireAtMillis = 0L,
                    syncState = when {
                        it.syncState == StoredAlarmRecord.SYNC_CREATE -> StoredAlarmRecord.SYNC_CREATE
                        it.remoteId != null -> StoredAlarmRecord.SYNC_ANSWER
                        else -> StoredAlarmRecord.SYNC_OK
                    }
                )
            }
        }
    }

    fun hasAnswerable(userId: String): Boolean = store.listForUser(userId).any(AlarmEscalation::answerable)

    fun cancel(alarmId: String) {
        cancelPendingIntent(alarmId)
        store.remove(alarmId)
    }

    /** Cancelación pedida offline de una alarma ya sincronizada: se borra localmente y se avisa al backend luego. */
    fun cancelAndSync(alarmId: String) {
        cancelPendingIntent(alarmId)
        val r = store.find(alarmId) ?: return
        if (r.remoteId != null && r.syncState != StoredAlarmRecord.SYNC_CREATE) {
            store.upsert(r.copy(answered = true, syncState = StoredAlarmRecord.SYNC_CANCEL, nextFireAtMillis = 0L))
        } else {
            store.remove(alarmId)
        }
    }

    /** Arranque de la app, BOOT_COMPLETED, cambio de hora/zona, permiso de alarmas exactas: re-arma todo desde disco. */
    fun restoreAll() {
        store.listAll().forEach { runCatching { arm(it) }.onFailure { e -> Log.w(TAG, "restore ${it.id}: ${e.javaClass.simpleName}") } }
    }

    @Deprecated("Usar restoreAll()", ReplaceWith("restoreAll()"))
    fun rescheduleStoredAlarms() = restoreAll()

    fun peekNextAlarm(userId: String): StoredAlarmRecord? =
        store.listForUser(userId).filter { !it.answered && it.kind == StoredAlarmRecord.KIND_ALARM }
            .sortedBy { it.triggerAtMillis }.firstOrNull()

    fun findByHour(userId: String, hour: String?): List<StoredAlarmRecord> {
        val active = store.listForUser(userId).filter { !it.answered && it.kind == StoredAlarmRecord.KIND_ALARM }
        return if (hour.isNullOrBlank()) listOfNotNull(active.maxByOrNull { it.triggerAtMillis }) else active.filter { it.hour == hour }
    }

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    fun exactAlarmPermissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) else null

    private fun setExact(record: StoredAlarmRecord, stage: Int, atMillis: Long) {
        val pi = broadcastIntent(record.id, record.userId, stage)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() ->
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            // Despertador: setAlarmClock (exento de Doze, ícono de alarma). Recordatorios: exacta permitida en reposo.
            record.kind == StoredAlarmRecord.KIND_ALARM ->
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, null), pi)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else -> @Suppress("DEPRECATION") alarmManager.setExact(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun cancelPendingIntent(alarmId: String) {
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context, requestCode(alarmId), Intent(context, Me2AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    private fun broadcastIntent(alarmId: String, userId: String, stage: Int): PendingIntent {
        val intent = Intent(context, Me2AlarmReceiver::class.java).apply {
            putExtra(Me2NotificationCoordinator.EXTRA_USER_ID, userId)
            putExtra(Me2NotificationCoordinator.EXTRA_ALARM_ID, alarmId)
            putExtra(Me2NotificationCoordinator.EXTRA_STAGE, stage)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(alarmId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Con red: texto del backend/usuario. Sin red: banco offline (BORRADOR editable en assets/offline) con
     * placeholders del usuario y su pista audiovisual, que viaja en el intent para el clip del avatar al abrir el Chat.
     */
    private fun notifyStage(record: StoredAlarmRecord, spec: AlarmDispatchStageSpec) {
        val (finalSpec, cue) = offlineContent(record, spec)
        Me2NotificationCoordinator(context).showAlarmNotification(record.userId, record.id, finalSpec.toStage(), cue)
    }

    internal fun offlineContent(record: StoredAlarmRecord, spec: AlarmDispatchStageSpec): Pair<AlarmDispatchStageSpec, com.me2.android.media.AudiovisualCue?> {
        if (isOnline()) return spec to null
        val categoria = if (record.kind == StoredAlarmRecord.KIND_REMINDER) OfflinePhraseBank.RECORDATORIO else OfflinePhraseBank.alarmCategory(spec.stage)
        val nombre = runCatching { SessionStorage(context).loadUser()?.displayName?.trim()?.substringBefore(' ') }.getOrNull()
        val phrase = OfflinePhrases(context).pick(categoria, mapOf("nombre" to nombre, "titulo" to record.title, "hora" to record.hour))
            ?: return spec to null
        return spec.copy(message = phrase.text) to phrase.cue
    }

    private fun nextTriggerMillis(hour: String): Long {
        val parts = hour.split(":")
        val calendar = Calendar.getInstance().apply { timeInMillis = clock() }
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toIntOrNull() ?: 0)
        calendar.set(Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
        if (calendar.timeInMillis <= clock()) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    companion object {
        private const val TAG = "Me2AlarmScheduler"
        fun requestCode(alarmId: String): Int = "alarm-$alarmId".hashCode()

        /**
         * Contenido del intento: título = el de la alarma/recordatorio (dato del usuario o del backend);
         * texto = mensaje del backend para ese intento si se cacheó, si no el mensaje guardado o la hora. Sin frases
         * inventadas en el teléfono.
         */
        fun specFor(record: StoredAlarmRecord, stage: Int): AlarmDispatchStageSpec {
            val loud = record.kind == StoredAlarmRecord.KIND_ALARM && stage >= 3
            val fromPlan = record.dispatchPlan.firstOrNull { it.stage == stage }
            val text = fromPlan?.message?.takeIf { it.isNotBlank() } ?: record.message.ifBlank { record.hour }
            return AlarmDispatchStageSpec(stage, record.title, text, loud)
        }
    }
}

data class AlarmDispatchStageSpec(val stage: Int, val title: String, val message: String, val loud: Boolean) {
    fun toStage() = com.me2.android.net.AlarmDispatchStage(
        stage = stage, offsetFromAlarmMs = 0L,
        channelId = if (loud) Me2NotificationChannels.CHANNEL_ALARMS else Me2NotificationChannels.CHANNEL_MESSAGES,
        notificationType = if (loud) "alarm" else "message",
        vibration = if (loud) "alarm" else "double",
        sound = if (loud) "alarm" else "bubble",
        title = title, message = message
    )
}
