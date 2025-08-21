package com.clearwaycargo.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clearwaycargo.ai.RealtimeClient
import com.clearwaycargo.ui.voice.VoiceState
import com.clearwaycargo.util.AudioController
import com.clearwaycargo.util.VoiceUiCues
import com.clearwaycargo.util.VoiceUiCuesFactory
import com.clearwaycargo.util.PermitVoiceMatcher
import com.permitnav.R
import com.permitnav.data.models.Permit
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.repository.PermitRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that manages voice session lifecycle
 * Owns RealtimeClient and AudioController, provides bound interface for UI
 */
class VoiceSessionService : Service() {
    
    companion object {
        private const val TAG = "VoiceSessionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_chat_channel"
        private const val BARGE_IN_THRESHOLD = 0.3f // Mic level threshold for barge-in
        private const val BARGE_IN_DURATION_MS = 500L // How long above threshold before barge-in
    }
    
    // Binder for UI communication
    inner class VoiceSessionBinder : Binder() {
        fun getService(): VoiceSessionService = this@VoiceSessionService
    }
    
    private val binder = VoiceSessionBinder()
    
    // Core components
    private var realtimeClient: RealtimeClient? = null
    private var audioController: AudioController? = null
    private var voiceUiCues: VoiceUiCues? = null
    private var permitVoiceMatcher: PermitVoiceMatcher? = null
    private var permitRepository: PermitRepository? = null
    
    // Service state
    private var serviceScope: CoroutineScope? = null
    private var currentPermit: Permit? = null
    private var isSessionActive = false
    private var pendingGreeting: String? = null
    
    // State flows for UI observation
    private val _voiceState = MutableStateFlow(VoiceState.LISTENING)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()
    
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()
    
