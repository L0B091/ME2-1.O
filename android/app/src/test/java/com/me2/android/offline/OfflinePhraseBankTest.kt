package com.me2.android.offline

import com.me2.android.media.AvatarCueMapper
import com.me2.android.media.MediaCategoria
import com.me2.android.media.MediaNameParser
import com.me2.android.media.MediaSelector
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OfflinePhraseBankTest {
    private val json = listOf("src/main/assets/offline/frases_offline.json", "app/src/main/assets/offline/frases_offline.json")
        .map(::File).first { it.exists() }.readText()
    private val bank = OfflinePhraseBank(json)

    @Test fun assetEsBorradorConCincoVariantesPorCategoria() {
        val root = JSONObject(json)
        assertEquals("BORRADOR", root.getString("estado"))
        val expected = setOf("alarma_aviso_1", "alarma_aviso_2", "alarma_final", "recordatorio", "inicio_conversacion")
        assertEquals(expected, bank.categories())
        expected.forEach { c ->
            assertEquals(c, 5, root.getJSONObject("categorias").getJSONObject(c).getJSONArray("variantes").length())
            assertNotNull(c, bank.pick(c, emptyMap())!!.cue)
        }
    }

    @Test fun rotacionNoRepiteLaUltima() {
        var prev: String? = null
        repeat(200) { seed ->
            val p = bank.pick(OfflinePhraseBank.INICIO, mapOf("nombre" to "Ema"), prev, Random(seed))!!
            assertNotEquals(prev, p.id); prev = p.id
        }
    }

    @Test fun placeholdersSeCompletanYSeOmitenSiFaltan() {
        assertEquals("Buen día, Ema. Son las 07:00: Gimnasio.", OfflinePhraseBank.render("Buen día, {nombre}. Son las {hora}: {titulo}.", mapOf("nombre" to "Ema", "hora" to "07:00", "titulo" to "Gimnasio")))
        assertEquals("Buen día. Son las 07:00.", OfflinePhraseBank.render("Buen día, {nombre}. Son las {hora}: {titulo}.", mapOf("hora" to "07:00")))
        for (c in bank.categories()) repeat(30) { s ->
            val t = bank.pick(c, emptyMap(), random = Random(s))!!.text
            assertFalse(t, t.contains("{")); assertFalse(t, t.contains(" ,")); assertFalse(t, t.isBlank())
        }
        assertEquals(OfflinePhraseBank.ALARMA_FINAL, OfflinePhraseBank.alarmCategory(3))
    }

    @Test fun cueDeLaFraseVaAlMediaSelectorConFallbackYAntiRepeticion() {
        val lib = listOf("01_LOOP_NEUTRAL/NEUTRAL_001.mp4", "01_LOOP_NEUTRAL/NEUTRAL_002.mp4",
            "02_REACCIONES/AFECTO/AFECTO_NORMAL_001.mp4", "02_REACCIONES/AFECTO/AFECTO_NORMAL_002.mp4",
            "07_PREMIUM/ADULTO/ADULTO_MEDIO_001.mp4").map { MediaNameParser.parse(it)!! }
        for (c in bank.categories()) repeat(20) { s ->
            val phrase = bank.pick(c, emptyMap(), random = Random(s))!!
            val req = AvatarCueMapper.fromCue(phrase.cue)
            assertNotNull("cue mapeable: ${phrase.cue}", req)
            val sel = MediaSelector.select(lib, req!!, previousId = "AFECTO_NORMAL_001", random = Random(s))!!
            assertFalse(sel.recurso.adulto)
            assertNotEquals(MediaCategoria.PRESENTACION, sel.recurso.categoria)
            if (sel.recurso.subcategoria == "AFECTO") assertEquals("AFECTO_NORMAL_002", sel.recurso.id)
        }
        // despertador sin clips propios todavía → cae a neutral, nunca null
        val alarma = MediaSelector.select(lib, AvatarCueMapper.fromCue(bank.pick(OfflinePhraseBank.ALARMA_FINAL, emptyMap())!!.cue)!!)!!
        assertEquals(MediaCategoria.LOOP_NEUTRAL, alarma.recurso.categoria)
    }
}
