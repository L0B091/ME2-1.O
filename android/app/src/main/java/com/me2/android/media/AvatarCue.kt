package com.me2.android.media

import java.util.Locale

/** Pista audiovisual que decide el orquestador del backend: categoría + subcategoría + intensidad (sin archivos). */
data class AudiovisualCue(val categoria: String, val subcategoria: String? = null, val intensidad: String? = null)

/** Traduce pistas del backend (nuevas o legacy tono/etiqueta) a un [MediaRequest]. Sin nombres de archivos. */
object AvatarCueMapper {
    fun fromCue(cue: AudiovisualCue?): MediaRequest? {
        cue ?: return null
        val cat = MediaCategoria.fromCarpeta(cue.categoria)?.takeIf { it != MediaCategoria.PRESENTACION } ?: return null
        val intensidad = cue.intensidad?.let { v -> MediaIntensidad.entries.firstOrNull { it.name == MediaNameParser.normalizar(v) } }
        return MediaRequest(cat, cue.subcategoria?.ifBlank { null }, intensidad)
    }

    /** Reacción adulta: primero 07_PREMIUM/ADULTO (si el permiso lo habilita), sin último recurso genérico. */
    fun adultRequest(intensityToken: String): MediaRequest? {
        val t = intensityToken.uppercase(Locale.ROOT)
        val nivel = when {
            t.contains("EXPLICIT") || t.contains("INTIMATE") -> MediaIntensidad.MAXIMO
            t.contains("SUGGESTIVE") -> MediaIntensidad.MEDIO
            t.contains("SOFT") || t.contains("FLIRT") -> MediaIntensidad.NORMAL
            else -> return null
        }
        return MediaRequest(MediaCategoria.PREMIUM, "ADULTO", nivel, fallbacks = emptyList(), ultimoRecurso = false)
    }

    /** Equivalente no adulto de la reacción adulta (si no hay recurso adulto disponible). */
    fun adultFallback(adult: MediaRequest): MediaRequest = MediaRequest(MediaCategoria.REACCION, "COQUETA", adult.intensidad)

    fun fromLegacy(state: String, detail: String): MediaRequest? {
        val s = state.uppercase(Locale.ROOT)
        val d = detail.uppercase(Locale.ROOT)
        val tokens = "$s $d"
        return when {
            s == "DEMO" -> MediaRequest(MediaCategoria.LOOP_NEUTRAL)
            s == "OFFLINE" -> MediaRequest(MediaCategoria.SISTEMA, "SIN_CONEXION")
            s == "ERROR" -> MediaRequest(MediaCategoria.SISTEMA, "ERROR")
            s == "AWAKE" -> MediaRequest(MediaCategoria.DESPERTADOR, "POST_ALARMA")
            s == "NOTICE" -> null
            d == "LOCAL" || d == "SYNC" || d == "MESSAGE" || d.startsWith("STAGE_") -> null
            tokens.contains("AGRADEC") -> MediaRequest(MediaCategoria.REACCION, "AFECTO", MediaIntensidad.MEDIO)
            tokens.contains("ALEGRE") || tokens.contains("HAPPY") || tokens.contains("FELIZ") || tokens.contains("SONRISA") ->
                MediaRequest(MediaCategoria.REACCION, "ALEGRIA", MediaIntensidad.NORMAL)
            tokens.contains("ATENTA") || tokens.contains("LISTENING") || tokens.contains("THINK") ->
                MediaRequest(MediaCategoria.CONVERSACION, "ATENCION")
            tokens.contains("TRISTE") -> MediaRequest(MediaCategoria.REACCION, "EMPATIA", MediaIntensidad.NORMAL)
            tokens.contains("ALIVIADA") || tokens.contains("CALMA") -> MediaRequest(MediaCategoria.REACCION, "ALIVIO", MediaIntensidad.NORMAL)
            s.isNotBlank() || d.isNotBlank() -> MediaRequest(MediaCategoria.REACCION, "AFECTO", MediaIntensidad.NORMAL)
            else -> null
        }
    }
}
