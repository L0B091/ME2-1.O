package com.me2.android.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Me2SessionRecoveryTest {
    private var now = 1_000_000L

    @Before fun setUp() {
        Me2SessionRecovery.reset()
        Me2SessionRecovery.clock = { now }
        Me2SessionRecovery.retryBackoffMillis = 60_000L
    }

    @After fun tearDown() {
        Me2SessionRecovery.renewer = null
        Me2SessionRecovery.reset()
        Me2SessionRecovery.clock = System::currentTimeMillis
    }

    @Test fun sinRenovacionElTokenEsElMismo() {
        assertEquals("a", Me2SessionRecovery.currentToken("a"))
        assertNull(Me2SessionRecovery.currentToken(null))
    }

    @Test fun un401SeRecuperaEnSilencioYSeReintentaUnaVez() {
        Me2SessionRecovery.renewer = Me2SessionRecovery.Renewer { "nuevo" }
        val usados = mutableListOf<String?>()
        val r = Me2SessionRecovery.withRecovery("viejo") { t ->
            usados += t
            if (t == "viejo") throw AuthRequiredException("Autenticación requerida")
            "ok"
        }
        assertEquals("ok", r)
        assertEquals(listOf("viejo", "nuevo"), usados)
        // Una copia vieja de la sesión en memoria ya usa el token nuevo, sin otro 401.
        assertEquals("nuevo", Me2SessionRecovery.currentToken("viejo"))
    }

    @Test fun sinRenovadorOFallaSePropagaEl401YNadaSeBorra() {
        try {
            Me2SessionRecovery.withRecovery("viejo") { throw AuthRequiredException("401") }
            fail()
        } catch (_: AuthRequiredException) {}
        Me2SessionRecovery.renewer = Me2SessionRecovery.Renewer { null }
        try {
            Me2SessionRecovery.withRecovery("viejo") { throw AuthRequiredException("401") }
            fail()
        } catch (_: AuthRequiredException) {}
        assertEquals("viejo", Me2SessionRecovery.currentToken("viejo"))
    }

    @Test fun otrosErroresNoDisparanRenovacion() {
        val llamadas = AtomicInteger()
        Me2SessionRecovery.renewer = Me2SessionRecovery.Renewer { llamadas.incrementAndGet(); "nuevo" }
        try {
            Me2SessionRecovery.withRecovery("t") { throw IllegalStateException("Error HTTP 500") }
            fail()
        } catch (e: IllegalStateException) { assertTrue(e !is AuthRequiredException) }
        assertEquals(0, llamadas.get())
    }

    @Test fun trasUnFalloEsperaAntesDeReintentar() {
        val llamadas = AtomicInteger()
        var respuesta: String? = null
        Me2SessionRecovery.renewer = Me2SessionRecovery.Renewer { llamadas.incrementAndGet(); respuesta }
        assertNull(Me2SessionRecovery.recover("viejo"))
        assertNull(Me2SessionRecovery.recover("viejo"))
        assertEquals(1, llamadas.get())
        now += 61_000L
        respuesta = "nuevo"
        assertEquals("nuevo", Me2SessionRecovery.recover("viejo"))
        assertEquals(2, llamadas.get())
    }

    @Test fun variosHilosCon401RenuevanUnaSolaVez() {
        val llamadas = AtomicInteger()
        Me2SessionRecovery.renewer = Me2SessionRecovery.Renewer { Thread.sleep(50); llamadas.incrementAndGet(); "nuevo" }
        val start = CountDownLatch(1)
        val resultados = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val hilos = (1..6).map { thread { start.await(); resultados += Me2SessionRecovery.recover("viejo") } }
        start.countDown(); hilos.forEach { it.join(5000) }
        assertEquals(1, llamadas.get())
        assertEquals(List(6) { "nuevo" }, resultados.toList())
    }

    @Test fun renovacionesEncadenadasYResetAlCerrarSesion() {
        var siguiente = "t2"
        Me2SessionRecovery.renewer = Me2SessionRecovery.Renewer { siguiente }
        assertEquals("t2", Me2SessionRecovery.recover("t1"))
        siguiente = "t3"
        assertEquals("t3", Me2SessionRecovery.recover("t2"))
        assertEquals("t3", Me2SessionRecovery.currentToken("t1"))
        Me2SessionRecovery.reset()
        assertEquals("t1", Me2SessionRecovery.currentToken("t1"))
    }
}
