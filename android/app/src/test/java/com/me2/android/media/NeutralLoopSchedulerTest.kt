package com.me2.android.media

import android.net.Uri
import com.me2.android.gallery.GalleryClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeutralLoopSchedulerTest {
    private fun clip(id: String) = GalleryClip(id, "loop", id, Uri.parse("asset:///ME2_MEDIA/$id.mp4"), GalleryClip.Source.ASSETS)
    private val shorts = (1..6).map { clip("LOOP_NEUTRAL_0$it") }
    private val longs = listOf(clip("NEUTRAL_LARGO_CAFE"), clip("NEUTRAL_LARGO_ESTIRARSE"))

    @Test fun emptyGalleryReturnsNull() = assertNull(NeutralLoopScheduler().pick(emptyList(), null))

    @Test fun singleClipRepeats() {
        val s = NeutralLoopScheduler()
        repeat(3) { assertEquals("LOOP_NEUTRAL_01", s.pick(shorts.take(1), "LOOP_NEUTRAL_01")?.id) }
    }

    @Test fun neverRepeatsLastThree() {
        val s = NeutralLoopScheduler()
        val r = Random(7)
        val seq = mutableListOf<String>()
        var cur: String? = null
        repeat(500) { cur = s.pick(shorts, cur, r)!!.id; seq += cur!! }
        seq.windowed(4).forEach { w -> assertEquals(w.toString(), 4, w.toSet().size) }
    }

    @Test fun smallGalleryShrinksWindowButNeverRepeatsCurrent() {
        val s = NeutralLoopScheduler()
        val r = Random(3)
        var cur: String? = null
        repeat(200) { val n = s.pick(shorts.take(2), cur, r)!!.id; assertTrue(n != cur); cur = n }
    }

    @Test fun longLoopsOccasionalAndNeverConsecutive() {
        val s = NeutralLoopScheduler()
        val r = Random(11)
        var cur: String? = null
        val seq = (1..4000).map { s.pick(shorts + longs, cur, r)!!.id.also { cur = it } }
        val isLong = seq.map { NeutralLoopScheduler.isLongName(it) }
        isLong.zipWithNext().forEach { (a, b) -> assertFalse(a && b) }
        val ratio = isLong.count { it }.toDouble() / seq.size
        assertTrue("ratio=$ratio", ratio in 0.15..0.28)
    }

    @Test fun worksWithoutLongLoops() {
        val s = NeutralLoopScheduler()
        val r = Random(5)
        var cur: String? = null
        repeat(100) { cur = s.pick(shorts, cur, r)!!.id; assertFalse(NeutralLoopScheduler.isLongName(cur!!)) }
    }

    @Test fun onlyLongLoopsStillPlay() {
        val s = NeutralLoopScheduler()
        val r = Random(5)
        var cur: String? = null
        repeat(20) { val n = s.pick(longs, cur, r)!!.id; assertTrue(n != cur); cur = n }
    }

    @Test fun weightsBiasSelection() {
        val s = NeutralLoopScheduler(recentWindow = 0, weightOf = { if (it.id == "LOOP_NEUTRAL_01") 20.0 else 1.0 })
        val r = Random(9)
        var cur: String? = null
        val seq = (1..1000).map { s.pick(shorts, cur, r)!!.id.also { cur = it } }
        assertTrue(seq.count { it == "LOOP_NEUTRAL_01" } > 300)
    }
}
