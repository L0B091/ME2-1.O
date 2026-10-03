package com.me2.android.offline

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RestWindowTest {
    private val tz = TimeZone.getTimeZone("America/Buenos_Aires")
    private fun at(h: Int, m: Int = 0, day: Int = 3) = Calendar.getInstance(tz).apply { set(2026, Calendar.OCTOBER, day, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun containsYNextActiveCirculares() {
        val w = RestWindow(23, 7)
        assertTrue(w.contains(at(23, 30), tz)); assertTrue(w.contains(at(3), tz)); assertFalse(w.contains(at(7), tz))
        assertEquals(at(7, day = 4), w.nextActive(at(23, 30), tz))
        assertEquals(at(7), w.nextActive(at(3), tz))
        assertEquals(at(12), w.nextActive(at(12), tz))
    }

    @Test fun estimacion() {
        assertEquals(RestWindow.DEFAULT, RestWindow.estimate(listOf(at(10)), tz))
        val obs = (1..20).flatMap { d -> listOf(10, 13, 16, 20, 23).map { at(it, day = d) } }
        assertEquals(RestWindow(0, 10), RestWindow.estimate(obs, tz))
        assertEquals(RestWindow(0, 8), RestWindow.estimate(obs, tz, wakeHour = 8))
        assertEquals(RestWindow(2, 9), RestWindow.estimate(obs, tz, configured = "02:00" to "09:00"))
    }
}
