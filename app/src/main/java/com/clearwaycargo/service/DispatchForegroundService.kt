package com.clearwaycargo.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clearwaycargo.voice.HotwordEngine
import com.clearwaycargo.voice.BackgroundSpeechToText
import com.clearwaycargo.voice.RouteCoordinator
import com.permitnav.R
import kotlinx.coroutines.*
import java.util.*

/**
 * Background foreground service that provides always-on hands-free dispatcher
 * Activated by "Hey Clearway Dispatch" hotword, no UI interaction
 * Maintains persistent notification and handles audio focus properly
 */
class DispatchForegroundService : Service(), TextToSpeech.OnInitListener {
    
    companion object {
        private const val TAG = "DispatchForegroundService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "background_dispatcher_channel"
        
        // Service control actions
        const val ACTION_PAUSE_MIC = "pause_mic"
        const val ACTION_RESUME_MIC = "resume_mic" 
        const val ACTION_STOP_DISPATCH = "stop_dispatch"
    }
    
    // Core components
    private var hotwordEngine: HotwordEngine? = null
    private var speechToText: BackgroundSpeechToText? = null
    private var textToSpeech: TextToSpeech? = null
    private var routeCoordinator: RouteCoordinator? = null
    
    // Audio management
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Service state
    private var serviceScope: CoroutineScope? = null
    private var isDispatchActive = false
    private var isMicrophonePaused = false
    private var lastHotwordTime = 0L
    private val HOTWORD_DEBOUNCE_MS = 2000L
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 Background dispatcher service created")
        
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        
        // CRITICAL: Start foreground service immediately
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Background Dispatcher starting..."))
        
        // Initialize components with delay to avoid crashes
        serviceScope?.launch {
            delay(1000) // Give the service time to fully start
            initializeService()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "▶️ Service start command: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_PAUSE_MIC -> pauseMicrophone()
            ACTION_RESUME_MIC -> resumeMicrophone()
            ACTION_STOP_DISPATCH -> stopDispatchSession()
            else -> {
                // Default start - ensure service is running
                if (!isDispatchActive && !isMicrophonePaused) {
                    startHotwordListening()
                }
            }
        }
        
        // Keep service alive even if process dies
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d(TAG, "🛑 Background dispatcher service destroyed")
        
        stopDispatchSession()
        stopHotwordListening()
        cleanupResources()
        
        serviceScope?.cancel()
        serviceScope = null
        
