package com.me2.android.media

/**
 * Metadatos de un recurso audiovisual (equivalente conceptual al JSON de la Biblioteca Audiovisual V1).
 * Se deriva de la ruta + nombre del archivo ([MediaNameParser]); `ME2_MEDIA/metadata.json` puede sobrescribir campos.
 */
data class MediaResource(
    val id: String,
    /** Ruta relativa a la raíz ME2_MEDIA (p. ej. "02_REACCIONES/ALEGRIA/ALEGRIA_MEDIO_001.mp4"). */
    val archivo: String,
    val categoria: MediaCategoria,
    val subcategoria: String?,
    val intensidad: MediaIntensidad?,
    val variante: Int,
    val tipo: MediaTipo,
    val duracionMs: Long? = null,
    val loop: Boolean = false,
    val audio: Boolean = true,
    val premium: Boolean = false,
    val adulto: Boolean = false,
    val widget: Boolean = false,
    val alarma: Boolean = false,
    val prioridad: Int = 1,
    val habilitado: Boolean = true,
    val origen: Origen = Origen.ASSETS
) {
    enum class Origen { ASSETS, FILES_DIR }
}

enum class MediaCategoria(val carpetas: Set<String>) {
    PRESENTACION(setOf("PRESENTACION")),
    LOOP_NEUTRAL(setOf("LOOP_NEUTRAL", "NEUTRAL")),
    REACCION(setOf("REACCIONES", "REACCION")),
    CONVERSACION(setOf("CONVERSACION")),
    WIDGET(setOf("WIDGET")),
    DESPERTADOR(setOf("DESPERTADOR", "ALARMA")),
    TRANSICION(setOf("TRANSICIONES", "TRANSICION")),
    PREMIUM(setOf("PREMIUM")),
    SISTEMA(setOf("SISTEMA")),
    /** 09_SIN_CONEXION: loop de reposo SOLO sin red (reemplaza a 01_LOOP_NEUTRAL mientras no hay conexión). */
    SIN_CONEXION(setOf("SIN_CONEXION"));

    companion object {
        /** "02_REACCIONES" → REACCION (el prefijo numérico solo ordena carpetas). */
        fun fromCarpeta(nombre: String): MediaCategoria? {
            val key = MediaNameParser.normalizar(nombre).replace(Regex("^\\d+_"), "")
            return entries.firstOrNull { key in it.carpetas }
        }
    }
}

enum class MediaIntensidad { NORMAL, MEDIO, MAXIMO }

enum class MediaTipo { VIDEO, GIF, IMAGEN, AUDIO }
