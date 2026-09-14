package com.joi.android

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.joi.android.data.AvatarWidgetScene
import com.joi.android.data.ChatMessage
import com.joi.android.data.LocalMemoryStore
import com.joi.android.data.PremiumBackupCrypto
import com.joi.android.data.SessionStorage
import com.joi.android.data.UserSession
import com.joi.android.databinding.ActivityMainBinding
import com.joi.android.net.JoiBackendClient
import com.joi.android.notifications.JoiAlarmScheduler
import com.joi.android.notifications.JoiNotificationChannels
import com.joi.android.notifications.JoiNotificationCoordinator
import com.joi.android.notifications.JoiInitiativeScheduler
import com.joi.android.notifications.JoiInitiativeStore
import com.joi.android.ui.ChatAdapter
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
    private lateinit var alarmScheduler: JoiAlarmScheduler
    private lateinit var notificationCoordinator: JoiNotificationCoordinator
    private lateinit var initiativeStore: JoiInitiativeStore
    private lateinit var initiativeScheduler: JoiInitiativeScheduler
    private lateinit var chatAdapter: ChatAdapter

    private val fullConversation = mutableListOf<ChatMessage>()
    private val visibleConversation = mutableListOf<ChatMessage>()
    private val backendClient = JoiBackendClient()
    private val premiumBackupCrypto = PremiumBackupCrypto()

    private var player: ExoPlayer? = null
    private var currentAvatarClipResId: Int? = null
    private var lastAvatarClipResId: Int? = null
    private var avatarMode: AvatarMode = AvatarMode.LOOP_NEUTRAL
    private var currentAvatarGallery: IntArray = intArrayOf(R.raw.joi_texting)
    private var hasPlayedPresentation = false
    private var sessionStartedAt: Long = 0L
    private lateinit var currentSession: UserSession
    private var startedFromEmptyLocalMemory: Boolean = false
    private var backupMaterial: String? = null

    private val loopNeutralGallery = intArrayOf(R.raw.joi_texting)
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
        alarmScheduler = JoiAlarmScheduler(this)
        notificationCoordinator = JoiNotificationCoordinator(this)
        initiativeStore = JoiInitiativeStore(this)
        initiativeScheduler = JoiInitiativeScheduler(this)

        JoiNotificationChannels.ensure(this)
        ensureNotificationPermission()

        setupToolbar()
        setupChat()
        setupBitacora(currentSession)
        setupWidget()
        setupVideo()

        startedFromEmptyLocalMemory = localMemoryStore.isEffectivelyEmpty(currentSession.id)
        val launchedWithEvent = intent?.getStringExtra(JoiNotificationCoordinator.EXTRA_EVENT_TYPE) != null
        hydrateConversation()
        if (!launchedWithEvent) maybePlayPresentation()
        localMemoryStore.observe(currentSession.id).observe(this) { record ->
            if (record != null) hydrateConversation()
        }
        syncPremiumState()
        handleIncomingIntent(intent)
        syncBackendAlarms()
        if (initiativeStore.isEnabled(currentSession.id)) initiativeScheduler.ensureScheduled()
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
        if (::currentSession.isInitialized) initiativeStore.observeInteraction(currentSession.id)
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
        ) initiativeScheduler.afterInteraction()
    }

    override fun onDestroy() {
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun setupToolbar() {
        binding.drawerLayout.setScrimColor(ContextCompat.getColor(this, R.color.joi_drawer_scrim))
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
        binding.userNameText.text = "USERNAME // ${session.displayName.uppercase(Locale.getDefault())}"
        binding.userIdText.text = "ID // ${session.id.uppercase(Locale.getDefault())}"
        binding.linkText.text = "ENLACE PSICOLÓGICO // ${sessionStorage.linkPercentage(session)}%"
        val planLabel = if (session.isPremium) "ESTABLE (PREMIUM)" else "ESTABLE (FREE)"
        binding.statusText.text = "ESTADO // $planLabel"
        binding.legendText.text = buildString {
            append(if (backendClient.isConfigured()) "[ME2] ${getString(R.string.bitacora_sync_wait)}\n" else "[ME2] MODO LOCAL PRIMARIO\n")
            append("[MEMORIA] MEMORIA LOCAL MULTICAPA ACTIVA\n")
            append("[USUARIO] SESIÓN VINCULADA A ${session.email.uppercase(Locale.getDefault())}\n")
            append("[SISTEMA] VIDEO LOCAL, CHAT, NOTIFICACIONES Y BITÁCORA DISPONIBLES")
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
    }

    private fun setupVideo() {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            binding.playerView.setKeepContentOnPlayerReset(true)
            binding.playerView.setShutterBackgroundColor(Color.TRANSPARENT)
            exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            exoPlayer.volume = 0f
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        handleAvatarPlaybackEnded()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("JoiAvatar", "Fallo reproduccion avatar: ${error.errorCodeName}")
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
            val isJoi = entry.role != "user"
            val message = ChatMessage(text, isJoi)
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
        val builder = AlertDialog.Builder(this)
            .setTitle("PREMIUM ME2")
            .setMessage("MODO PREMIUM HABILITA M/A, MEMORIA EXTENDIDA, GESTIÓN DE ARCHIVOS Y RESPALDO DE MEMORIA COMPLETA DURANTE 30 DÍAS.")

        if (currentSession.isPremium && !currentSession.authToken.isNullOrBlank()) {
            builder
                .setPositiveButton(getString(R.string.premium_backup)) { _, _ ->
                    performPremiumBackup()
                }
                .setNeutralButton(getString(R.string.premium_restore)) { _, _ ->
                    restorePremiumBackup()
                }
                .setNegativeButton("CERRAR", null)
        } else {
            builder
                .setPositiveButton("ABRIR MERCADO PAGO") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.MERCADO_PAGO_URL)))
                }
                .setNegativeButton("CERRAR", null)
        }

        builder.show()
    }

    private fun dispatchChat(content: String, initiative: JSONObject? = null) {
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
                    val state = result.tone?.uppercase(Locale.getDefault()) ?: "ONLINE"
                    val detail = result.microExpression?.uppercase(Locale.getDefault()) ?: "SYNC"
                    appendAssistantReply(result.reply, state, detail)
                    startedFromEmptyLocalMemory = false
                    result.premiumUntilMillis?.let { premiumUntil ->
                        updateCurrentSession(currentSession.copy(premiumUntilMillis = premiumUntil))
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

    private fun appendAssistantReply(reply: String, state: String, detail: String) {
        val joiReply = ChatMessage(reply, true)
        fullConversation += joiReply
        visibleConversation += joiReply
        localMemoryStore.appendAssistantMessage(currentSession.id, reply)
        binding.avatarStateText.text = "STATE // $state"
        applyAssistantAvatarState(state, detail)
        renderConversation()
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
        val eventType = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_EVENT_TYPE) ?: return
        val title = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_MESSAGE).orEmpty()
        val alarmId = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_ALARM_ID)
        val stage = intent.getIntExtra(JoiNotificationCoordinator.EXTRA_STAGE, 0)

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

        intent.removeExtra(JoiNotificationCoordinator.EXTRA_EVENT_TYPE)
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
        val userId = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_USER_ID)
        val id = intent.getStringExtra(JoiNotificationCoordinator.EXTRA_INITIATIVE_ID)
        if (userId != currentSession.id || id.isNullOrBlank()) {
            Log.w("JoiInitiative", "Aviso de otra sesion o sin identificador")
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
            Log.e("JoiInitiative", "No se pudo abrir el contexto: ${error.javaClass.simpleName}")
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
