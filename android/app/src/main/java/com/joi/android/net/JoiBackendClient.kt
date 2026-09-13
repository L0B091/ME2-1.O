package com.joi.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.joi.android.BuildConfig
import com.joi.android.data.LocalJoiMemory
import com.joi.android.data.UserSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class BackendAuthResult(
    val token: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val photoUrl: String?,
    val emailVerified: Boolean
)

data class BackendChatResult(
    val reply: String,
    val tone: String?,
    val rhythm: String?,
    val microExpression: String?,
    val premiumUntilMillis: Long?
)

data class PremiumStatusResult(
    val active: Boolean,
    val premiumUntilMillis: Long,
    val backupMaterial: String?
)

data class AlarmDispatchStage(
    val stage: Int,
    val offsetFromAlarmMs: Long,
    val channelId: String,
    val notificationType: String,
    val vibration: String,
    val sound: String,
    val title: String,
    val message: String
)

data class AlarmRecord(
    val id: String,
    val userId: String,
    val hour: String,
    val title: String,
    val message: String,
    val state: String,
    val stage: Int,
    val attempts: Int,
    val dispatchPlan: List<AlarmDispatchStage>
)

data class AlarmEventResult(
    val state: String,
    val message: String?,
    val nextStage: Int?,
    val alarm: AlarmRecord?
)

class JoiBackendClient {
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL.trim().trimEnd('/')
    private val betaPremiumMillis = 4102444800000L

    fun isConfigured(): Boolean = baseUrl.isNotEmpty()

