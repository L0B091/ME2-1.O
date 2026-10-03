package com.me2.android.offline

import android.content.Context
import com.me2.android.data.LocalConversationEntry
import com.me2.android.data.LocalMe2Memory
import com.me2.android.media.AvatarCueMapper
import com.me2.android.media.MediaCategoria
import com.me2.android.media.MediaNameParser
import com.me2.android.media.MediaSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineChatResponderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val lib = listOf("01_LOOP_NEUTRAL/NEUTRAL_001.mp4", "01_LOOP_NEUTRAL/NEUTRAL_002.mp4",
        "02_REACCIONES/EMPATIA/EMPATIA_NORMAL_001.mp4", "02_REACCIONES/EMPATIA/EMPATIA_NORMAL_002.mp4").map { MediaNameParser.parse(it)!! }

    @Test fun inputSinRedPersistePendienteYRespondeConFraseYCue() {
        val order = mutableListOf<String>()
        val reply = OfflineChatResponder(
            pick = { cat, vars -> order += "pick:$cat"; OfflinePhrases(context).pick(cat, vars) },
            markPending = { order += "pending" }
        ).respond("Ema", "fallback")
        assertEquals(listOf("pending", "pick:sin_red_input"), order) // primero se guarda el mensaje
        assertTrue(reply.phraseId!!.startsWith("sin_red_input#"))
        assertFalse(reply.text.contains("{")); assertNotEquals("fallback", reply.text)
        assertTrue("frase del set nuevo: ${reply.text}", reply.text in FRASES_SIN_RED)
        assertTrue(com.me2.android.media.OfflineAvatarPool.esCueNeutral(reply.cue))
        val req = AvatarCueMapper.fromCue(reply.cue)!!
        assertTrue(req.categoria == MediaCategoria.LOOP_NEUTRAL || req.subcategoria == "ESCRIBIENDO")
        val sel = MediaSelector.select(lib, req)!!
        assertFalse(sel.recurso.adulto)
        assertEquals(MediaCategoria.LOOP_NEUTRAL, sel.recurso.categoria) // sin clips de escribiendo → neutral, nunca reacción
    }

    @Test fun frasesSinRedSonCortasYSinPromesas() {
        val json = org.json.JSONObject(context.assets.open("offline/frases_offline.json").bufferedReader().readText())
        val arr = json.getJSONObject("categorias").getJSONObject("sin_red_input").getJSONArray("variantes")
        val frases = (0 until arr.length()).map { arr.getString(it) }
        assertEquals(FRASES_SIN_RED, frases.toSet())
        frases.forEach { f ->
            assertTrue(f, f.length <= 40)
            assertFalse(f, Regex("guard|anot|perd|respond|contest|\\{", RegexOption.IGNORE_CASE).containsMatchIn(f))
        }
    }

    @Test fun sinBancoUsaTextoDeRespaldoYCueNeutral() {
        val r = OfflineChatResponder(pick = { _, _ -> null }, markPending = {}).respond(null, "Sin conexión.")
        assertEquals("Sin conexión.", r.text); assertEquals("LOOP_NEUTRAL", r.cue.categoria)
    }

    @Test fun rotacionSinRepeticionInmediataPersistidaEntreInstancias() {
        var prev: String? = null
        repeat(40) {
            val r = OfflineChatResponder({ c, v -> OfflinePhrases(context).pick(c, v) }, {}).respond("Ema", "x")
            assertNotEquals(prev, r.phraseId); prev = r.phraseId
        }
        assertEquals(prev, context.getSharedPreferences("me2_offline_phrases", Context.MODE_PRIVATE).getString("sin_red_input", null))
    }

    @Test fun mensajePendienteSePersisteEnLaMemoriaLocal() {
        val m = LocalMe2Memory(userId = "u", conversation = mutableListOf(
            LocalConversationEntry("user", "¿mañana llueve?", 10L, pending = true),
            LocalConversationEntry("assistant", "Estoy sin red", 11L)))
        val back = LocalMe2Memory.fromJson(m.toJson())
        assertTrue(back.conversation[0].pending); assertEquals("¿mañana llueve?", back.conversation[0].text)
        assertFalse(back.conversation[1].pending)
        assertFalse(LocalMe2Memory.fromJson(org.json.JSONObject(m.toJson().toString().replace(",\"pendiente\":true", ""))).conversation[0].pending)
    }

    private companion object {
        val FRASES_SIN_RED = setOf("Oops, no hay internet.", "Conectate para seguir charlando.", "Algo pasó con la conexión.",
            "Sin conexión por ahora.", "Se cortó internet.")
    }
}
