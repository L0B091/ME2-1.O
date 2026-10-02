package com.me2.android.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TypewriterTimingTest {
    @Test fun pausesAreLongerAfterCommasAndPeriods() {
        val r = Random(7)
        val letter = (1..200).map { TypewriterTiming.delayAfter('a', r) }
        val comma = (1..200).map { TypewriterTiming.delayAfter(',', r) }
        val period = (1..200).map { TypewriterTiming.delayAfter('.', r) }
        assertTrue(letter.max() < comma.min())
        assertTrue(comma.max() < period.min())
        // ritmo humano: ~25-45 ms por letra, con jitter (no constante)
        assertTrue(letter.min() >= 20 && letter.max() <= 50)
        assertTrue(letter.toSet().size > 3)
    }

    @Test fun scheduleCoversEveryCharAndIsReproducibleBySeed() {
        val text = "Hola, Emanuel. ¿Todo bien?"
        val a = TypewriterTiming.schedule(text, Random(1))
        assertEquals(text.length, a.size)
        assertEquals(a, TypewriterTiming.schedule(text, Random(1)))
        val total = a.sum()
        assertTrue("duración humana total: $total ms", total in 1200..2500)
    }

    @Test fun tapRestartsAfterFinishingAndOldMessagesDontReanimateOnScroll() {
        assertEquals(TypewriterTiming.TapAction.RESTART, TypewriterTiming.tapAction(running = false))
        assertEquals(TypewriterTiming.TapAction.FINISH, TypewriterTiming.tapAction(running = true))
        assertTrue(TypewriterTiming.animateOnBind(fromMe2 = true, wantsAnimation = true, alreadyPlayed = false))
        assertFalse(TypewriterTiming.animateOnBind(fromMe2 = true, wantsAnimation = true, alreadyPlayed = true))
        assertFalse(TypewriterTiming.animateOnBind(fromMe2 = false, wantsAnimation = true, alreadyPlayed = false))
    }
}