        super.onDestroy()
    }
    
    /**
     * Initialize all service components
     */
    private fun initializeService() {
        Log.d(TAG, "🔧 Initializing background dispatcher components")
        
        try {
            // Audio manager
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Wake lock for keeping CPU alive during voice interaction
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DispatchForegroundService::WakeLock"
            )
            
            Log.d(TAG, "✅ Basic components initialized")
            
            // Initialize components that may fail gracefully
            initializeAudioComponents()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize basic components", e)
            // Don't crash - service can still run with limited functionality
            updateNotification("Service started with limited functionality")
        }
    }
    
    /**
     * Initialize audio components that may fail
     */
    private fun initializeAudioComponents() {
        try {
            // Text-to-speech
            Log.d(TAG, "🔊 Initializing TTS")
            textToSpeech = TextToSpeech(this, this)
            
            Log.d(TAG, "✅ TTS initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize TTS", e)
        }
        
        try {
            // Route coordinator for navigation queries
            Log.d(TAG, "🗺️ Initializing RouteCoordinator")
            routeCoordinator = RouteCoordinator(this)
            
            Log.d(TAG, "✅ RouteCoordinator initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize RouteCoordinator", e)
            // Continue without RouteCoordinator
        }
        
        // Delay hotword initialization to avoid startup crashes
        serviceScope?.launch {
            delay(3000) // Wait 3 seconds for system to stabilize
            initializeHotwordDetection()
        }
    }
    
    /**
     * Initialize hotword detection components
     */
    private fun initializeHotwordDetection() {
        try {
            Log.d(TAG, "🎤 Initializing speech components")
            
            // Speech-to-text (will be initialized when needed)
            speechToText = BackgroundSpeechToText(this) { transcript ->
                handleUserSpeech(transcript)
            }
            
            // Hotword engine
            hotwordEngine = HotwordEngine(this) {
                onHotwordDetected()
            }
            
            // Start listening for hotwords if we have permission
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startHotwordListening()
            } else {
                Log.w(TAG, "⚠️ RECORD_AUDIO permission not granted")
                updateNotification("Microphone permission required")
            }
            
            Log.d(TAG, "✅ Speech components initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize speech components", e)
            updateNotification("Speech recognition unavailable")
        }
    }
    
    /**
     * Start hotword listening
     */
    private fun startHotwordListening() {
        if (isMicrophonePaused) {
            Log.d(TAG, "🔇 Microphone paused, skipping hotword start")
            return
        }
        
        Log.d(TAG, "🎤 Starting hotword listening")
        
        try {
            hotwordEngine?.startListening()
            updateNotification("Listening for 'Hey Clearway Dispatch'")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start hotword listening", e)
            updateNotification("Microphone unavailable - check permissions")
        }
    }
    
    /**
     * Stop hotword listening
     */
    private fun stopHotwordListening() {
        Log.d(TAG, "🔇 Stopping hotword listening")
        hotwordEngine?.stopListening()
    }
    
    /**
     * Called when "Hey Clearway Dispatch" is detected
     */
    private fun onHotwordDetected() {
        // Debounce hotword detection to prevent multiple triggers
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastHotwordTime < HOTWORD_DEBOUNCE_MS) {
            Log.d(TAG, "⏰ Hotword debounced - too soon after last detection")
            return
        }
        lastHotwordTime = currentTime
        
        Log.d(TAG, "🎯 Hotword detected - activating dispatch session")
        
        serviceScope?.launch {
            try {
                // Stop hotword listening FIRST to release microphone
                stopHotwordListening()
                
                // Small delay to ensure mic is released
                delay(300)
                
                // Acquire wake lock to keep CPU active
                acquireWakeLock()
                
                // Play confirmation beep
                playConfirmationBeep()
                
                // Start dispatch session but don't start listening yet
                isDispatchActive = true
                updateNotification("Dispatch Active - Say 'Stop Dispatch' to end")
                
                // Speak prompt using TTS
                serviceScope?.launch {
                    speakResponse("Yes? How can I help you?", skipListeningRestart = true)
                    
                    // Wait for TTS to finish  
                    delay(2500)
                    
                    // NOW start listening for user command
                    if (isDispatchActive) {
                        speechToText?.startListening()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to handle hotword detection", e)
                // Return to hotword listening on error
                startHotwordListening()
            }
        }
    }
    
    /**
     * Start active dispatch session with speech recognition
     */
    private fun startDispatchSession() {
        // This method is no longer needed - logic moved to onHotwordDetected
        Log.d(TAG, "startDispatchSession called but logic is in onHotwordDetected")
    }
    
    /**
     * Stop active dispatch session
     */
    private fun stopDispatchSession() {
        if (!isDispatchActive) return
        
        Log.d(TAG, "⏹️ Stopping dispatch session")
        
        isDispatchActive = false
        
        // Stop speech recognition
        speechToText?.stopListening()
        
        // Release wake lock
        releaseWakeLock()
        
        // Speak confirmation and return to hotword listening
        serviceScope?.launch {
            delay(500) // Brief pause
            
            textToSpeech?.speak(
                "Dispatch closed.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "dispatch_closed"
            )
            
            delay(2000) // Wait for TTS to finish
            
            // Resume hotword listening
            if (!isMicrophonePaused) {
                startHotwordListening()
            } else {
                updateNotification("Microphone paused - tap Resume to listen")
            }
        }
    }
    
    /**
     * Handle recognized speech from user
     */
    private fun handleUserSpeech(transcript: String) {
        Log.d(TAG, "🗣️ User said: '$transcript'")
        
        // Check for stop phrase first
        if (transcript.contains("stop dispatch", ignoreCase = true)) {
            stopDispatchSession()
            return
        }
        
        // Route the query to appropriate handler
        serviceScope?.launch {
            try {
                val response = routeUserQuery(transcript)
                speakResponse(response)
                
                // After responding, wait for TTS to finish
                delay(3000) // Give time for TTS to complete
                
                // Restart listening for next command
                if (isDispatchActive) {
                    speechToText?.startListening()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to handle user speech", e)
                speakResponse("I'm having trouble processing that. Please try again or say 'stop dispatch' to end.")
                
                // Try to restart listening after error
                delay(3000)
                if (isDispatchActive) {
                    speechToText?.startListening()
                }
            }
        }
    }
    
    /**
     * Route user query to appropriate handler
     */
    private suspend fun routeUserQuery(query: String): String {
        return when {
            isNavigationQuery(query) -> {
                routeCoordinator?.handleNavigationQuery(query) 
                    ?: "Navigation assistance is not available right now."
            }
            
            isHazardQuery(query) -> {
                routeCoordinator?.handleHazardQuery(query)
                    ?: "Hazard information is not available right now."
            }
            
            isPermitQuery(query) -> {
                routeCoordinator?.handlePermitQuery(query)
                    ?: "Permit information is not available right now."
            }
            
            else -> {
                // General dispatcher questions - use existing LLM with context
                routeCoordinator?.handleGeneralQuery(query)
                    ?: "I can help with navigation, hazards, and permit questions. What do you need?"
            }
        }
    }
    
    /**
     * Check if query is navigation-related
     */
    private fun isNavigationQuery(query: String): Boolean {
        val navKeywords = listOf(
            "turn", "maneuver", "direction", "next", "ahead", "route", "way", 
            "exit", "merge", "lane", "left", "right", "straight"
        )
        return navKeywords.any { query.contains(it, ignoreCase = true) }
    }
    
    /**
     * Check if query is hazard-related
     */
    private fun isHazardQuery(query: String): Boolean {
        val hazardKeywords = listOf(
            "hazard", "bridge", "height", "clearance", "restriction", "warning",
            "low bridge", "construction", "weight limit", "danger"
        )
        return hazardKeywords.any { query.contains(it, ignoreCase = true) }
    }
    
    /**
     * Check if query is permit-related
     */
    private fun isPermitQuery(query: String): Boolean {
        val permitKeywords = listOf(
            "permit", "legal", "compliant", "escort", "oversize", "overweight",
            "violation", "restriction", "regulation", "dot"
        )
        return permitKeywords.any { query.contains(it, ignoreCase = true) }
    }
    
    /**
     * Speak response using TTS with proper audio focus
     */
    private fun speakResponse(text: String, skipListeningRestart: Boolean = false) {
        Log.d(TAG, "🗣️ Speaking: $text")
        
        // Request audio focus for TTS
        requestAudioFocus()
        
        // Use navigation guidance audio stream for TTS
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
        
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "dispatch_response")
    }
    
    /**
     * Play confirmation beep when hotword is detected
     */
    private fun playConfirmationBeep() {
        // Simple tone generator or use TTS for "ready" sound
        val params = HashMap<String, String>()
        textToSpeech?.playSilence(100, TextToSpeech.QUEUE_FLUSH, params)
        
        // Could also use ToneGenerator for actual beep:
        // val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        // toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
    }
    
    /**
     * Pause microphone listening
     */
    private fun pauseMicrophone() {
        Log.d(TAG, "⏸️ Pausing microphone")
        
        isMicrophonePaused = true
        stopHotwordListening()
        
        if (isDispatchActive) {
            stopDispatchSession()
        }
        
        updateNotification("Microphone paused - tap Resume to listen")
    }
    
    /**
     * Resume microphone listening
     */
    private fun resumeMicrophone() {
        Log.d(TAG, "▶️ Resuming microphone")
        
        isMicrophonePaused = false
        
        if (!isDispatchActive) {
            startHotwordListening()
        }
    }
    
    /**
     * Request audio focus for TTS playback
     */
    private fun requestAudioFocus() {
        audioManager?.let { am ->
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "🔊 Audio focus change: $focusChange")
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            // Stop TTS and abandon focus
                            textToSpeech?.stop()
                            abandonAudioFocus()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // Pause TTS
                            textToSpeech?.stop()
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            // Resume or continue normally
                        }
                    }
                }
                .build()
            
            audioFocusRequest?.let { request ->
                am.requestAudioFocus(request)
            }
        }
    }
    
    /**
     * Abandon audio focus after TTS
     */
    private fun abandonAudioFocus() {
        audioManager?.let { am ->
            audioFocusRequest?.let { request ->
                am.abandonAudioFocusRequest(request)
            }
        }
    }
    
    /**
     * Acquire wake lock to keep CPU active during voice interaction
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(300000) // 5 minutes max
            Log.d(TAG, "🔋 Wake lock acquired")
        }
    }
    
    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "🔋 Wake lock released")
        }
    }
    
    /**
     * TextToSpeech initialization callback
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                // Set language
                tts.language = Locale.US
                
                // Try to use a more natural voice if available
                try {
                    // Set speech rate slightly slower for clarity
                    tts.setSpeechRate(0.95f)
                    
                    // Set pitch for more natural sound
                    tts.setPitch(1.0f)
                    
                    // Try to use Google's neural voice if available
                    val voices = tts.voices
                    val preferredVoice = voices?.firstOrNull { voice ->
                        voice.name.contains("en-us-x-tpf", ignoreCase = true) || // Female neural
                        voice.name.contains("en-us-x-tpm", ignoreCase = true) || // Male neural  
                        voice.name.contains("neural", ignoreCase = true) ||
                        (voice.quality == android.speech.tts.Voice.QUALITY_VERY_HIGH && 
                         !voice.isNetworkConnectionRequired)
                    }
                    
                    if (preferredVoice != null) {
                        tts.voice = preferredVoice
                        Log.d(TAG, "🎙️ Using voice: ${preferredVoice.name}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set preferred voice", e)
                }
                
                // Set audio attributes for navigation guidance
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                
                tts.setAudioAttributes(audioAttributes)
                
                Log.d(TAG, "✅ TextToSpeech initialized successfully")
            }
        } else {
            Log.e(TAG, "❌ TextToSpeech initialization failed")
        }
    }
    
    /**
     * Create notification channel for the service
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Dispatcher",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Always-on hands-free dispatcher service"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    /**
     * Build notification for foreground service
     */
    private fun buildNotification(message: String): Notification {
        // Pause/Resume action
        val pauseResumeAction = if (isMicrophonePaused) {
            createNotificationAction("▶️ Resume", ACTION_RESUME_MIC)
        } else {
            createNotificationAction("⏸️ Pause", ACTION_PAUSE_MIC)
        }
        
        // Stop dispatch action (if active)
        val stopAction = if (isDispatchActive) {
            createNotificationAction("⏹️ Stop", ACTION_STOP_DISPATCH)
        } else null
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clearway Dispatch")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(pauseResumeAction)
        
        stopAction?.let { builder.addAction(it) }
        
        return builder.build()
    }
    
    /**
     * Create notification action
     */
    private fun createNotificationAction(title: String, action: String): NotificationCompat.Action {
        val intent = Intent(this, DispatchForegroundService::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getService(
            this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Action.Builder(0, title, pendingIntent).build()
    }
    
    /**
     * Update notification with new message
     */
    private fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val notificationManager = NotificationManagerCompat.from(this)
        
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to update notification - missing permission", e)
        }
    }
    
    /**
     * Clean up all resources
     */
    private fun cleanupResources() {
        Log.d(TAG, "🧹 Cleaning up background dispatcher resources")
        
        hotwordEngine?.cleanup()
        hotwordEngine = null
        
        speechToText?.cleanup()
        speechToText = null
        
        textToSpeech?.shutdown()
        textToSpeech = null
        
        routeCoordinator?.cleanup()
        routeCoordinator = null
        
        abandonAudioFocus()
        releaseWakeLock()
    }
}