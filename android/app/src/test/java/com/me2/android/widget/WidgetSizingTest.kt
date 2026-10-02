package com.me2.android.widget

import com.me2.android.gallery.ClipPicker
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class WidgetSizingTest {
    @Test fun widgetStaysSquareAndCenteredOnResize() {
        assertEquals(WidgetSizing.Square(110, 0, 0), WidgetSizing.square(110, 110))
        val wide = WidgetSizing.square(250, 110)
        assertEquals(110, wide.side); assertEquals(70, wide.padH); assertEquals(0, wide.padV)
        val tall = WidgetSizing.fromOptions(minW = 120, maxW = 160, minH = 100, maxH = 260, portrait = true)
        assertEquals(120, tall.side); assertEquals(0, tall.padH); assertEquals(70, tall.padV)
        val land = WidgetSizing.fromOptions(minW = 120, maxW = 300, minH = 100, maxH = 260, portrait = false)
        assertEquals(100, land.side); assertEquals(100, land.padH)
    }

    @Test fun randomClipPickerNeverRepeatsPreviousWhenPossible() {
        val clips = listOf("a", "b", "c")
        val r = Random(3)
        var prev: String? = null
        val seen = mutableSetOf<String>()
        repeat(100) {
            val next = ClipPicker.pickRandom(clips, prev, r)!!
            assertNotEquals(prev, next)
            seen += next; prev = next
        }
        assertEquals(clips.toSet(), seen)
        assertEquals("solo", ClipPicker.pickRandom(listOf("solo"), "solo", r))
        assertNull(ClipPicker.pickRandom(emptyList<String>(), null, r))
    }
}
