package com.me2.android.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class WelcomeBackPolicyTest {
    @Test fun debounce() {
        assertTrue(WelcomeBackPolicy.debeSaludar(1_000, 31_000, false, false))
        assertFalse(WelcomeBackPolicy.debeSaludar(1_000, 20_000, false, false))
        assertFalse(WelcomeBackPolicy.debeSaludar(1_000, 99_000, true, false))
        assertFalse(WelcomeBackPolicy.debeSaludar(1_000, 99_000, false, true))
        assertFalse(WelcomeBackPolicy.debeSaludar(0, 99_000, false, false))
        // sin límite diario: dispara cada vez
        repeat(5) { assertTrue(WelcomeBackPolicy.debeSaludar(1_000L + it * 100_000, 61_000L + it * 100_000, false, false)) }
    }

    @Test fun noRepiteLaAnterior() {
        val r = Random(7)
        var prev = -1
        repeat(200) { val i = WelcomeBackPolicy.elegirIndice(prev, r); assertNotEquals(prev, i); prev = i }
        assertTrue(WelcomeBackPolicy.frase(0, "Ema").contains("Ema"))
    }
}
