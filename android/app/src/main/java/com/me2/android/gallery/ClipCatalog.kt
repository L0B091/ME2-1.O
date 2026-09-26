package com.me2.android.gallery

import android.content.Context
import android.net.Uri
import android.util.Log
import com.me2.android.R
import java.io.File

/**
 * Local clip catalog: lists drop-in gallery + assets, falls back to res/raw demos.
 *
 * TODO(gallery-ui): wire a RecyclerView/grid to [listAll] when the gallery screen ships.
 * Until then MainActivity uses [listByMood] for avatar playback (offline-first).
 */
class ClipCatalog(context: Context) : GalleryRepository {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private val assetsRoot = ASSETS_VIDEOS_ROOT
    private val filesRoot = File(appContext.filesDir, FILES_GALLERY_ROOT)

    override fun ensureDirs() {
        MOODS.forEach { mood ->
            File(filesRoot, mood).mkdirs()
        }
    }

    override fun runtimeGalleryRoot(): File = filesRoot

    override fun listAll(): List<GalleryClip> =
        MOODS.flatMap { listByMood(it) }

    override fun listByMood(mood: String): List<GalleryClip> {
        val normalized = normalizeMood(mood)
        val fromFiles = scanFilesDir(normalized)
        if (fromFiles.isNotEmpty()) return fromFiles
        val fromAssets = scanAssets(normalized)
        if (fromAssets.isNotEmpty()) return fromAssets
        return demoRawClips(normalized)
    }

    override fun findById(id: String): GalleryClip? =
        listAll().firstOrNull { it.id == id }

    override fun playbackUri(clip: GalleryClip): Uri = clip.uri

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

    private fun demoRawClips(mood: String): List<GalleryClip> {
        val resIds = DEMO_RAW_BY_MOOD[mood].orEmpty()
        return resIds.mapNotNull { resId ->
            if (!isRawAvailable(resId)) return@mapNotNull null
            val name = runCatching { appContext.resources.getResourceEntryName(resId) }
                .getOrDefault("raw_$resId")
            GalleryClip(
                id = "raw:$name",
                mood = mood,
                displayName = name,
                uri = Uri.parse("android.resource://$packageName/$resId"),
                source = GalleryClip.Source.RAW,
                carriesVoice = mood == MOOD_PRESENTACION,
                rawResId = resId
            )
        }
    }

    private fun isRawAvailable(resId: Int): Boolean =
        runCatching {
            // Prefer entry-name resolve (works under Robolectric); Fd validates on device.
            appContext.resources.getResourceEntryName(resId)
            runCatching { appContext.resources.openRawResourceFd(resId)?.close() }
            true
        }.getOrDefault(false)

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

        private val DEMO_RAW_BY_MOOD: Map<String, List<Int>> = mapOf(
            MOOD_LOOP_NEUTRAL to listOf(
                R.raw.me2_texting,
                R.raw.avatar_calida_01,
                R.raw.avatar_atenta_01,
                R.raw.avatar_alegre_01,
                R.raw.avatar_aliviada_01,
                R.raw.avatar_agradecida_01
            ),
            MOOD_PRESENTACION to listOf(R.raw.avatar_presentacion_01),
            MOOD_CALIDA to listOf(R.raw.avatar_calida_01, R.raw.me2_texting),
            MOOD_ALEGRE to listOf(R.raw.avatar_alegre_01, R.raw.avatar_agradecida_01),
            MOOD_ATENTA to listOf(R.raw.avatar_atenta_01, R.raw.me2_texting),
            MOOD_ALIVIADA to listOf(R.raw.avatar_aliviada_01, R.raw.avatar_calida_01),
            MOOD_AGRADECIDA to listOf(R.raw.avatar_agradecida_01, R.raw.avatar_alegre_01)
        )

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
