package com.me2.android.media

import com.me2.android.media.PlaybackTransition.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTransitionTest {
    @Test fun idlePlayerStartsNow() = assertEquals(Action.START_NOW, PlaybackTransition.decide(false, 5000))
    @Test fun endedClipStartsNow() = assertEquals(Action.START_NOW, PlaybackTransition.decide(true, 0))
    @Test fun shortRemainderWaits() {
        assertEquals(Action.WAIT_FOR_END, PlaybackTransition.decide(true, 1200))
        assertEquals(Action.WAIT_FOR_END, PlaybackTransition.decide(true, 300))
    }
    @Test fun longRemainderCrossfades() = assertEquals(Action.CROSSFADE, PlaybackTransition.decide(true, 1201))
    @Test fun unknownDurationCrossfades() = assertEquals(Action.CROSSFADE, PlaybackTransition.decide(true, null))
    @Test fun neverHardCut() {
        listOf(null, -1L, 0L, 1L, 1200L, 1201L, 60000L).forEach { r ->
            listOf(true, false).forEach { p -> PlaybackTransition.decide(p, r) }
        }
        assertEquals(400L, PlaybackTransition.CROSSFADE_MS)
    }
    @Test fun pendingKeepsOnlyLatest() {
        val p = PlaybackTransition.Pending<String>()
        p.offer("a"); p.offer("b"); p.offer("c")
        assertEquals("c", p.take())
        assertNull(p.take())
        assertFalse(p.hasPending)
    }
}
