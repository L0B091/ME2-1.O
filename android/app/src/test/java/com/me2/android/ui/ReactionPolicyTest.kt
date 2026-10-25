package com.me2.android.ui

import com.me2.android.data.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ReactionPolicyTest {
    private fun u(t: String, r: String? = null) = ChatMessage(t, false, reaction = r)
    private fun m(t: String) = ChatMessage(t, true)

    @Test fun allowlistOnly() {
        assertEquals("❤️", ReactionPolicy.validar("❤️"))
        assertEquals("❤️", ReactionPolicy.validar("❤"))
        assertNull(ReactionPolicy.validar("🍆"))
        assertNull(ReactionPolicy.validar(" "))
        assertNull(ReactionPolicy.aceptar(listOf(u("hola")), "🍆"))
    }

    @Test fun neverConsecutiveAndMinGap() {
        val conv = mutableListOf(u("gracias", "❤️"), m("de nada"), u("jaja"))
        assertNull("nunca dos seguidas", ReactionPolicy.aceptar(conv, "😂"))
        conv += listOf(m("a"), u("b"), m("c"), u("d"))
        assertNull(ReactionPolicy.aceptar(conv, "😂"))
        conv += listOf(m("e"), u("f"))
        assertEquals("😂", ReactionPolicy.aceptar(conv, "😂"))
        assertNull("ya tiene reacción", ReactionPolicy.aceptar(listOf(u("x", "👍")), "😂"))
        assertNull("sin mensajes del usuario", ReactionPolicy.aceptar(listOf(m("hola")), "😂"))
    }

    @Test fun offlineHeuristicRareAndClear() {
        assertNull(ReactionPolicy.offline(listOf(u("qué hora es")), "qué hora es", 0.0))
        assertEquals("❤️", ReactionPolicy.offline(listOf(u("gracias")), "gracias", 0.0))
        assertNull(ReactionPolicy.offline(listOf(u("gracias")), "gracias", 0.9))
    }

    @Test fun reactionAppearsDuringTypingAndPersistsField() {
        assertTrue(ReactionPolicy.DELAY_MS in 300L..1200L)
        assertEquals("🎉", u("x").copy(reaction = "🎉").reaction)
    }
}
