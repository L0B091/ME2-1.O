package com.me2.android.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BitacoraContentTest {
    private val prohibidas = Regex("(?i)premium|\\bfree\\b|\\bplan\\b|demo")

    @Test fun bitacoraTextsHaveNoPlanOrPremiumLines() {
        val c = BitacoraContent.build("Emanuel", "usuario@gmail.com", "user-1", "Nova", 630)
        assertEquals("EMANUEL", c.userName)
        assertEquals("AVATAR // NOVA", c.avatarLine)
        assertTrue(c.nodeLine.matches(Regex("NODO_ID // \\d{2}-\\d{2}-\\d{2}-\\d")))
        assertEquals("MAIL // usuario@gmail.com", c.mailLine)
        assertEquals("10.5%", c.linkLabel)
        assertEquals(105, c.linkProgressTenths)
        c.allTexts().forEach { assertFalse(it, prohibidas.containsMatchIn(it)) }
    }

    @Test fun avatarLineHiddenWhenUnknown() {
        assertNull(BitacoraContent.build("Emanuel", "u@x.com", "u", null, 0).avatarLine)
        assertNull(BitacoraContent.build("Emanuel", "u@x.com", "u", "  ", 0).avatarLine)
    }

    @Test fun bitacoraLayoutHasNoPremiumButtonOrPlanLines() {
        val layout = listOf("src/main/res/layout/activity_main.xml", "app/src/main/res/layout/activity_main.xml")
            .map(::File).first { it.exists() }.readText()
        val panel = layout.substring(layout.indexOf("@+id/bitacoraPanel"))
        assertFalse(prohibidas.containsMatchIn(panel))
        assertTrue(panel.contains("@+id/linkProgressBar"))
        assertTrue(panel.contains("@+id/homeWidgetSwitch"))
        assertTrue(panel.indexOf("@+id/signOutButton") > panel.indexOf("@+id/homeWidgetSwitch"))
    }

    @Test fun linkStartsAtZeroGrowsOnePercentPerHourCapped() {
        assertEquals(0.0, LinkProgress.percent(0), 0.0)
        assertEquals(10.5, LinkProgress.percent(630), 0.0)
        assertEquals(99.9, LinkProgress.percent(200 * 60), 0.0)
        assertEquals("0.0%", LinkProgress.label(LinkProgress.percent(0)))
        assertEquals("99.9%", LinkProgress.label(LinkProgress.percent(100_000 * 60)))
        assertTrue(LinkProgress.percent(Long.MAX_VALUE / 2) < 100.0)
    }
}
