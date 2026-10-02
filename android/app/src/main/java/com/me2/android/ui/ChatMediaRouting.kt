package com.me2.android.ui

/**
 * Reglas de medios del chat (puras, testeables):
 * - El contenedor de video del avatar SOLO reproduce clips de video (nunca GIF).
 * - Los GIF solo van como burbuja inline del AVATAR y solo con modo adulto activo en la sesión.
 * - La reacción emoji solo se pinta sobre burbujas del usuario.
 */
object ChatMediaRouting {
    data class Clip(val tipo: String, val fuente: String, val categoria: String?, val url: String?)
    data class Media(val tipo: String, val url: String?)

    /** URL remota para el contenedor del avatar, o null → usar la galería local por categoría. */
    fun avatarRemoteClipUrl(clip: Clip?, adultUnlocked: Boolean): String? {
        if (clip == null || clip.tipo != "clip") return null
        if (clip.fuente != "catalogo_adulto" || !adultUnlocked) return null
        return clip.url?.takeIf { it.isNotBlank() }
    }

    /** URL del GIF para una burbuja inline del avatar, o null. */
    fun inlineGifUrl(media: Media?, adultUnlocked: Boolean): String? {
        if (!adultUnlocked || media == null || media.tipo != "gif") return null
        return media.url?.takeIf { it.isNotBlank() }
    }

    fun reactionFor(fromMe2: Boolean, emoji: String?): String? = if (fromMe2) null else emoji?.takeIf { it.isNotBlank() }
}
