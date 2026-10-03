package com.me2.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Detectado en emulador: el demo saludaba "Uy, Vista, …" (rótulo "Vista previa"). */
class UserSessionGreetingTest {
    @Test fun demoNoUsaSuRotuloComoNombre() {
        assertNull(UserSession.demoPreview().greetingName)
        assertEquals("Ana", UserSession("Ana María", "a@b.c", "u1").greetingName)
        assertNull(UserSession("  ", "a@b.c", "u1").greetingName)
    }
}
