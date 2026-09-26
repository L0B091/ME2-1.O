package com.me2.android

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.me2.android.BuildConfig
import com.me2.android.data.AvatarWidgetScene
import com.me2.android.data.ChatMessage
import com.me2.android.data.LocalMemoryStore
import com.me2.android.data.PremiumBackupCrypto
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import com.me2.android.databinding.ActivityMainBinding
import com.me2.android.net.Me2BackendClient
import com.me2.android.notifications.Me2AlarmScheduler
import com.me2.android.notifications.Me2NotificationChannels
import com.me2.android.notifications.Me2NotificationCoordinator
import com.me2.android.notifications.Me2InitiativeScheduler
import com.me2.android.notifications.Me2InitiativeStore
import com.me2.android.ui.ChatAdapter
import com.me2.android.widget.Me2HomeWidgetProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private enum class AvatarMode { LOOP_NEUTRAL, CONTEXTUAL, PRESENTATION }

    private data class AvatarSelection(
        val label: String,
        val gallery: IntArray
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionStorage: SessionStorage
    private lateinit var localMemoryStore: LocalMemoryStore
    private lateinit var alarmScheduler: Me2AlarmScheduler
    private lateinit var notificationCoordinator: Me2NotificationCoordinator
    private lateinit var initiativeStore: Me2InitiativeStore
    private lateinit var initiativeScheduler: Me2InitiativeScheduler
    private lateinit var chatAdapter: ChatAdapter

    private val fullConversation = mutableListOf<ChatMessage>()
    private val visibleConversation = mutableListOf<ChatMessage>()
    private val backendClient = Me2BackendClient()
    private val premiumBackupCrypto = PremiumBackupCrypto()

    private var player: ExoPlayer? = null
    private var currentAvatarClipResId: Int? = null
    private var lastAvatarClipResId: Int? = null
    private var avatarMode: AvatarMode = AvatarMode.LOOP_NEUTRAL
    private var currentAvatarGallery: IntArray = intArrayOf(R.raw.me2_texting)
    private var hasPlayedPresentation = false
    private var sessionStartedAt: Long = 0L
    private lateinit var currentSession: UserSession
    private var startedFromEmptyLocalMemory: Boolean = false
    private var backupMaterial: String? = null
    private val checkInHandler = Handler(Looper.getMainLooper())
    private val checkInTick = object : Runnable {
        override fun run() {
            maybeAskSilenceCheckIn()
            checkInHandler.postDelayed(this, 30_000L)
        }
    }

    private val loopNeutralGallery = intArrayOf(R.raw.me2_texting)
    private val presentationGallery = intArrayOf(R.raw.avatar_presentacion_01)
    private val calidaGallery = intArrayOf(R.raw.avatar_calida_01)
    private val alegreGallery = intArrayOf(R.raw.avatar_alegre_01)
    private val atentaGallery = intArrayOf(R.raw.avatar_atenta_01)
    private val aliviadaGallery = intArrayOf(R.raw.avatar_aliviada_01)
    private val agradecidaGallery = intArrayOf(R.raw.avatar_agradecida_01)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, getString(R.string.notification_permission_needed), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStorage = SessionStorage(this)
        val session = sessionStorage.loadUser()
        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        currentSession = session

        localMemoryStore = LocalMemoryStore(this)
        alarmScheduler = Me2AlarmScheduler(this)
        notificationCoordinator = Me2NotificationCoordinator(this)
        initiativeStore = Me2InitiativeStore(this)
        initiativeScheduler = Me2InitiativeScheduler(this)

        Me2NotificationChannels.ensure(this)
        ensureNotificationPermission()

        setupToolbar()
        setupChat()
        setupBitacora(currentSession)
        setupWidget()
        setupVideo()

        startedFromEmptyLocalMemory = localMemoryStore.isEffectivelyEmpty(currentSession.id)
        val launchedWithEvent = intent?.getStringExtra(Me2NotificationCoordinator.EXTRA_EVENT_TYPE) != null
        hydrateConversation()
        if (!launchedWithEvent) maybePlayPresentation()
        localMemoryStore.observe(currentSession.id).observe(this) { record ->
            if (record != null) hydrateConversation()
        }
        syncPremiumState()
        handleIncomingIntent(intent)
        syncBackendAlarms()
        if (initiativeStore.isEnabled(currentSession.id)) {
            initiativeScheduler.ensureScheduled()
            if (initiativeStore.snapshot(currentSession.id).optLong("ultimaInteraccion", 0L) <= 0L) {
                initiativeStore.observeInteraction(currentSession.id)
            }
        }
        checkInHandler.postDelayed(checkInTick, 30_000L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        sessionStartedAt = SystemClock.elapsedRealtime()
        restoreAvatarPresence(forceReload = currentAvatarClipResId == null)
        player?.playWhenReady = true
        player?.volume = 1f
        if (::currentSession.isInitialized) {
            // Returning to foreground cancels a pending post-silence eval only if user is active;
            // observeInteraction is reserved for real chat/widget interactions.
            maybeAskSilenceCheckIn()
        }
    }

    override fun onPause() {
        super.onPause()
        if (sessionStartedAt > 0L) {
            val elapsedMinutes = ((SystemClock.elapsedRealtime() - sessionStartedAt) / 60000L).coerceAtLeast(0L)
            if (elapsedMinutes > 0L) {
                sessionStorage.addUsageMinutes(elapsedMinutes)
            }
        }
        player?.playWhenReady = false
        if (::currentSession.isInitialized && sessionStorage.loadUser()?.id == currentSession.id &&
            initiativeStore.isEnabled(currentSession.id)
        ) {
            // Leaving the app starts the 1-hour countdown toward server eval (server may ESPERAR).
            initiativeStore.markPostSilenceEvalArmed(currentSession.id)
            initiativeScheduler.schedulePostSilenceEval()
        }
    }

    override fun onDestroy() {
        checkInHandler.removeCallbacks(checkInTick)
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun setupToolbar() {
        binding.drawerLayout.setScrimColor(ContextCompat.getColor(this, R.color.me2_drawer_scrim))
        binding.bitacoraButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.clearChatButton.setOnClickListener {
            localMemoryStore.clearConversationDisplay(currentSession.id)
            initiativeStore.clearActiveContext(currentSession.id)
            visibleConversation.clear()
            chatAdapter.submitList(visibleConversation.toList())
            binding.avatarStateText.text = "STATE // STANDBY"
            Toast.makeText(this, "PANTALLA LIMPIA", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatRecyclerView.adapter = chatAdapter
        binding.sendButton.setOnClickListener { sendMessage() }
    }

    private fun setupBitacora(session: UserSession) {
        binding.userNameText.text = "MAIL // ${session.email.uppercase(Locale.getDefault())}"
        val planTag = when {
            session.isDemo -> getString(R.string.demo_session_plan)
            session.isPremium -> "PREMIUM"
            else -> "FREE"
        }
        binding.userIdText.text = "PLAN // $planTag"
        binding.linkText.text = "ENLACE PSICOLÓGICO // ${sessionStorage.linkPercentage(session)}%"
        val planLabel = when {
            session.isDemo -> getString(R.string.demo_session_plan)
            session.isPremium -> "ESTABLE (PREMIUM)"
            else -> "ESTABLE (FREE)"
        }
        binding.statusText.text = "ESTADO // $planLabel"
        binding.legendText.text = buildString {
            append(getString(R.string.bitacora_leyenda_line))
            append("\n")
            append("ENLACE PSICOLÓGICO // ${sessionStorage.linkPercentage(session)}%")
        }

        binding.homeWidgetSwitch.setOnCheckedChangeListener(null)
        binding.homeWidgetSwitch.isChecked = sessionStorage.isHomeWidgetEnabled()
        binding.homeWidgetSwitch.setOnCheckedChangeListener { _, checked ->
            sessionStorage.setHomeWidgetEnabled(checked)
            Me2HomeWidgetProvider.refreshAll(this)
            Toast.makeText(
                this,
                if (checked) getString(R.string.home_widget_on) else getString(R.string.home_widget_off),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.editProfileButton.setOnClickListener {
            Toast.makeText(this, "EDICIÓN DE PERFIL RESERVADA", Toast.LENGTH_SHORT).show()
        }

        binding.signOutButton.setOnClickListener {
            val shouldBackup =
                currentSession.isPremium &&
                    !currentSession.authToken.isNullOrBlank() &&
                    backendClient.isConfigured() &&
                    backendClient.isOnline(this)

            if (shouldBackup) {
                performPremiumBackup { completeSignOut() }
            } else {
                completeSignOut()
            }
        }

        binding.premiumButton.setOnClickListener {
            showPremiumDialog()
        }
        binding.initiativeSettingsButton.setOnClickListener { showInitiativeSettings() }
    }

    private fun setupWidget() {
        updateWidgetScene(AvatarWidgetScene("CLIP LECTURA", "VENTANA AL MUNDO DEL AVATAR", "21°C"))

        binding.chipLectura.setOnClickListener {
            updateWidgetScene(AvatarWidgetScene("CLIP LECTURA", "CALMA Y FOCO", "21°C"))
            binding.avatarStateText.text = "STATE // THINKING"
        }
        binding.chipMusica.setOnClickListener {
            updateWidgetScene(AvatarWidgetScene("CLIP MÚSICA", "AUDIO Y PRESENCIA", "23°C"))
            binding.avatarStateText.text = "STATE // HAPPY"
        }
        binding.chipAtenta.setOnClickListener {
            updateWidgetScene(AvatarWidgetScene("CLIP ATENCIÓN", "ESCUCHA ACTIVA", "20°C"))
            binding.avatarStateText.text = "STATE // LISTENING"
        }

        binding.audioPrimaryButton.setOnClickListener {
            ensureNotificationPermission()
            notificationCoordinator.showMessageNotification(
                userId = currentSession.id,
                title = "ME2",
                message = "Canal de mensajes listo para prueba."
            )
            Toast.makeText(this, getString(R.string.message_notification_sent), Toast.LENGTH_SHORT).show()
        }

        binding.audioMoreButton.setOnClickListener {
            scheduleTestAlarm()
        }
        binding.audioMoreButton.setOnLongClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
            true
        }

        binding.audioMuteButton.setOnClickListener {
            cancelNextAlarm()
        }
    }

    private fun updateWidgetScene(scene: AvatarWidgetScene) {
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        binding.widgetSceneText.text = scene.title
        binding.widgetFooterText.text = "$clock // ${scene.temperature}"
        sessionStorage.saveLastTemperature(scene.temperature)
        if (sessionStorage.isHomeWidgetEnabled()) {
            Me2HomeWidgetProvider.refreshAll(this)
        }
    }

    private fun setupVideo() {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            binding.playerView.setKeepContentOnPlayerReset(true)
            binding.playerView.setShutterBackgroundColor(Color.TRANSPARENT)
            exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            exoPlayer.volume = 1f
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        handleAvatarPlaybackEnded()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("Me2Avatar", "Fallo reproduccion avatar: ${error.errorCodeName}")
                    fallbackToLoopNeutral(forceReload = true)
                }
            })
            fallbackToLoopNeutral(forceReload = true, resetStateLabel = true)
        }
    }

    private fun seedConversation() {
        if (fullConversation.isNotEmpty()) return
        val intro = localMemoryStore.load(currentSession.id).characterName
            ?.let { "Hola. Podés llamarme $it." }
            ?: "Hola. Estoy lista para acompañarte."
        fullConversation += ChatMessage(intro, true)
        localMemoryStore.appendAssistantMessage(currentSession.id, intro)
        visibleConversation += fullConversation
        renderConversation()
    }

    private fun hydrateConversation() {
        val memory = localMemoryStore.load(currentSession.id)
        if (memory.conversation.isEmpty()) {
            seedConversation()
            return
        }

        fullConversation.clear()
        visibleConversation.clear()
        memory.conversation.forEach { entry ->
            val text = if (entry.role == "user") {
                entry.text.uppercase(Locale.getDefault())
            } else {
                entry.text
            }
            val isMe2 = entry.role != "user"
            val message = ChatMessage(text, isMe2)
            fullConversation += message
            if (entry.timestamp > memory.hiddenConversationThrough) visibleConversation += message
        }
        if (avatarMode == AvatarMode.LOOP_NEUTRAL) {
            binding.avatarStateText.text = "STATE // ${memory.shortTermFocus.uppercase(Locale.getDefault())}"
        }
        renderConversation()
    }

    private fun sendMessage() {
        val content = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) return
        val initiative = initiativeStore.activeContext(currentSession.id)

        val visibleUserText = content.uppercase(Locale.getDefault())
        val userMessage = ChatMessage(visibleUserText, false)
        fullConversation += userMessage
        visibleConversation += userMessage
        localMemoryStore.appendUserMessage(currentSession.id, content)
        initiativeStore.observeInteraction(currentSession.id)
        initiativeScheduler.cancelPostSilenceEval()
        initiative?.let { initiativeStore.responded(currentSession.id, it.getString("id"), responseText = content) }
        binding.messageInput.text?.clear()
        binding.avatarStateText.text = "STATE // SYNCING"
        fallbackToLoopNeutral(forceReload = !galleryContainsCurrentClip(loopNeutralGallery))
        renderConversation()
        dispatchChat(content, initiative)
    }

    private fun renderConversation() {
        chatAdapter.submitList(visibleConversation.toList())
        binding.chatRecyclerView.post {
            if (chatAdapter.itemCount > 0) {
                binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    private fun showPremiumDialog() {
        val premiumCopy = if (currentSession.isPremium) {
            getString(R.string.adult_mode_active_copy)
        } else {
            getString(R.string.adult_mode_premium_copy)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("PREMIUM ME2")
            .setMessage(premiumCopy)

        if (currentSession.isPremium && !currentSession.authToken.isNullOrBlank()) {
            builder
                .setPositiveButton(getString(R.string.premium_backup)) { _, _ ->
                    performPremiumBackup()
                }
                .setNeutralButton(getString(R.string.premium_tools)) { _, _ ->
                    showPremiumToolsDialog()
                }
                .setNegativeButton(getString(R.string.premium_restore)) { _, _ ->
                    restorePremiumBackup()
                }
        } else {
            builder
                .setPositiveButton(getString(R.string.premium_checkout)) { _, _ ->
                    openMercadoPagoCheckout()
                }
                .setNegativeButton("CERRAR", null)
        }

        builder.show()
    }

    private fun showPremiumToolsDialog() {
        if (!currentSession.isPremium) {
            Toast.makeText(this, getString(R.string.premium_required), Toast.LENGTH_SHORT).show()
            return
        }
        val memory = localMemoryStore.load(currentSession.id)
        val summary = buildString {
            append("CÓDIGO: ${memory.codeMemories.size} recuerdos\n")
            append("FISCAL: ${memory.fiscalMemories.size} recuerdos\n")
            append("TODO: envío de mail al contador / captura de imágenes fiscales pendiente en backend.")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.premium_tools))
            .setMessage(summary)
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private fun openMercadoPagoCheckout() {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured()) {
            Toast.makeText(this, getString(R.string.premium_checkout_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        if (!backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.premium_checkout_offline), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.premium_checkout_starting), Toast.LENGTH_SHORT).show()
        thread {
            runCatching {
                backendClient.createMercadoPagoCheckout(currentSession)
            }.onSuccess { checkout ->
                runOnUiThread {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checkout.initPoint)))
                    // After returning, refresh premium status (verify endpoint may be unconfigured).
                    syncPremiumState()
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.premium_checkout_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun maybeAskSilenceCheckIn() {
        if (!::currentSession.isInitialized) return
        if (!initiativeStore.isEnabled(currentSession.id)) return
        if (!initiativeStore.shouldAskCheckIn(currentSession.id)) return
        initiativeStore.markCheckInAsked(currentSession.id)
        val prompt = getString(R.string.silence_check_in)
        localMemoryStore.appendAssistantMessage(currentSession.id, prompt)
        hydrateConversation()
        // After check-in without response, arm 1h countdown toward server eval.
        initiativeStore.markPostSilenceEvalArmed(currentSession.id)
        initiativeScheduler.schedulePostSilenceEval()
    }

    private fun dispatchChat(content: String, initiative: JSONObject? = null) {
        // Demo / no-token path: keep UI alive without hitting backend.
        if (currentSession.isDemo || currentSession.authToken.isNullOrBlank()) {
            val notice = if (currentSession.isDemo) {
                getString(R.string.demo_chat_notice)
            } else {
                getString(R.string.offline_memory_notice)
            }
            appendAssistantReply(
                notice,
                if (currentSession.isDemo) "DEMO" else "OFFLINE",
                "LOCAL",
                typewriter = currentSession.isDemo
            )
            return
        }
        if (!backendClient.isConfigured() || !backendClient.isOnline(this)) {
            appendAssistantReply(getString(R.string.offline_memory_notice), "OFFLINE", "LOCAL")
            return
        }

        val memorySnapshot = localMemoryStore.load(currentSession.id)
        binding.sendButton.isEnabled = false
        thread {
            runCatching {
                backendClient.sendChat(currentSession, memorySnapshot, content, initiative)
            }.onSuccess { result ->
                runOnUiThread {
                    binding.sendButton.isEnabled = true
                    applyAdultModeFromChat(result)
                    val intensity = result.adultMode?.intensity
                    val state = when {
                        !intensity.isNullOrBlank() && intensity != "none" && result.adultMode?.unlocked == true ->
                            "ADULT_${intensity.uppercase(Locale.getDefault())}"
                        else -> result.tone?.uppercase(Locale.getDefault()) ?: "ONLINE"
                    }
                    val detail = result.videoEtiqueta?.uppercase(Locale.getDefault())
                        ?: result.microExpression?.uppercase(Locale.getDefault())
                        ?: "SYNC"
                    appendAssistantReply(result.reply, state, detail, typewriter = true)
                    startedFromEmptyLocalMemory = false
                    result.premiumUntilMillis?.let { premiumUntil ->
                        updateCurrentSession(currentSession.copy(premiumUntilMillis = premiumUntil))
                    }
                    result.checkoutInitPoint?.takeIf { it.isNotBlank() }?.let { initPoint ->
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(initPoint)))
                        }
                    }
                    // Persist keyword if ME2 just assigned one in the reply.
                    extractAdultKeyword(result.reply)?.let { keyword ->
                        sessionStorage.saveAdultKeyword(keyword)
                    }
                }
            }.onFailure {
                runOnUiThread {
                    binding.sendButton.isEnabled = true
                    appendAssistantReply(getString(R.string.offline_memory_notice), "OFFLINE", "LOCAL")
                }
            }
        }
    }

    private fun appendAssistantReply(
        reply: String,
        state: String,
        detail: String,
        typewriter: Boolean = false
    ) {
        // Clear previous typewriter flags so only the newest ME2 bubble animates.
        for (i in visibleConversation.indices) {
            val msg = visibleConversation[i]
            if (msg.fromMe2 && msg.animateTypewriter) {
                visibleConversation[i] = msg.copy(animateTypewriter = false)
            }
        }
        val me2Reply = ChatMessage(reply, true, animateTypewriter = typewriter)
        fullConversation += me2Reply
        visibleConversation += me2Reply
        localMemoryStore.appendAssistantMessage(currentSession.id, reply)
        binding.avatarStateText.text = "STATE // $state"
        applyAssistantAvatarState(state, detail)
        renderConversation()
    }

    private fun applyAdultModeFromChat(result: com.me2.android.net.BackendChatResult) {
        val adult = result.adultMode ?: return
        sessionStorage.saveAdultUnlocked(adult.unlocked)
        adult.intensity?.let { sessionStorage.saveAdultIntensity(it) }
    }

    private fun extractAdultKeyword(reply: String): String? {
        val patterns = listOf(
            Regex("""palabra clave para modo adulto será:\s*([A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+)""", RegexOption.IGNORE_CASE),
            Regex("""palabra clave para modo adulto sera:\s*([A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+)""", RegexOption.IGNORE_CASE)
        )
        for (re in patterns) {
            val m = re.find(reply) ?: continue
            return m.groupValues.getOrNull(1)?.lowercase(Locale.getDefault())
        }
        return null
    }

    private fun syncPremiumState() {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            return
        }

        thread {
            runCatching {
                backendClient.fetchPremiumStatus(currentSession)
            }.onSuccess { premium ->
                val updatedSession = currentSession.copy(
                    premiumUntilMillis = if (premium.active) premium.premiumUntilMillis else 0L
                )
                runOnUiThread {
                    updateCurrentSession(updatedSession)
                    backupMaterial = premium.backupMaterial
                    premium.adultMode?.let { adult ->
                        sessionStorage.saveAdultUnlocked(adult.unlocked)
                        adult.intensity?.let { sessionStorage.saveAdultIntensity(it) }
                    }
                    if (updatedSession.isPremium && startedFromEmptyLocalMemory) {
                        restorePremiumBackup(silent = true)
                    }
                }
            }
        }
    }

    private fun syncBackendAlarms() {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            return
        }
        thread {
            runCatching {
                backendClient.listAlarms(currentSession)
            }.onSuccess { alarms ->
                alarms.forEach { alarmScheduler.schedule(it) }
            }
        }
    }

    private fun scheduleTestAlarm() {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.alarm_requires_backend), Toast.LENGTH_SHORT).show()
            return
        }
        ensureNotificationPermission()
        if (!alarmScheduler.canScheduleExactAlarms()) {
            alarmScheduler.exactAlarmPermissionIntent()?.let(::startActivity)
            Toast.makeText(this, getString(R.string.exact_alarm_permission_needed), Toast.LENGTH_SHORT).show()
            return
        }

        val nextMinute = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val hour = SimpleDateFormat("HH:mm", Locale.getDefault()).format(nextMinute.time)

        thread {
            runCatching {
                val alarm = backendClient.createAlarm(
                    currentSession,
                    hour = hour,
                    title = getString(R.string.alarm_protocol_title),
                    message = getString(R.string.alarm_protocol_message)
                )
                alarmScheduler.schedule(alarm)
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, "${getString(R.string.alarm_test_scheduled)} $hour", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: getString(R.string.alarm_requires_backend), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cancelNextAlarm() {
        val nextAlarm = alarmScheduler.peekNextAlarm(currentSession.id)
        if (nextAlarm == null) {
            Toast.makeText(this, getString(R.string.alarm_none_active), Toast.LENGTH_SHORT).show()
            return
        }

        alarmScheduler.cancel(nextAlarm.id)
        notificationCoordinator.cancelAlarmNotifications(nextAlarm.id)

        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.alarm_cancelled), Toast.LENGTH_SHORT).show()
            return
        }

        thread {
            runCatching {
                backendClient.cancelAlarm(currentSession, nextAlarm.id)
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.alarm_cancelled), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        restoreAvatarPresence()
        val eventType = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_EVENT_TYPE) ?: return
        val title = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_MESSAGE).orEmpty()
        val alarmId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_ALARM_ID)
        val stage = intent.getIntExtra(Me2NotificationCoordinator.EXTRA_STAGE, 0)

        when (eventType) {
            "initiative" -> openInitiative(intent)

            "message" -> {
                appendAssistantReply(
                    message.ifBlank { getString(R.string.notification_message_opened) },
                    "NOTICE",
                    title.ifBlank { "MESSAGE" }
                )
            }

            "alarm" -> {
                appendAssistantReply(
                    message.ifBlank { getString(R.string.notification_alarm_opened) },
                    "AWAKE",
                    "STAGE_$stage"
                )
                if (!alarmId.isNullOrBlank()) {
                    alarmScheduler.cancel(alarmId)
                    notificationCoordinator.cancelAlarmNotifications(alarmId)
                    resolveAlarmEvent(alarmId, stage)
                }
            }
        }

        intent.removeExtra(Me2NotificationCoordinator.EXTRA_EVENT_TYPE)
    }

    private fun restoreAvatarPresence(forceReload: Boolean = false) {
        if (player == null) {
            setupVideo()
            return
        }
        fallbackToLoopNeutral(
            forceReload = forceReload || !galleryContainsCurrentClip(loopNeutralGallery),
            resetStateLabel = currentAvatarClipResId == null
        )
    }

    private fun maybePlayPresentation() {
        if (!startedFromEmptyLocalMemory || hasPlayedPresentation) return
        val exoPlayer = player ?: return
        val clip = resolveNextClip(presentationGallery, currentAvatarClipResId) ?: return
        hasPlayedPresentation = true
        avatarMode = AvatarMode.PRESENTATION
        currentAvatarGallery = presentationGallery
        binding.videoCaption.text = "AVATAR // LOCAL CLIP // PRESENTACION"
        playAvatarClip(exoPlayer, clip)
    }

    private fun applyAssistantAvatarState(state: String, detail: String) {
        val selection = resolveAvatarSelection(state, detail)
        if (selection == null) {
            fallbackToLoopNeutral(forceReload = !galleryContainsCurrentClip(loopNeutralGallery))
            return
        }
        val exoPlayer = player ?: return
        val clip = resolveNextClip(selection.gallery, currentAvatarClipResId)
        if (clip == null) {
            fallbackToLoopNeutral(forceReload = !galleryContainsCurrentClip(loopNeutralGallery))
            return
        }
        avatarMode = AvatarMode.CONTEXTUAL
        currentAvatarGallery = selection.gallery
        binding.videoCaption.text = "AVATAR // LOCAL CLIP // ${selection.label}"
        playAvatarClip(exoPlayer, clip)
    }

    private fun resolveAvatarSelection(state: String, detail: String): AvatarSelection? {
        val normalizedState = state.uppercase(Locale.getDefault())
        val normalizedDetail = detail.uppercase(Locale.getDefault())
        val tokens = "$normalizedState $normalizedDetail"
        // Adult intensity → teasers locales existentes (sin biblioteca adulta Blender).
        when {
            tokens.contains("ADULT_EXPLICIT") -> return AvatarSelection("TEASER_EXPLICIT", loopNeutralGallery)
            tokens.contains("ADULT_INTIMATE") -> return AvatarSelection("TEASER_INTIMATE", calidaGallery)
            tokens.contains("ADULT_SUGGESTIVE") -> return AvatarSelection("TEASER_SUGGESTIVE", alegreGallery)
            tokens.contains("ADULT_SOFT_FLIRT") || tokens.contains("ADULT_SOFT") ->
                return AvatarSelection("TEASER_SOFT", calidaGallery)
        }
        return when {
            normalizedState == "OFFLINE" || normalizedState == "NOTICE" -> null
            normalizedDetail == "LOCAL" || normalizedDetail == "SYNC" || normalizedDetail == "MESSAGE" || normalizedDetail.startsWith("STAGE_") -> null
            tokens.contains("AGRADEC") -> AvatarSelection("AGRADECIDA", agradecidaGallery)
            tokens.contains("ALEGRE") || tokens.contains("HAPPY") || tokens.contains("FELIZ") || tokens.contains("SONRISA") -> AvatarSelection("ALEGRE", alegreGallery)
            tokens.contains("ATENTA") || tokens.contains("LISTENING") || tokens.contains("MIRADA_ATENTA") || tokens.contains("THINK") -> AvatarSelection("ATENTA", atentaGallery)
            tokens.contains("ALIVIADA") || tokens.contains("CALMA") || tokens.contains("TRISTE") -> AvatarSelection("ALIVIADA", aliviadaGallery)
            normalizedState.isNotBlank() || normalizedDetail.isNotBlank() -> AvatarSelection("CALIDA", calidaGallery)
            else -> null
        }
    }

    private fun handleAvatarPlaybackEnded() {
        when (avatarMode) {
            AvatarMode.PRESENTATION, AvatarMode.CONTEXTUAL -> fallbackToLoopNeutral(forceReload = true)
            AvatarMode.LOOP_NEUTRAL -> {
                val exoPlayer = player ?: return
                playAvatarClip(exoPlayer, pickNextClip(currentAvatarGallery, currentAvatarClipResId))
            }
        }
    }

    private fun fallbackToLoopNeutral(forceReload: Boolean = false, resetStateLabel: Boolean = false) {
        avatarMode = AvatarMode.LOOP_NEUTRAL
        currentAvatarGallery = loopNeutralGallery
        if (resetStateLabel) {
            binding.avatarStateText.text = "STATE // NEUTRAL"
        }
        binding.videoCaption.text = "AVATAR // LOCAL CLIP // LOOP_NEUTRAL"
        ensureAvatarPlayback(forceReload = forceReload)
    }

    private fun ensureAvatarPlayback(forceReload: Boolean = false) {
        val exoPlayer = player ?: return
        val gallery = currentAvatarGallery
        val desiredClip = when {
            forceReload -> pickNextClip(gallery, currentAvatarClipResId)
            currentAvatarClipResId == null -> pickNextClip(gallery, lastAvatarClipResId)
            !galleryContainsCurrentClip(gallery) -> pickNextClip(gallery, currentAvatarClipResId)
            else -> currentAvatarClipResId ?: loopNeutralGallery.first()
        }
        if (!forceReload && desiredClip == currentAvatarClipResId) {
            exoPlayer.playWhenReady = true
            return
        }
        playAvatarClip(exoPlayer, desiredClip)
    }

    private fun playAvatarClip(exoPlayer: ExoPlayer, clipResId: Int) {
        if (!isClipAvailable(clipResId)) {
            if (clipResId != loopNeutralGallery.first()) {
                fallbackToLoopNeutral(forceReload = true)
            }
            return
        }
        lastAvatarClipResId = currentAvatarClipResId
        currentAvatarClipResId = clipResId
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://$packageName/$clipResId")))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun galleryContainsCurrentClip(gallery: IntArray): Boolean =
        currentAvatarClipResId?.let { clipResId -> gallery.contains(clipResId) && isClipAvailable(clipResId) } == true

    private fun resolveNextClip(gallery: IntArray, previousClipResId: Int?): Int? {
        val available = gallery.filter(::isClipAvailable)
        if (available.isEmpty()) return null
        if (available.size == 1 || previousClipResId == null) return available.first()
        val previousIndex = available.indexOf(previousClipResId).takeIf { it >= 0 } ?: return available.first()
        return available[(previousIndex + 1) % available.size]
    }

    private fun pickNextClip(gallery: IntArray, previousClipResId: Int?): Int =
        resolveNextClip(gallery, previousClipResId) ?: loopNeutralGallery.first()

    private fun isClipAvailable(clipResId: Int): Boolean =
        runCatching {
            resources.openRawResourceFd(clipResId)?.close()
            true
        }.getOrDefault(false)

    private fun openInitiative(intent: Intent) {
        val userId = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_USER_ID)
        val id = intent.getStringExtra(Me2NotificationCoordinator.EXTRA_INITIATIVE_ID)
        if (userId != currentSession.id || id.isNullOrBlank()) {
            Log.w("Me2Initiative", "Aviso de otra sesion o sin identificador")
            Toast.makeText(this, R.string.initiative_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val initiative = initiativeStore.find(userId, id)
            if (initiative == null || initiative.optString("estado") == "CANCELADA") {
                Toast.makeText(this, R.string.initiative_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            if (initiative.isNull("abierta")) {
                localMemoryStore.appendInitiativeMessage(userId, id, initiative.getString("mensaje"))
            }
            initiativeStore.opened(userId, id)
            notificationCoordinator.cancelInitiative(userId, id)
            hydrateConversation()
        } catch (error: Exception) {
            Log.e("Me2Initiative", "No se pudo abrir el contexto: ${error.javaClass.simpleName}")
            Toast.makeText(this, R.string.initiative_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showInitiativeSettings() {
        val enabled = initiativeStore.isEnabled(currentSession.id)
        val choices = arrayOf(
            getString(if (enabled) R.string.initiative_disable else R.string.initiative_enable),
            getString(R.string.initiative_sleep_schedule),
            getString(R.string.initiative_learn_schedule)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.initiative_settings)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> {
                        initiativeStore.setEnabled(currentSession.id, !enabled)
                        if (enabled) {
                            initiativeScheduler.cancel()
                            initiativeStore.clearActiveContext(currentSession.id)
                            initiativeStore.records(currentSession.id).forEach {
                                val id = it.getString("id")
                                notificationCoordinator.cancelInitiative(currentSession.id, id)
                                initiativeStore.cancelled(currentSession.id, id)
                            }
                        } else {
                            ensureNotificationPermission()
                            initiativeScheduler.ensureScheduled()
                        }
                    }
                    1 -> configureInitiativeSleep()
                    2 -> initiativeStore.configureSleep(currentSession.id, null, null)
                }
            }
            .setNegativeButton(R.string.initiative_close, null)
            .show()
    }

    private fun configureInitiativeSleep() {
        val configured = initiativeStore.snapshot(currentSession.id)
            .optJSONObject("perfilRitmo")?.optJSONObject("configurado")
        val sleep = configured?.optString("dormir")?.split(":")
        val wake = configured?.optString("despertar")?.split(":")
        val clock = Calendar.getInstance()
        val picker = TimePickerDialog(this, { _, sleepHour, sleepMinute ->
            val wakePicker = TimePickerDialog(this, { _, wakeHour, wakeMinute ->
                val sleepTime = String.format(Locale.US, "%02d:%02d", sleepHour, sleepMinute)
                val wakeTime = String.format(Locale.US, "%02d:%02d", wakeHour, wakeMinute)
                if (sleepTime == wakeTime) {
                    Toast.makeText(this, R.string.initiative_invalid_sleep, Toast.LENGTH_SHORT).show()
                } else {
                    initiativeStore.configureSleep(currentSession.id, sleepTime, wakeTime)
                }
            }, wake?.getOrNull(0)?.toIntOrNull() ?: clock.get(Calendar.HOUR_OF_DAY),
                wake?.getOrNull(1)?.toIntOrNull() ?: clock.get(Calendar.MINUTE), true)
            wakePicker.setTitle(R.string.initiative_wake_time)
            wakePicker.show()
        }, sleep?.getOrNull(0)?.toIntOrNull() ?: clock.get(Calendar.HOUR_OF_DAY),
            sleep?.getOrNull(1)?.toIntOrNull() ?: clock.get(Calendar.MINUTE), true)
        picker.setTitle(R.string.initiative_sleep_time)
        picker.show()
    }

    private fun resolveAlarmEvent(alarmId: String, stage: Int) {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            return
        }
        thread {
            runCatching {
                backendClient.reportAlarmEvent(currentSession, alarmId, stage, "respondio")
            }.onSuccess { result ->
                result.message?.let { reply ->
                    runOnUiThread {
                        appendAssistantReply(reply, "AWAKE", "CLIMA")
                    }
                }
            }
        }
    }

    private fun updateCurrentSession(session: UserSession) {
        currentSession = session
        sessionStorage.saveUser(session)
        setupBitacora(session)
    }

    private fun completeSignOut() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, options)
            .signOut()
            .addOnCompleteListener {
                initiativeScheduler.cancel()
                initiativeStore.clearActiveContext(currentSession.id)
                initiativeStore.records(currentSession.id).forEach {
                    notificationCoordinator.cancelInitiative(currentSession.id, it.getString("id"))
                    initiativeStore.cancelled(currentSession.id, it.getString("id"))
                }
                sessionStorage.clear()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
    }

    private fun performPremiumBackup(onComplete: (() -> Unit)? = null) {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            Toast.makeText(this, getString(R.string.premium_backup_failed), Toast.LENGTH_SHORT).show()
            onComplete?.invoke()
            return
        }

        thread {
            runCatching {
                val memory = localMemoryStore.load(currentSession.id)
                val material = backupMaterial ?: backendClient.fetchBackupMaterial(currentSession)
                backupMaterial = material
                val encrypted = premiumBackupCrypto.encrypt(currentSession, memory, material)
                backendClient.uploadEncryptedBackup(currentSession, encrypted)
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.premium_backup_done), Toast.LENGTH_SHORT).show()
                    onComplete?.invoke()
                }
            }.onFailure {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.premium_backup_failed), Toast.LENGTH_SHORT).show()
                    onComplete?.invoke()
                }
            }
        }
    }

    private fun restorePremiumBackup(silent: Boolean = false) {
        if (currentSession.authToken.isNullOrBlank() || !backendClient.isConfigured() || !backendClient.isOnline(this)) {
            if (!silent) {
                Toast.makeText(this, getString(R.string.premium_restore_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }

        thread {
            runCatching {
                val payload = backendClient.downloadEncryptedBackup(currentSession)
                val material = backupMaterial ?: backendClient.fetchBackupMaterial(currentSession)
                backupMaterial = material
                payload?.let { premiumBackupCrypto.decrypt(currentSession, it, material) }
            }.onSuccess { memory ->
                runOnUiThread {
                    when {
                        memory == null && !silent -> {
                            Toast.makeText(this, getString(R.string.premium_restore_empty), Toast.LENGTH_SHORT).show()
                        }

                        memory != null -> {
                            localMemoryStore.replace(memory.copy(userId = currentSession.id))
                            startedFromEmptyLocalMemory = false
                            hydrateConversation()
                            if (!silent) {
                                Toast.makeText(this, getString(R.string.premium_restore_done), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }.onFailure {
                if (!silent) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.premium_restore_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
