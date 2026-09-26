package com.me2.android.notifications

import com.me2.android.data.EncryptedLocalPayload
import com.me2.android.data.Me2InitiativeRecordDao
import com.me2.android.data.Me2InitiativeRecordEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Me2InitiativeStoreTest {
    private class MemoryDao : Me2InitiativeRecordDao {
        private val rows = mutableMapOf<String, Me2InitiativeRecordEntity>()
        override fun findByUserId(userId: String) = rows[userId]
        override fun upsert(record: Me2InitiativeRecordEntity) { rows[record.userId] = record }
    }

    private val dao = MemoryDao()
    private fun store() = Me2InitiativeStore(dao, { EncryptedLocalPayload("test", it) }, { _, value -> value })

    private fun initiative(id: String = "news-1", expires: Long = System.currentTimeMillis() + 60_000) = JSONObject()
        .put("id", id).put("categoria", "NOTICIA").put("motivo", "Un tema de interes")
        .put("timestamp", 1L).put("contexto", JSONObject().put("evento", "noticia real"))
        .put("referenciaEvento", "source-1").put("prioridad", 50).put("fuente", "news")
        .put("mensaje", "Una novedad sobre el tema que hablamos.").put("expiresAt", expires)

    @Test fun reservationAndDeliverySurviveRecreationAndRemainUnique() {
        assertTrue(store().reserve("user", initiative()))
        assertFalse(store().reserve("user", initiative()))
        assertTrue(store().find("user", "news-1")!!.isNull("entregada"))
        store().delivered("user", "news-1", 10L)
        store().delivered("user", "news-1", 20L)
        assertEquals(10L, store().find("user", "news-1")!!.getLong("entregada"))
        assertEquals(1, store().records("user").size)
        assertNull(store().find("other-user", "news-1"))
    }

    @Test fun openingAndRespondingPreserveContextWithoutDowngradingState() {
        val now = System.currentTimeMillis()
        store().reserve("user", initiative())
        store().delivered("user", "news-1", now)
        store().opened("user", "news-1", now + 1)
        store().responded("user", "news-1", now + 2)
        store().dismissed("user", "news-1")
        store().cancelled("user", "news-1")
        store().opened("user", "news-1", now + 3)
        val record = store().activeContext("user")!!
        assertEquals("RESPONDIDA", record.getString("estado"))
        assertEquals(now + 1, record.getLong("abierta"))
        assertEquals(now + 2, record.getLong("respondida"))
        assertEquals("noticia real", record.getJSONObject("contexto").getString("evento"))
    }

    @Test fun dismissedUnopenedNotificationIsIgnored() {
        store().reserve("user", initiative())
        store().delivered("user", "news-1")
        store().dismissed("user", "news-1")
        assertEquals("IGNORADA", store().find("user", "news-1")!!.getString("estado"))
        assertNull(store().activeContext("user"))
    }

    @Test fun responseTypeDistinguishesRejectionFromPositiveEngagement() {
        store().reserve("user", initiative("negative"))
        store().opened("user", "negative")
        store().responded("user", "negative", responseText = "No me escribas ahora")
        assertEquals("negativa", store().find("user", "negative")!!.getString("tipoRespuesta"))
        store().reserve("user", initiative("positive"))
        store().opened("user", "positive")
        store().responded("user", "positive", responseText = "Gracias, contame")
        assertEquals("positiva", store().find("user", "positive")!!.getString("tipoRespuesta"))
    }

    @Test fun expiryDistinguishesUndeliveredFromIgnored() {
        store().reserve("user", initiative("undelivered", 100))
        store().reserve("user", initiative("delivered", 100))
        store().delivered("user", "delivered", 20)
        assertEquals(2, store().expire("user", 101).size)
        assertEquals("CANCELADA", store().find("user", "undelivered")!!.getString("estado"))
        assertEquals("IGNORADA", store().find("user", "delivered")!!.getString("estado"))
        assertTrue(store().expire("user", 102).isEmpty())
    }

    @Test fun pendingProfileResponseCannotReplaceNewSettingsOrObservations() {
        store().configureSleep("user", "23:00", "07:00")
        store().observeInteraction("user", 1000)
        store().mergeProfile("user", JSONObject()
            .put("configurado", JSONObject().put("dormir", "20:00").put("despertar", "06:00"))
            .put("origen", "aprendido"))
        val profile = store().snapshot("user").getJSONObject("perfilRitmo")
        assertEquals("23:00", profile.getJSONObject("configurado").getString("dormir"))
        assertEquals(1000L, profile.getJSONArray("observaciones").getLong(0))
        store().configureSleep("user", null, null)
        store().mergeProfile("user", profile)
        assertFalse(store().snapshot("user").getJSONObject("perfilRitmo").has("configurado"))
    }

    @Test fun repeatedInteractionsDoNotCreateManyIndependentObservations() {
        repeat(10) { store().observeInteraction("user", 1000L + it) }
        val profile = store().snapshot("user").getJSONObject("perfilRitmo")
        assertEquals(1, profile.getJSONArray("observaciones").length())
        assertEquals(1009L, store().snapshot("user").getLong("ultimaInteraccion"))
    }

    @Test fun preferencesAndRecordLimitArePersistent() {
        store().setEnabled("user", false)
        repeat(205) { store().reserve("user", initiative("event-$it")) }
        assertFalse(store().isEnabled("user"))
        assertTrue(store().isEnabled("other-user"))
        assertEquals(200, store().records("user").size)
        assertFalse(store().reserve("user", initiative("event-0")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun equalSleepAndWakeAreRejected() {
        store().configureSleep("user", "07:00", "07:00")
    }

    @Test(expected = IllegalStateException::class)
    fun unreadableRecordDoesNotSilentlyResetDeduplication() {
        store().reserve("user", initiative())
        Me2InitiativeStore(dao, { EncryptedLocalPayload("test", it) }, { _, _ -> null }).snapshot("user")
    }
}
