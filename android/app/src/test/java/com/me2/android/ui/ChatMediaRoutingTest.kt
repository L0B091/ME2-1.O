package com.me2.android.ui

import com.me2.android.ui.ChatMediaRouting.Clip
import com.me2.android.ui.ChatMediaRouting.Media
import org.junit.Assert.*
import org.junit.Test

class ChatMediaRoutingTest {
    private val gif = Media("gif", "http://h/api/media/xxx/g1")
    private val adultClip = Clip("clip", "catalogo_adulto", null, "http://h/api/media/xxx/c1")

    @Test fun gifsOnlyInlineAndOnlyInAdultMode() {
        assertNull(ChatMediaRouting.inlineGifUrl(gif, adultUnlocked = false))
        assertEquals("http://h/api/media/xxx/g1", ChatMediaRouting.inlineGifUrl(gif, adultUnlocked = true))
        assertNull(ChatMediaRouting.inlineGifUrl(Media("clip", "http://h/x"), adultUnlocked = true))
    }

    @Test fun avatarContainerNeverPlaysGifsAndAdultClipsNeedSession() {
        assertNull(ChatMediaRouting.avatarRemoteClipUrl(Clip("gif", "catalogo_adulto", null, "http://h/g"), true))
        assertNull(ChatMediaRouting.avatarRemoteClipUrl(adultClip, adultUnlocked = false))
        assertEquals("http://h/api/media/xxx/c1", ChatMediaRouting.avatarRemoteClipUrl(adultClip, true))
        assertNull("galería local por categoría", ChatMediaRouting.avatarRemoteClipUrl(Clip("clip", "galeria", "alegre", null), true))
    }

    @Test fun reactionsOnlyOnUserBubbles() {
        assertEquals("🎉", ChatMediaRouting.reactionFor(fromMe2 = false, emoji = "🎉"))
        assertNull(ChatMediaRouting.reactionFor(fromMe2 = true, emoji = "🎉"))
        assertNull(ChatMediaRouting.reactionFor(fromMe2 = false, emoji = " "))
    }
}