    private val _micLevel = MutableStateFlow(0.0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()
    
    private val _assistantLevel = MutableStateFlow(0.0f)
    val assistantLevel: StateFlow<Float> = _assistantLevel.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _currentPermitContext = MutableStateFlow<Permit?>(null)
    val currentPermitContext: StateFlow<Permit?> = _currentPermitContext.asStateFlow()
    
    // Barge-in detection
    private var bargeInJob: Job? = null
    private var lastBargeInTime = 0L
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 VoiceSessionService created")
        
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        
        // CRITICAL: Create notification channel and start foreground IMMEDIATELY
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        initializeComponents()
    }
    
    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "🔗 Service bound")
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "▶️ Service start command")
        
        // Foreground notification already started in onCreate(), just ensure it's active
        if (!isSessionActive) {
            startForeground(NOTIFICATION_ID, createNotification("Voice chat ready"))
        }
        
        return START_NOT_STICKY // Don't restart if killed
    }
    
    override fun onDestroy() {
        Log.d(TAG, "🛑 VoiceSessionService destroyed")
        
        stopSession()
        cleanupComponents()
        
        serviceScope?.cancel()
        serviceScope = null
        
        super.onDestroy()
    }
    
    /**
     * Start voice session with optional permit context and greeting
     */
    fun startSession(initialGreeting: Boolean = true, permitContext: Permit? = null) {
        if (isSessionActive) {
            Log.w(TAG, "⚠️ Session already active")
            return
        }
        
        Log.d(TAG, "🎤 Starting voice session")
        
        serviceScope?.launch {
            try {
                currentPermit = permitContext
                isSessionActive = true
                
                // Set initial state to connecting
                _voiceState.value = VoiceState.CONNECTING
                
                // Initialize audio and realtime components
                initializeForSession()
                
                // Start audio capture and monitoring
                startAudioCapture()
                
                // Start realtime connection
                startRealtimeConnection()
                
                // Greeting temporarily disabled due to OpenAI API complexity
                // Will implement after voice chat is stable
                if (initialGreeting) {
                    Log.d(TAG, "🎤 Greeting requested but temporarily disabled")
                }
                
                Log.d(TAG, "✅ Voice session started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start session", e)
                _error.value = "Failed to start voice session: ${e.message}"
                stopSession()
            }
        }
    }
    
    /**
     * Stop the current voice session
     */
    fun stopSession() {
        if (!isSessionActive) return
        
        Log.d(TAG, "⏹️ Stopping voice session")
        
        isSessionActive = false
        currentPermit = null
        pendingGreeting = null
        
        // Stop components
        stopAudioCapture()
        realtimeClient?.stopSession()
        voiceUiCues?.stopThinkingLoop()
        
        // Reset state
        _voiceState.value = VoiceState.LISTENING
        _transcript.value = ""
        _micLevel.value = 0.0f
        _assistantLevel.value = 0.0f
        _isConnected.value = false
        _error.value = null
        
        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Log.d(TAG, "✅ Voice session stopped")
    }
    
    /**
     * Mute/unmute microphone
     */
    fun mute(muted: Boolean) {
        audioController?.setMuted(muted)
        Log.d(TAG, "🔇 Microphone ${if (muted) "muted" else "unmuted"}")
    }
    
    /**
     * Initialize components for voice session
     */
    private fun initializeForSession() {
        Log.d(TAG, "🔧 Initializing components for session")
        
        // Check permissions before initializing audio components
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw Exception("RECORD_AUDIO permission not granted")
        }
        
        // Audio controller
        audioController?.let { controller ->
            if (!controller.initialize()) {
                throw Exception("Failed to initialize audio controller")
            }
        }
        
        // Voice UI cues
        voiceUiCues?.let { cues ->
            if (!cues.initialize()) {
                throw Exception("Failed to initialize voice UI cues")
            }
        }
        
        // Realtime client
        realtimeClient?.initialize()
    }
    
    /**
     * Start audio capture and monitoring
     */
    private fun startAudioCapture() {
        Log.d(TAG, "🎤 Starting audio capture")
        
        audioController?.let { controller ->
            // Start monitoring audio levels
            serviceScope?.launch {
                controller.micAmplitude.collect { level ->
                    _micLevel.value = level
                    
                    // Check for barge-in during speaking state
                    if (_voiceState.value == VoiceState.SPEAKING) {
                        checkBargeIn(level)
                    }
                }
            }
            
            // Start audio capture
            controller.startCapture { audioData ->
                // In production, send audioData to realtimeClient
                // For now, just log that we're capturing
                if (_voiceState.value == VoiceState.LISTENING) {
                    // Simulate voice activity detection
                    if (_micLevel.value > 0.1f) {
                        handleUserSpeaking()
                    }
                }
            }
        }
    }
    
    /**
     * Stop audio capture
     */
    private fun stopAudioCapture() {
        audioController?.stopCapture()
        bargeInJob?.cancel()
        bargeInJob = null
    }
    
    /**
     * Start realtime connection to OpenAI
     */
    private fun startRealtimeConnection() {
        Log.d(TAG, "🌐 Starting realtime connection")
        
        realtimeClient?.startSession()
    }
    
    /**
     * Check for barge-in (user interrupting AI)
     */
    private fun checkBargeIn(micLevel: Float) {
        if (micLevel > BARGE_IN_THRESHOLD) {
            val currentTime = System.currentTimeMillis()
            
            if (bargeInJob == null) {
                bargeInJob = serviceScope?.launch {
                    delay(BARGE_IN_DURATION_MS)
                    
                    // Check if still above threshold
                    if (_micLevel.value > BARGE_IN_THRESHOLD && 
                        currentTime - lastBargeInTime > 2000) {
                        
                        Log.d(TAG, "✋ Barge-in detected")
                        handleBargeIn()
                        lastBargeInTime = currentTime
                    }
                    
                    bargeInJob = null
                }
            }
        } else {
            bargeInJob?.cancel()
            bargeInJob = null
        }
    }
    
    /**
     * Handle user barge-in
     */
    private fun handleBargeIn() {
        realtimeClient?.bargeIn()
        _voiceState.value = VoiceState.LISTENING
        voiceUiCues?.stopThinkingLoop()
        voiceUiCues?.playListenStart()
    }
    
    /**
     * Handle user starting to speak
     */
    private fun handleUserSpeaking() {
        if (_voiceState.value == VoiceState.LISTENING) {
            _voiceState.value = VoiceState.THINKING
            voiceUiCues?.startThinkingLoop()
            
            // Simulate processing user input
            serviceScope?.launch {
                delay(2000) // Simulate processing time
                
                // Simulate AI response
                val response = "I understand. Let me check that for you."
                handleAIResponse(response)
            }
        }
    }
    
    /**
     * Handle AI response
     */
    private fun handleAIResponse(response: String) {
        Log.d(TAG, "🗣️ AI Response: $response")
        
        _voiceState.value = VoiceState.SPEAKING
        _transcript.value = response
        
        voiceUiCues?.stopThinkingLoop()
        
        // Simulate TTS playback
        serviceScope?.launch {
            // Simulate speaking duration (150 words per minute)
            val wordCount = response.split(" ").size
            val speakingDurationMs = (wordCount * 400L) // ~400ms per word
            
            delay(speakingDurationMs)
            
            // Speaking completed
            _voiceState.value = VoiceState.LISTENING
            _transcript.value = ""
            voiceUiCues?.playListenStart()
        }
    }
    
    /**
     * Initialize service components
     */
    private fun initializeComponents() {
        Log.d(TAG, "🔧 Initializing service components")
        
        // Create realtime client
        realtimeClient = RealtimeClient(this, object : RealtimeClient.RealtimeCallbacks {
            override fun onTtsStart() {
                _voiceState.value = VoiceState.SPEAKING
                voiceUiCues?.stopThinkingLoop()
            }
            
            override fun onTtsEnd() {
                _voiceState.value = VoiceState.LISTENING
                voiceUiCues?.playListenStart()
            }
            
            override fun onAssistantAudioLevel(level: Float) {
                _assistantLevel.value = level
            }
            
            override fun onUserPartialTranscript(text: String) {
                _transcript.value = text
            }
            
            override fun onUserFinalTranscript(text: String) {
                _transcript.value = text
                _voiceState.value = VoiceState.THINKING
                voiceUiCues?.startThinkingLoop()
                
                Log.d(TAG, "🎤 Final transcript received: '$text'")
                
                // Check if user is referencing a permit
                checkForPermitReference(text)
            }
            
            override fun onError(error: Throwable) {
                Log.e(TAG, "❌ Realtime client error", error)
                _error.value = "Voice chat error: ${error.message}"
            }
            
            override fun onConnectionStateChange(connected: Boolean) {
                _isConnected.value = connected
                Log.d(TAG, "📡 Connection state: $connected")
                
                // Transition from CONNECTING to LISTENING when connected
                if (connected && _voiceState.value == VoiceState.CONNECTING) {
                    _voiceState.value = VoiceState.LISTENING
                    Log.d(TAG, "🎤 Voice chat ready - user can start speaking")
                    
                    // Play connection ready ding sound
                    voiceUiCues?.playConnectionReady()
                    
                    // Update session with advanced voice configuration
                    realtimeClient?.updateSessionConfig()
                }
            }
        })
        
        // Create audio controller
        audioController = AudioController(this)
        
        // Create voice UI cues
        voiceUiCues = VoiceUiCuesFactory.createForVoiceChat(this)
        
        // Initialize permit repository and matcher
        val database = PermitNavDatabase.getDatabase(this)
        permitRepository = PermitRepository(database.permitDao())
        permitVoiceMatcher = PermitVoiceMatcher(permitRepository!!)
        
        Log.d(TAG, "✅ Service components initialized")
    }
    
    /**
     * Check if user is referencing a permit in their voice command
     * Examples: "Check permit ending in 789", "My Chicago permit", etc.
     */
    private fun checkForPermitReference(voiceText: String) {
        Log.d(TAG, "🔍 checkForPermitReference called with: '$voiceText'")
        serviceScope?.launch {
            try {
                val match = permitVoiceMatcher?.findPermitFromVoiceCommand(voiceText)
                if (match != null && match.permit != null) {
                    switchPermitContext(match.permit)
                    
                    // Generate and speak response about permit switch
                    val response = permitVoiceMatcher?.generatePermitSwitchResponse(match)
                    if (response != null) {
                        // Send this response to the AI to acknowledge the permit switch
                        sendPermitContextUpdate(response)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for permit reference", e)
            }
        }
    }
    
    /**
     * Switch the current permit context
     */
    private fun switchPermitContext(permit: Permit) {
        Log.d(TAG, "Switching permit context to: ${permit.permitNumber}")
        currentPermit = permit
        _currentPermitContext.value = permit
        
        // Format permit context the same way text chat does
        val permitContext = formatPermitForChat(permit)
        
        // Update the AI assistant with new permit context
        val contextMessage = buildPermitContextMessage(permit)
        
        // Send permit context to AI system - mimic text chat behavior
        sendPermitContextToAI(permitContext, contextMessage)
        
        Log.d(TAG, "Updated permit context: $contextMessage")
    }
    
    /**
     * Build a context message about the current permit
     */
    private fun buildPermitContextMessage(permit: Permit): String {
        return """
            Current permit context:
            - Permit Number: ${permit.permitNumber}
            - State: ${permit.state}
            - Expires: ${permit.expirationDate}
            - Weight: ${permit.dimensions.weight} lbs
            - Height: ${permit.dimensions.height} ft
            - Width: ${permit.dimensions.width} ft
            - Length: ${permit.dimensions.length} ft
            - Destination: ${permit.destination ?: "Not specified"}
            - Restrictions: ${permit.restrictions.size} active
        """.trimIndent()
    }
    
    /**
     * Send permit context update to the AI
     */
    private fun sendPermitContextUpdate(message: String) {
        // This would integrate with the RealtimeClient to inform the AI
        // For now, just log it
        Log.d(TAG, "Permit context update: $message")
        
        // In production, this would send a system message to the AI:
        // realtimeClient?.sendSystemMessage(message)
    }
    
    /**
     * Send permit context to AI system - mimics text chat behavior
     */
    private fun sendPermitContextToAI(permitContext: String, contextMessage: String) {
        Log.d(TAG, "🤖 Sending permit context to AI system")
        Log.d(TAG, "📋 Permit context: $permitContext")
        Log.d(TAG, "💬 Context message: $contextMessage")
        
        // This is where we would update the AI system prompt with permit information
        // Same approach as ChatViewModel.loadPermitContext() and sendMessage()
        
        // The AI should now have access to:
        // 1. Formatted permit details (permitContext)
        // 2. System message about permit switch (contextMessage)
        
        // When RealtimeClient supports context updates, this would be:
        // realtimeClient?.updateSystemPrompt(permitContext)
        // realtimeClient?.sendSystemMessage(contextMessage)
        
        // For now, ensure this information is available when AI makes calls
        realtimeClient?.setPermitContext(permitContext)
    }
    
    /**
     * Format permit data for chat context - same as ChatService.formatPermitForChat()
     */
    private fun formatPermitForChat(permit: Permit): String {
        return buildString {
            append("PERMIT DETAILS:\n")
            append("Permit Number: ${permit.permitNumber}\n")
            append("State: ${permit.state}\n")
            append("Issue Date: ${permit.issueDate}\n")
            append("Expiration Date: ${permit.expirationDate}\n")
            append("\nDIMENSIONS:\n")
            permit.dimensions.let { dim ->
                dim.length?.let { append("Length: ${it}ft\n") }
                dim.width?.let { append("Width: ${it}ft\n") }
                dim.height?.let { append("Height: ${it}ft\n") }
                dim.weight?.let { append("Weight: ${it}lbs\n") }
                dim.axles?.let { append("Axles: $it\n") }
            }
            append("\nVEHICLE INFO:\n")
            append("License Plate: ${permit.vehicleInfo.licensePlate}\n")
            append("Make/Model: ${permit.vehicleInfo.make} ${permit.vehicleInfo.model}\n")
            
            if (permit.restrictions.isNotEmpty()) {
                append("\nRESTRICTIONS:\n")
                permit.restrictions.forEach { restriction ->
                    append("- $restriction\n")
                }
            }
            
            if (!permit.routeDescription.isNullOrBlank()) {
                append("\nROUTE: ${permit.routeDescription}\n")
            }
        }
    }
    
    /**
     * Get current permit information for voice responses
     */
    fun getCurrentPermitInfo(): String? {
        val permit = currentPermit ?: return null
        return "Currently discussing permit ${permit.permitNumber} for ${permit.state}"
    }
    
    /**
     * Cleanup service components
     */
    private fun cleanupComponents() {
        Log.d(TAG, "🧹 Cleaning up service components")
        
        realtimeClient?.cleanup()
        realtimeClient = null
        
        audioController?.cleanup()
        audioController = null
        
        voiceUiCues?.cleanup()
        voiceUiCues = null
    }
    
    /**
     * Create notification channel for foreground service
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Chat",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Voice chat with AI dispatcher"
            setSound(null, null)
            enableVibration(false)
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    /**
     * Create foreground service notification
     */
    private fun createNotification(message: String = "Speaking with AI dispatcher"): Notification {
        val stopIntent = Intent(this, VoiceSessionService::class.java).apply {
            action = "STOP_SESSION"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Chat Active")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.mipmap.ic_launcher,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
}