package com.me2.android.gallery

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Local clip catalog: lists drop-in gallery + assets/videos, falls back to the bundled ME2_MEDIA library
 * (fuente única de clips de la app; ya no hay copias mp4 en res/raw).
 *
 * TODO(gallery-ui): wire a RecyclerView/grid to [listAll] when the gallery screen ships.
 * Until then MainActivity uses [listByMood] for avatar playback (offline-first).
 */
class ClipCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private val assetsRoot = ASSETS_VIDEOS_ROOT
    private val filesRoot = File(appContext.filesDir, FILES_GALLERY_ROOT)

    fun ensureDirs() {
        MOODS.forEach { mood ->
            File(filesRoot, mood).mkdirs()
        }
    }

    fun runtimeGalleryRoot(): File = filesRoot

    fun listAll(): List<GalleryClip> =
        MOODS.flatMap { listByMood(it) }

    fun listByMood(mood: String): List<GalleryClip> {
        val normalized = normalizeMood(mood)
        val fromFiles = scanFilesDir(normalized)
        if (fromFiles.isNotEmpty()) return fromFiles
        val fromAssets = scanAssets(normalized)
        if (fromAssets.isNotEmpty()) return fromAssets
        return demoMediaClips(normalized)
    }

    fun findById(id: String): GalleryClip? =
        listAll().firstOrNull { it.id == id }

    fun playbackUri(clip: GalleryClip): Uri = clip.uri

    fun nextClip(gallery: List<GalleryClip>, previousId: String?): GalleryClip? {
        if (gallery.isEmpty()) return null
        if (gallery.size == 1 || previousId == null) return gallery.first()
        val previousIndex = gallery.indexOfFirst { it.id == previousId }.takeIf { it >= 0 }
            ?: return gallery.first()
        return gallery[(previousIndex + 1) % gallery.size]
    }

    fun containsClip(gallery: List<GalleryClip>, clipId: String?): Boolean =
        clipId != null && gallery.any { it.id == clipId }

    private fun scanFilesDir(mood: String): List<GalleryClip> {
        val dir = File(filesRoot, mood)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && isVideoFile(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { file ->
                GalleryClip(
                    id = "files:$mood/${file.name}",
                    mood = mood,
                    displayName = file.nameWithoutExtension,
                    uri = Uri.fromFile(file),
                    source = GalleryClip.Source.FILES_DIR,
                    carriesVoice = mood == MOOD_PRESENTACION
                )
            }
            .orEmpty()
    }

    private fun scanAssets(mood: String): List<GalleryClip> {
        val path = "$assetsRoot/$mood"
        val names = runCatching {
            appContext.assets.list(path)?.toList().orEmpty()
        }.getOrElse {
            Log.w(TAG, "assets list failed for $path: ${it.message}")
            emptyList()
        }
        return names
            .filter(::isVideoFile)
            .sortedBy { it.lowercase() }
            .map { name ->
                GalleryClip(
                    id = "assets:$mood/$name",
                    mood = mood,
                    displayName = name.substringBeforeLast('.'),
                    uri = Uri.parse("asset:///$path/$name"),
                    source = GalleryClip.Source.ASSETS,
                    carriesVoice = mood == MOOD_PRESENTACION
                )
            }
    }

    /** Respaldo offline: clips de assets/ME2_MEDIA (mismo id que MediaLibrary.toClip → anti-repetición coherente). */
    private fun demoMediaClips(mood: String): List<GalleryClip> =
        DEMO_MEDIA_BY_MOOD[mood].orEmpty().filter(::isAssetAvailable).map { archivo ->
            GalleryClip(
                id = "me2:$archivo",
                mood = mood,
                displayName = archivo.substringAfterLast('/').substringBeforeLast('.'),
                uri = Uri.parse("asset:///$MEDIA_ROOT/$archivo"),
                source = GalleryClip.Source.ASSETS,
                carriesVoice = mood == MOOD_PRESENTACION
            )
        }

    private fun isAssetAvailable(archivo: String): Boolean =
        runCatching { appContext.assets.open("$MEDIA_ROOT/$archivo").close(); true }.getOrDefault(false)

    companion object {
        private const val TAG = "Me2ClipCatalog"

        const val ASSETS_VIDEOS_ROOT = "videos"
        const val FILES_GALLERY_ROOT = "gallery"

        const val MOOD_LOOP_NEUTRAL = "loop_neutral"
        const val MOOD_PRESENTACION = "presentacion"
        const val MOOD_CALIDA = "calida"
        const val MOOD_ALEGRE = "alegre"
        const val MOOD_ATENTA = "atenta"
        const val MOOD_ALIVIADA = "aliviada"
        const val MOOD_AGRADECIDA = "agradecida"

        val MOODS = listOf(
            MOOD_LOOP_NEUTRAL,
            MOOD_PRESENTACION,
            MOOD_CALIDA,
            MOOD_ALEGRE,
            MOOD_ATENTA,
            MOOD_ALIVIADA,
            MOOD_AGRADECIDA
        )

        const val MEDIA_ROOT = "ME2_MEDIA"
        private const val NEUTRAL = "01_LOOP_NEUTRAL/NEUTRAL_001.mp4"
        private val NEUTRALES = listOf("001", "002", "004", "005", "006").map { "01_LOOP_NEUTRAL/NEUTRAL_$it.mp4" }
        private const val ESCUCHANDO = "03_CONVERSACION/ESCUCHANDO/ESCUCHANDO_001.mp4"
        private const val PENSANDO = "03_CONVERSACION/PENSANDO/PENSANDO_001.mp4"
        private const val ALEGRIA = "02_REACCIONES/ALEGRIA/ALEGRIA_MEDIO_001.mp4"
        private const val EMPATIA = "02_REACCIONES/EMPATIA/EMPATIA_NORMAL_001.mp4"
        private const val AFECTO = "02_REACCIONES/AFECTO/AFECTO_NORMAL_001.mp4"
        private const val ALIVIO = "02_REACCIONES/ALIVIO/ALIVIO_NORMAL_001.mp4"
        private const val GRATITUD = "02_REACCIONES/GRATITUD/GRATITUD_NORMAL_001.mp4"

        /** Respaldo por mood (res/raw no se usa): rutas reales de ME2_MEDIA (galería v4). */
        private val DEMO_MEDIA_BY_MOOD: Map<String, List<String>> = mapOf(
            MOOD_LOOP_NEUTRAL to NEUTRALES,
            MOOD_PRESENTACION to listOf("00_PRESENTACION/PRESENTACION_001.mp4", "00_PRESENTACION/PRESENTACION_002.mp4", "00_PRESENTACION/PRESENTACION_003.mp4"),
            MOOD_CALIDA to listOf(NEUTRAL, AFECTO),
            MOOD_ALEGRE to listOf(ALEGRIA, AFECTO),
            MOOD_ATENTA to listOf(ESCUCHANDO, PENSANDO),
            MOOD_ALIVIADA to listOf(ALIVIO, EMPATIA),
            MOOD_AGRADECIDA to listOf(GRATITUD, AFECTO)
        )

        /** Todas las rutas del respaldo (para verificar que existen en assets). */
        val ALL_DEMO_PATHS: Set<String> get() = DEMO_MEDIA_BY_MOOD.values.flatten().toSet()

        fun normalizeMood(mood: String): String {
            val key = mood.trim().lowercase().replace('-', '_').replace(' ', '_')
            return when {
                key in MOODS -> key
                key.contains("present") -> MOOD_PRESENTACION
                key.contains("agradec") -> MOOD_AGRADECIDA
                key.contains("alegre") || key.contains("happy") -> MOOD_ALEGRE
                key.contains("atenta") || key.contains("listen") -> MOOD_ATENTA
                key.contains("alivi") || key.contains("calma") -> MOOD_ALIVIADA
                key.contains("calida") || key.contains("warm") -> MOOD_CALIDA
                key.contains("loop") || key.contains("neutral") || key.contains("demo") -> MOOD_LOOP_NEUTRAL
                else -> MOOD_LOOP_NEUTRAL
            }
        }

        private fun isVideoFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv")
        }
    }
}