    fun isOnline(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun authenticateWithGoogle(idToken: String): BackendAuthResult {
        val json = request(
            method = "POST",
            path = "/api/auth/google",
            body = JSONObject().put("idToken", idToken)
        )
        val data = json.optJSONObject("data") ?: json
        val profile = data.optJSONObject("profile") ?: JSONObject()
        return BackendAuthResult(
            token = data.getString("token"),
            userId = profile.optString("userId", data.optString("userId")),
            email = profile.optString("email"),
            displayName = profile.optString("displayName", "Usuario"),
            photoUrl = profile.optString("photoUrl").ifBlank { null },
            emailVerified = profile.optBoolean("emailVerified", false)
        )
    }

    fun registerLocal(email: String, password: String, displayName: String): BackendAuthResult {
        val json = request(
            method = "POST",
            path = "/api/auth/register",
            body = JSONObject().apply {
                put("email", email)
                put("password", password)
                put("displayName", displayName)
            }
        )
        return parseLocalAuthResult(json)
    }

    fun loginLocal(email: String, password: String): BackendAuthResult {
        val json = request(
            method = "POST",
            path = "/api/auth/login",
            body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
        )
        return parseLocalAuthResult(json)
    }

    fun sendChat(
        session: UserSession,
        memory: LocalJoiMemory,
        message: String,
        initiative: JSONObject? = null
    ): BackendChatResult {
        val json = request(
            method = "POST",
            path = "/chat",
            authToken = session.authToken,
            body = JSONObject().apply {
                put("mensaje", message)
                put("userId", session.id)
                put("contexto", JSONObject().apply {
                    put("clienteOficial", "android_nativo")
                    put("memoriaLocal", memory.toBackendContext())
                    if (initiative != null) put("iniciativa", initiative)
                })
            }
        )

        return BackendChatResult(
            reply = json.optString("respuesta", "ME2 recibió el mensaje, pero no devolvió texto."),
            tone = json.optJSONObject("expresion")?.optString("tono"),
            rhythm = json.optJSONObject("expresion")?.optString("ritmo"),
            microExpression = json.optJSONObject("expresion")?.optString("microexpresion"),
            premiumUntilMillis = parsePremiumMillis(json.optJSONObject("premium"))
        )
    }

    fun evaluateInitiative(
        session: UserSession,
        memory: LocalJoiMemory,
        state: JSONObject,
        events: JSONArray = JSONArray()
    ): JSONObject {
        val profile = JSONObject((state.optJSONObject("perfilRitmo") ?: JSONObject()).toString())
            .put("zonaHoraria", TimeZone.getDefault().id)
            .put("ultimaInteraccion", state.optLong("ultimaInteraccion"))
        val json = request(
            method = "POST",
            path = "/api/iniciativas/evaluar",
            authToken = session.authToken,
            body = JSONObject().apply {
                put("userId", session.id)
                put("memoriaLocal", memory.toBackendContext())
                put("registro", state.optJSONArray("registro") ?: JSONArray())
                put("perfilRitmo", profile)
                put("disponibilidad", JSONObject().put("enPrimerPlano", false).put("notificacionesHabilitadas", true))
                put("eventos", events)
            }
        )
        return json.getJSONObject("data")
    }

    fun fetchPremiumStatus(session: UserSession): PremiumStatusResult {
        val json = request(
            method = "GET",
            path = "/api/premium/${session.id}",
            authToken = session.authToken
        )
        val data = json.optJSONObject("data") ?: json
        return PremiumStatusResult(
            active = data.optBoolean("premiumActivo", false),
            premiumUntilMillis = parsePremiumMillis(data) ?: 0L,
            backupMaterial = data.optString("backupMaterial").ifBlank { null }
        )
    }

    fun fetchBackupMaterial(session: UserSession): String {
        val json = request(
            method = "GET",
            path = "/api/premium/${session.id}/backup/materials",
            authToken = session.authToken
        )
        return json.optJSONObject("data")?.optString("backupMaterial")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Material de respaldo no disponible")
    }

    fun uploadEncryptedBackup(session: UserSession, encryptedBackup: JSONObject) {
        request(
            method = "PUT",
            path = "/api/premium/${session.id}/backup",
            authToken = session.authToken,
            body = JSONObject().put("backup", encryptedBackup)
        )
    }

    fun downloadEncryptedBackup(session: UserSession): JSONObject? {
        val json = request(
            method = "GET",
            path = "/api/premium/${session.id}/backup",
            authToken = session.authToken
        )
        return json.optJSONObject("data")?.optJSONObject("backup")
    }

    fun listAlarms(session: UserSession): List<AlarmRecord> {
        val json = request(
            method = "GET",
            path = "/api/alarmas/${session.id}",
            authToken = session.authToken
        )
        return parseAlarmList(json.optJSONArray("data"))
    }

    fun createAlarm(session: UserSession, hour: String, title: String, message: String): AlarmRecord {
        val json = request(
            method = "POST",
            path = "/api/alarmas/${session.id}",
            authToken = session.authToken,
            body = JSONObject().apply {
                put("hora", hour)
                put("titulo", title)
                put("mensaje", message)
            }
        )
        return parseAlarmRecord(json.optJSONObject("data"))
            ?: throw IllegalStateException("Alarma inválida")
    }

    fun updateAlarm(session: UserSession, alarmId: String, hour: String, title: String, message: String): AlarmRecord {
        val json = request(
            method = "PATCH",
            path = "/api/alarmas/${session.id}/$alarmId",
            authToken = session.authToken,
            body = JSONObject().apply {
                put("hora", hour)
                put("titulo", title)
                put("mensaje", message)
            }
        )
        return parseAlarmRecord(json.optJSONObject("data"))
            ?: throw IllegalStateException("No se pudo actualizar la alarma")
    }

    fun cancelAlarm(session: UserSession, alarmId: String) {
        request(
            method = "DELETE",
            path = "/api/alarmas/${session.id}/$alarmId",
            authToken = session.authToken
        )
    }

    fun reportAlarmEvent(session: UserSession, alarmId: String, stage: Int, state: String): AlarmEventResult {
        val json = request(
            method = "POST",
            path = "/api/alarmas/${session.id}/$alarmId/evento",
            authToken = session.authToken,
            body = JSONObject().apply {
                put("stage", stage)
                put("estado", state)
            }
        )
        val data = json.optJSONObject("data") ?: JSONObject()
        return AlarmEventResult(
            state = data.optString("estado", state),
            message = data.optString("mensaje").ifBlank { null },
            nextStage = data.optInt("stageSiguiente").takeIf { it > 0 },
            alarm = parseAlarmRecord(data.optJSONObject("alarma"))
        )
    }

    private fun parseLocalAuthResult(json: JSONObject): BackendAuthResult {
        val profile = json.optJSONObject("perfil") ?: JSONObject()
        return BackendAuthResult(
            token = json.getString("token"),
            userId = profile.optString("userId"),
            email = profile.optString("email"),
            displayName = profile.optString("displayName", profile.optString("email", "Usuario")),
            photoUrl = profile.optString("photoUrl").ifBlank { null },
            emailVerified = true
        )
    }

    private fun parseAlarmList(array: JSONArray?): List<AlarmRecord> {
        if (array == null) return emptyList()
        val result = mutableListOf<AlarmRecord>()
        for (index in 0 until array.length()) {
            parseAlarmRecord(array.optJSONObject(index))?.let(result::add)
        }
        return result
    }

    private fun parseAlarmRecord(json: JSONObject?): AlarmRecord? {
        if (json == null || json.optString("id").isBlank()) return null
        val dispatchArray = json.optJSONArray("dispatchPlan")
        val dispatchPlan = mutableListOf<AlarmDispatchStage>()
        if (dispatchArray != null) {
            for (index in 0 until dispatchArray.length()) {
                val item = dispatchArray.optJSONObject(index) ?: continue
                dispatchPlan += AlarmDispatchStage(
                    stage = item.optInt("stage", 1),
                    offsetFromAlarmMs = item.optLong("offsetFromAlarmMs", 0L),
                    channelId = item.optString("channelId", "JOI_MESSAGES"),
                    notificationType = item.optString("notificationType", "message"),
                    vibration = item.optString("vibration", "double"),
                    sound = item.optString("sound", "bubble"),
                    title = item.optString("titulo", "Hora de despertar"),
                    message = item.optString("mensaje", "ME2 registró tu protocolo de despertar.")
                )
            }
        }
        return AlarmRecord(
            id = json.optString("id"),
            userId = json.optString("userID", json.optString("userId")),
            hour = json.optString("hora"),
            title = json.optString("titulo", "Hora de despertar"),
            message = json.optString("mensaje", "ME2 registró tu protocolo de despertar."),
            state = json.optString("estado", "ACTIVE"),
            stage = json.optInt("stage", 1),
            attempts = json.optInt("intentos", 0),
            dispatchPlan = dispatchPlan
        )
    }

    private fun parsePremiumMillis(data: JSONObject?): Long? {
        if (data == null) return null
        val parsed = parseIsoMillis(data.optString("premiumHasta"))
        if (parsed != null) return parsed
        return if (data.optBoolean("premiumActivo", false)) betaPremiumMillis else null
    }

    private fun request(
        method: String,
        path: String,
        authToken: String? = null,
        body: JSONObject? = null
    ): JSONObject {
        check(isConfigured()) { "BACKEND_BASE_URL no configurada." }
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ".plus(authToken))
            }
            doInput = true
            if (body != null) {
                doOutput = true
            }
        }

        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val responseJson = responseText.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()

            if (responseCode !in 200..299) {
                throw IllegalStateException(responseJson.optString("error").ifBlank {
                    "Error HTTP $responseCode"
                })
            }
            return responseJson
        } finally {
            connection.disconnect()
        }
    }

    private fun parseIsoMillis(rawValue: String?): Long? {
        if (rawValue.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX"
        )
        patterns.forEach { pattern ->
            try {
                return SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(rawValue)?.time
            } catch (_: ParseException) {
            }
        }
        return null
    }
}
