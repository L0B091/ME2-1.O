package com.me2.android.notifications

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Me2OfflineSyncTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store get() = Me2AlarmStore(context)

    private class FakeApi(var down: Boolean = false) : AlarmSyncApi {
        val calls = mutableListOf<String>()
        override fun createAlarm(hour: String, title: String, message: String): String { if (down) error("offline"); calls += "create:$hour"; return "srv-$hour" }
        override fun reportAnswered(remoteId: String, stage: Int): String? { if (down) error("offline"); calls += "answer:$remoteId:$stage"; return "18°C" }
        override fun cancel(remoteId: String) { if (down) error("offline"); calls += "cancel:$remoteId" }
    }

    private fun rec(id: String, sync: String, remote: String?, fired: Int = 0, answered: Boolean = false) = StoredAlarmRecord(
        id = id, userId = "u", hour = "07:00", title = "t", message = "", state = "ACTIVE", triggerAtMillis = Long.MAX_VALUE / 2,
        dispatchPlan = emptyList(), firedStage = fired, answered = answered, remoteId = remote, syncState = sync, nextFireAtMillis = 1L
    )

    @Before fun clean() { store.listAll().forEach { store.remove(it.id) } }

    @Test fun alReconectarSubeAltaRespuestaYCancelacion() {
        store.upsert(rec("local-1", StoredAlarmRecord.SYNC_CREATE, null))
        store.upsert(rec("srv-a", StoredAlarmRecord.SYNC_ANSWER, "srv-a", fired = 2, answered = true))
        store.upsert(rec("srv-b", StoredAlarmRecord.SYNC_CANCEL, "srv-b", answered = true))
        val api = FakeApi()
        val report = Me2OfflineSync(Me2AlarmStore(context), api).syncPending("u")
        assertEquals(Me2OfflineSync.Report(1, 1, 1, 0, listOf("18°C")), report)
        assertEquals(setOf("create:07:00", "answer:srv-a:2", "cancel:srv-b"), api.calls.toSet())
        assertTrue(store.pendingSync().isEmpty())
        val created = store.find("local-1")!!
        assertEquals("srv-07:00", created.remoteId); assertEquals(StoredAlarmRecord.SYNC_OK, created.syncState)
        assertNull(store.find("srv-a")); assertNull(store.find("srv-b"))
    }

    @Test fun sinRedFallaYQuedaPendienteParaReintento() {
        store.upsert(rec("srv-a", StoredAlarmRecord.SYNC_ANSWER, "srv-a", fired = 1, answered = true))
        val api = FakeApi(down = true)
        assertEquals(1, Me2OfflineSync(store, api).syncPending("u").failed)
        assertEquals(1, Me2AlarmStore(context).pendingSync().size) // persiste para el próximo intento
        api.down = false
        assertEquals(1, Me2OfflineSync(Me2AlarmStore(context), api).syncPending("u").answered)
        assertTrue(store.pendingSync().isEmpty())
    }

    @Test fun altaLocalYaRespondidaOfflineNoSeCreaEnServidor() {
        store.upsert(rec("local-2", StoredAlarmRecord.SYNC_CREATE, null, fired = 1, answered = true))
        val api = FakeApi()
        Me2OfflineSync(store, api).syncPending("u")
        assertTrue(api.calls.isEmpty()); assertNull(store.find("local-2"))
    }
}
