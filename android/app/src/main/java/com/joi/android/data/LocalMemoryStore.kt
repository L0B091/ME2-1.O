package com.joi.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class LocalConversationEntry(
    val role: String,
    val text: String,
    val timestamp: Long,
    val initiativeId: String? = null
)

data class LocalMemoryNote(
    val category: String,
    val text: String,
    val importance: Int,
    val timestamp: Long
)

data class LocalAssetMemory(
    val name: String,
    val summary: String,
    val timestamp: Long
)

data class LocalJoiMemory(
    val version: Int = 2,
    val userId: String,
    val characterName: String? = null,
    val preferredName: String? = null,
    val conversation: MutableList<LocalConversationEntry> = mutableListOf(),
    val shortTermFocus: String = "general",
    val shortTermIntent: String = "acompanar",
    val persistentMemories: MutableList<LocalMemoryNote> = mutableListOf(),
    val importantMemories: MutableList<LocalMemoryNote> = mutableListOf(),
    val codeMemories: MutableList<LocalAssetMemory> = mutableListOf(),
    val fiscalMemories: MutableList<LocalAssetMemory> = mutableListOf(),
    val updatedAt: Long = System.currentTimeMillis(),
    val hiddenConversationThrough: Long = 0L
) {
    fun withUpdatedTimestamp() = copy(updatedAt = System.currentTimeMillis())

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("userId", userId)
        characterName?.let { put("characterName", it) }
        preferredName?.let { put("preferredName", it) }
        put("shortTermFocus", shortTermFocus)
        put("shortTermIntent", shortTermIntent)
        put("updatedAt", updatedAt)
        put("hiddenConversationThrough", hiddenConversationThrough)
        put("conversation", JSONArray().apply {
            conversation.forEach { entry ->
                put(JSONObject().apply {
                    put("role", entry.role)
                    put("text", entry.text)
                    put("timestamp", entry.timestamp)
                    entry.initiativeId?.let { put("initiativeId", it) }
                })
            }
        })
        put("persistentMemories", Companion.notesToJson(persistentMemories))
        put("importantMemories", Companion.notesToJson(importantMemories))
        put("codeMemories", Companion.assetsToJson(codeMemories))
        put("fiscalMemories", Companion.assetsToJson(fiscalMemories))
    }

    fun toBackendContext(): JSONObject = JSONObject().apply {
        put("source", "android_local_primary")
        put("version", version)
        characterName?.let { put("characterName", it) }
        put("shortTermFocus", shortTermFocus)
        put("shortTermIntent", shortTermIntent)
        preferredName?.let { put("preferredName", it) }
        put("updatedAt", updatedAt)
        put("recentConversation", JSONArray().apply {
            conversation.takeLast(20).forEach { entry ->
                put(JSONObject().apply {
                    put("role", entry.role)
                    put("text", entry.text)
                    put("timestamp", entry.timestamp)
                })
            }
        })
        put("persistentMemories", Companion.notesToJson(persistentMemories.takeLast(12)))
        put("importantMemories", Companion.notesToJson(importantMemories.takeLast(8)))
        put("codeMemories", Companion.assetsToJson(codeMemories.takeLast(8)))
        put("fiscalMemories", Companion.assetsToJson(fiscalMemories.takeLast(8)))
    }

    companion object {
        fun fromJson(json: JSONObject): LocalJoiMemory {
            return LocalJoiMemory(
                version = json.optInt("version", 1).coerceAtLeast(1),
                userId = json.optString("userId"),
                characterName = json.optString("characterName").ifBlank { null },
                preferredName = json.optString("preferredName").ifBlank { null },
                conversation = jsonArrayToConversation(json.optJSONArray("conversation")),
                shortTermFocus = json.optString("shortTermFocus", "general"),
                shortTermIntent = json.optString("shortTermIntent", "acompanar"),
                persistentMemories = jsonArrayToNotes(json.optJSONArray("persistentMemories")),
                importantMemories = jsonArrayToNotes(json.optJSONArray("importantMemories")),
                codeMemories = jsonArrayToAssets(json.optJSONArray("codeMemories")),
                fiscalMemories = jsonArrayToAssets(json.optJSONArray("fiscalMemories")),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                hiddenConversationThrough = json.optLong("hiddenConversationThrough", 0L)
            )
        }

        internal fun notesToJson(notes: List<LocalMemoryNote>): JSONArray = JSONArray().apply {
            notes.forEach { note ->
                put(JSONObject().apply {
                    put("category", note.category)
                    put("text", note.text)
                    put("importance", note.importance)
                    put("timestamp", note.timestamp)
                })
            }
        }

        internal fun assetsToJson(assets: List<LocalAssetMemory>): JSONArray = JSONArray().apply {
            assets.forEach { asset ->
                put(JSONObject().apply {
                    put("name", asset.name)
                    put("summary", asset.summary)
                    put("timestamp", asset.timestamp)
                })
            }
        }

        private fun jsonArrayToConversation(array: JSONArray?): MutableList<LocalConversationEntry> {
            val result = mutableListOf<LocalConversationEntry>()
            if (array == null) return result
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result += LocalConversationEntry(
                    role = item.optString("role", "user"),
                    text = item.optString("text", ""),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                    initiativeId = item.optString("initiativeId").takeIf { it.isNotBlank() }
                )
            }
            return result
        }

        private fun jsonArrayToNotes(array: JSONArray?): MutableList<LocalMemoryNote> {
            val result = mutableListOf<LocalMemoryNote>()
            if (array == null) return result
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result += LocalMemoryNote(
                    category = item.optString("category", "general"),
                    text = item.optString("text", ""),
                    importance = item.optInt("importance", 1),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }
            return result
        }

        private fun jsonArrayToAssets(array: JSONArray?): MutableList<LocalAssetMemory> {
            val result = mutableListOf<LocalAssetMemory>()
            if (array == null) return result
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result += LocalAssetMemory(
                    name = item.optString("name", ""),
                    summary = item.optString("summary", ""),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }
            return result
        }
    }
}

class LocalMemoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = JoiMemoryDatabase.getInstance(appContext)
    private val dao = database.memoryDao()
    private val localVault = LocalVault(appContext)
    private val legacyRoot = File(appContext.filesDir, "joi_memory")

    fun observe(userId: String) = dao.observeByUserId(userId)

    fun load(userId: String): LocalJoiMemory {
        migrateLegacyIfNeeded(userId)
        val record = dao.findByUserId(userId) ?: return LocalJoiMemory(userId = userId)
        return localVault.decrypt(record.ivBase64, record.payloadBase64)
            ?.let { LocalJoiMemory.fromJson(JSONObject(it)) }
            ?.copy(userId = userId)
            ?: LocalJoiMemory(userId = userId)
    }

    fun save(memory: LocalJoiMemory) {
        val updated = memory.withUpdatedTimestamp()
        val encrypted = localVault.encrypt(updated.toJson().toString())
        dao.upsert(
            JoiMemoryRecordEntity(
                userId = updated.userId,
                payloadBase64 = encrypted.payloadBase64,
                ivBase64 = encrypted.ivBase64,
                schemaVersion = updated.version,
                updatedAt = updated.updatedAt
            )
        )
    }

    fun replace(memory: LocalJoiMemory) = save(memory)

    fun migrateUserMemory(fromUserId: String, toUserId: String) {
        if (fromUserId == toUserId) return
        migrateLegacyIfNeeded(fromUserId)
        if (!isEffectivelyEmpty(toUserId)) return
        val sourceMemory = load(fromUserId)
        if (isEffectivelyEmpty(fromUserId)) return
        save(sourceMemory.copy(userId = toUserId))
        dao.deleteByUserId(fromUserId)
        legacyFile(fromUserId).takeIf(File::exists)?.delete()
    }

    fun isEffectivelyEmpty(userId: String): Boolean {
        val memory = load(userId)
        return memory.conversation.isEmpty() &&
            memory.characterName.isNullOrBlank() &&
            memory.preferredName.isNullOrBlank() &&
            memory.persistentMemories.isEmpty() &&
            memory.importantMemories.isEmpty() &&
            memory.codeMemories.isEmpty() &&
            memory.fiscalMemories.isEmpty()
    }

    fun appendUserMessage(userId: String, rawText: String) {
        val memory = load(userId)
        val text = rawText.trim()
        if (text.isEmpty()) return
        val characterName = extractCharacterName(text, memory) ?: memory.characterName
        val updated = memory.copy(
            characterName = characterName,
            conversation = (memory.conversation + LocalConversationEntry("user", text, nextMessageTime(memory)))
                .takeLast(200)
                .toMutableList(),
            shortTermFocus = inferFocus(text),
            shortTermIntent = inferIntent(text),
            persistentMemories = mergeNotes(memory.persistentMemories, extractPersistentNote(text)),
            importantMemories = mergeNotes(memory.importantMemories, extractImportantNote(text))
        )
        save(updated)
    }

    fun appendAssistantMessage(userId: String, rawText: String) {
        val memory = load(userId)
        val text = rawText.trim()
        if (text.isEmpty()) return
        val updated = memory.copy(
            conversation = (memory.conversation + LocalConversationEntry("assistant", text, nextMessageTime(memory)))
                .takeLast(200)
                .toMutableList()
        )
        save(updated)
    }

    fun appendInitiativeMessage(userId: String, initiativeId: String, text: String) {
        val memory = load(userId)
        if (memory.conversation.any { it.initiativeId == initiativeId }) return
        require(text.isNotBlank())
        val entry = LocalConversationEntry("assistant", text, nextMessageTime(memory), initiativeId)
        save(memory.copy(conversation = (memory.conversation + entry).takeLast(200).toMutableList()))
    }

    fun clearConversationDisplay(userId: String) {
        val memory = load(userId)
        save(memory.copy(hiddenConversationThrough = maxOf(
            memory.hiddenConversationThrough, memory.conversation.maxOfOrNull { it.timestamp } ?: 0L
        )))
    }

    private fun nextMessageTime(memory: LocalJoiMemory): Long =
        maxOf(System.currentTimeMillis(), (memory.conversation.lastOrNull()?.timestamp ?: 0L) + 1L, memory.hiddenConversationThrough + 1L)

    private fun migrateLegacyIfNeeded(userId: String) {
        if (dao.findByUserId(userId) != null) return
        val file = legacyFile(userId)
        if (!file.exists()) return
        runCatching {
            val legacy = LocalJoiMemory.fromJson(JSONObject(file.readText())).copy(userId = userId, version = 2)
            save(legacy)
            file.delete()
        }
    }

    private fun legacyFile(userId: String): File {
        legacyRoot.mkdirs()
        val safeUserId = userId.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
        return File(legacyRoot, "$safeUserId.json")
    }

    private fun inferFocus(text: String): String {
        val normalized = text.lowercase(Locale.US)
        return when {
            "código" in normalized || "codigo" in normalized || "program" in normalized -> "codigo"
            "factura" in normalized || "impuesto" in normalized || "fiscal" in normalized -> "fiscal"
            "agenda" in normalized || "alarma" in normalized || "record" in normalized -> "organizacion"
            "premium" in normalized || "pago" in normalized -> "premium"
            else -> "general"
        }
    }

    private fun inferIntent(text: String): String {
        val normalized = text.lowercase(Locale.US)
        return when {
            normalized.contains("?") || normalized.startsWith("qué") || normalized.startsWith("como") -> "resolver"
            "recuerda" in normalized || "acuérdate" in normalized || "anota" in normalized -> "recordar"
            else -> "acompanar"
        }
    }

    private fun extractCharacterName(text: String, memory: LocalJoiMemory): String? {
        extractCharacterNameFromPattern(text)?.let { return it }
        val lastAssistantMessage = memory.conversation.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        return if (CHARACTER_NAME_PROMPT in lastAssistantMessage) {
            normalizeCharacterName(text)
        } else {
            null
        }
    }

    private fun extractCharacterNameFromPattern(text: String): String? {
        val patterns = listOf(
            "quiero que te llames ",
            "quiero llamarte ",
            "te voy a llamar ",
            "voy a llamarte ",
            "quiero ponerte ",
            "quiero darte ",
            "tu nombre va a ser ",
            "tu nombre será ",
            "tu nombre sera ",
            "vas a llamarte ",
            "te llamaré ",
            "te llamare "
        )
        val lowered = text.lowercase(Locale.US).trim()
        for (pattern in patterns) {
            val index = lowered.indexOf(pattern)
            if (index < 0) continue
            val value = text.substring(index + pattern.length)
            normalizeCharacterName(trimCharacterName(value))?.let { return it }
        }
        return null
    }

    private fun trimCharacterName(raw: String): String {
        val lowered = raw.lowercase(Locale.US)
        val separators = listOf(" por ", " y además", " y ademas", " pero ", " porque ", ",", ".", "?", "!", ";", ":")
        var cut = raw.length
        for (separator in separators) {
            val index = lowered.indexOf(separator)
            if (index >= 0 && index < cut) cut = index
        }
        return raw.substring(0, cut)
    }

    private fun normalizeCharacterName(raw: String): String? {
        val cleaned = raw.trim()
            .trim('"', '\'', '“', '”', '‘', '’', '.', ',', ';', ':', '!', '?', '…')
            .replace(Regex("""\s+"""), " ")
        if (cleaned.isBlank() || cleaned.length > 40) return null
        if (!cleaned.matches(Regex("""[A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9][A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9 '\-_.]{0,39}"""))) {
            return null
        }
        val lowered = cleaned.lowercase(Locale.US)
        if (lowered in setOf(
                "joi", "me2", "hola", "holi", "buenas", "gracias", "ninguno",
                "como quieras", "da igual", "sin nombre", "ningún nombre", "ningun nombre",
                "nombre", "un nombre", "nickname", "un nickname", "apodo", "un apodo"
            )
        ) return null
        if (cleaned.split(" ").size > 4) return null
        return cleaned.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
    }

    private fun extractPersistentNote(text: String): LocalMemoryNote? {
        val normalized = text.lowercase(Locale.US)
        val shouldPersist = listOf(
            "me gusta",
            "prefiero",
            "trabajo",
            "estoy construyendo",
            "proyecto",
            "mi empresa",
            "mi horario"
        ).any { normalized.contains(it) }
        if (!shouldPersist) return null
        return LocalMemoryNote(
            category = inferFocus(text),
            text = text.trim(),
            importance = 2,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun extractImportantNote(text: String): LocalMemoryNote? {
        val normalized = text.lowercase(Locale.US)
        val shouldPersist = listOf(
            "recuérd",
            "importante",
            "no olvides",
            "mi nombre es",
            "cumpleaños",
            "google"
        ).any { normalized.contains(it) }
        if (!shouldPersist) return null
        return LocalMemoryNote(
            category = "importante",
            text = text.trim(),
            importance = 3,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun mergeNotes(existing: MutableList<LocalMemoryNote>, candidate: LocalMemoryNote?): MutableList<LocalMemoryNote> {
        if (candidate == null) return existing
        val deduped = existing.filterNot { it.text.equals(candidate.text, ignoreCase = true) }
        return (deduped + candidate).takeLast(64).toMutableList()
    }

    companion object {
        private const val CHARACTER_NAME_PROMPT = "¿Qué nombre o nickname querés que tenga?"
    }
}
