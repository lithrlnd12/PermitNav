package com.clearwaycargo.voice

// ============================================================================
// DISABLED: Background Dispatcher Service (Parked for Later)
// ============================================================================
// This service provides always-on "Hey Dispatch" hotword detection that
// triggers OpenAI Realtime voice chat in the background. Currently disabled
// in MainActivity due to beeping issues during speech recognition.
// 
// To re-enable:
// 1. Uncomment startDispatcherServiceIfNeeded() calls in MainActivity
// 2. Add back the background dispatcher UI card in HomeScreen
// 3. Test and refine hotword detection sensitivity
// ============================================================================

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.clearwaycargo.ai.RealtimeClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

class DispatchHotwordService : Service() {

    private val TAG = "DispatchHotword"
    private val CHANNEL_ID = "dispatch_voice_channel"
    private val NOTIF_ID = 4127

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- STATE ----
    private enum class Mode { HOTWORD, SESSION }
    @Volatile private var mode: Mode = Mode.HOTWORD
    private val running = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    private val isAgentSpeaking = AtomicBoolean(false)

    // ---- AUDIO IN (mic) ----
    private var recorder: AudioRecord? = null
    private val SAMPLE_RATE = 16000
    private val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private var minBuf = 0

    // ---- HOTWORD ----
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var recognitionIntent: Intent? = null
    private var isListeningForHotword = false
    private var pendingRecognition = false

    // ---- EXISTING REALTIME CLIENT ----
    private var realtime: RealtimeClient? = null

    // ---- HINTS & BACKOFF ----
    private var hintShown = false
    private lateinit var prefs: SharedPreferences
    private var rtReconnectAttempt = 0
    private var rtActive = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 DispatchHotwordService onCreate")
        logb(TAG, "onCreate")
        
        // Check RECORD_AUDIO permission first
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ RECORD_AUDIO permission not granted - stopping service")
            stopSelf()
            return
        }
        
        try {
            createChannel()
            val stopIntent = Intent(this, DispatchHotwordService::class.java).apply {
                action = "STOP_DISPATCHER"
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            startForeground(
                NOTIF_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setOngoing(true)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setSilent(true)
                    .setColor(Color.BLACK)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setContentTitle("Clearway Cargo — Dispatcher")
                    .setContentText("Ready - Say \"Hey Dispatch\" to start")
                    .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                    .build()
            )
            Log.d(TAG, "✅ Foreground notification started with stop action")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground service", e)
            stopSelf()
            return
        }

        // Init preferences
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        hintShown = prefs.getBoolean(KEY_HINT_SHOWN, false)

        // Init audio input for session streaming (not hotword detection)
        try {
            minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING).coerceAtLeast(4096)
            Log.d(TAG, "📏 AudioRecord buffer size: $minBuf")
            
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 2
            )
            
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioRecord failed to initialize")
                stopSelf()
                return
            }
            
            Log.d(TAG, "✅ AudioRecord initialized successfully for session streaming")
            selectInputDevicePreferBT()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize AudioRecord", e)
            stopSelf()
            return
        }

        // Init hotword detection using Android SpeechRecognizer
        setupSpeechRecognition()

        // Init your EXISTING realtime voice chat client
        setupRealtimeClient()

        running.set(true)
        mode = Mode.HOTWORD
        logb(TAG, "MODE=HOTWORD")
        Log.d(TAG, "🎤 Starting hybrid hotword detection")
        scope.launch { runHotwordLoop() }
        
        Log.d(TAG, "✅ DispatchHotwordService onCreate completed successfully")
    }

    override fun onDestroy() {
        logb(TAG, "onDestroy")
        running.set(false)
        stopStreaming()
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder?.release()
        speechRecognizer?.destroy()
        realtimeStopAndReset()
        realtime?.cleanup()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_DISPATCHER" -> {
                Log.d(TAG, "🛑 Stop action received from notification")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    // ================= REALTIME CLIENT SETUP =================
    private fun setupRealtimeClient() {
        realtime = RealtimeClient(
            context = this,
            callbacks = object : RealtimeClient.RealtimeCallbacks {
                override fun onTtsStart() {
                    logb(TAG, "SPK=true")
                    isAgentSpeaking.set(true)
                    stopStreaming()
                }
                
                override fun onTtsEnd() {
                    logb(TAG, "SPK=false")
                    isAgentSpeaking.set(false)
                    resumeStreamingIfSession()
                }
                
                override fun onAssistantAudioLevel(level: Float) {
                    // Optional: could use for visual feedback
                }
                
                override fun onUserPartialTranscript(text: String) {
                    logb(TAG, "TXT_PART:'${norm(text)}'")
                    // Aggressively check stop phrase on even short partials to beat OpenAI
                    if (isStopPhrase(text) && text.length >= 4) {  // Lower threshold
                        Log.d(TAG, "🛑 IMMEDIATE STOP - partial: '${text}' - cutting off OpenAI")
                        realtime?.bargeIn()  // Interrupt OpenAI immediately
                        stopSessionAndReturnToHotword()
                    }
                }
                
                override fun onUserFinalTranscript(text: String) {
                    logb(TAG, "TXT_FINAL:'${norm(text)}'")
                    // Always check stop phrase on final transcripts
                    if (isStopPhrase(text)) {
                        Log.d(TAG, "🛑 Stop phrase detected in final transcript - stopping session")
                        realtime?.bargeIn()  // Interrupt OpenAI immediately
                        stopSessionAndReturnToHotword()
                    }
                }
                
                override fun onError(error: Throwable) {
                    Log.e(TAG, "RealtimeClient error", error)
                    // Try to recover
                    if (mode == Mode.SESSION) {
                        val wait = backoffMs(rtReconnectAttempt++)
                        scope.launch {
                            delay(wait)
                            if (mode == Mode.SESSION && running.get()) {
                                tryStartRealtime()
                            }
                        }
                    }
                }
                
                override fun onConnectionStateChange(connected: Boolean) {
                    logb(TAG, "RT=connected:$connected")
                    if (connected && mode == Mode.SESSION) {
                        startStreaming()
                        
                        // Send initial greeting when session starts
                        sendInitialGreeting()
                        
                        // Show one-time hint
                        if (!hintShown) {
                            updateNotif("Dispatcher is listening… say \"stop dispatch\" to end.")
                            hintShown = true
                            prefs.edit { putBoolean(KEY_HINT_SHOWN, true) }
                        }
                    }
                }
            }
        )
        
        realtime?.initialize()
    }

    // ================= HYBRID HOTWORD DETECTION =================
    private fun setupSpeechRecognition() {
        Log.d(TAG, "🎤 Setting up speech recognition for hotword verification")
        
        try {
            speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            
            recognitionIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300)
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            
            speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "🎤 SpeechRecognizer ready")
                }
                
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "🎤 Speech recognition started")
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // Reduced logging
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {
                    // No logging
                }
                
                override fun onEndOfSpeech() {
                    Log.d(TAG, "🎤 Speech recognition ended")
                    pendingRecognition = false
                }
                
                override fun onError(error: Int) {
                    pendingRecognition = false
                    when (error) {
                        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> {
                            Log.d(TAG, "🎤 No speech match found")
                        }
                        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            Log.d(TAG, "🎤 Speech timeout")
                        }
                        else -> {
                            val errorMsg = when (error) {
                                android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                                android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error" 
                                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                                else -> "Error $error"
                            }
                            Log.w(TAG, "⚠️ Speech recognition error: $errorMsg")
                        }
                    }
                }
                
                override fun onResults(results: Bundle?) {
                    pendingRecognition = false
                    val matches = results?.getStringArrayList(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    matches?.forEach { result ->
                        Log.d(TAG, "🎯 Speech result: '$result'")
                        checkForHotword(result)
                    }
                }
                
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    matches?.firstOrNull()?.let { result ->
                        Log.v(TAG, "🎯 Partial: '$result'")
                        checkForHotword(result)
                    }
                }
                
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            
            Log.d(TAG, "✅ Speech recognition setup complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup speech recognition", e)
        }
    }
    
    // ================= HYBRID HOTWORD LOOP =================
    private suspend fun runHotwordLoop() = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔄 Starting hybrid hotword detection")
        val buf = ShortArray(minBuf / 2)
        try { 
            recorder?.startRecording() 
            Log.d(TAG, "🎤 AudioRecord started for hotword detection")
        } catch (t: Throwable) { 
            Log.e(TAG, "❌ AudioRecord start failed", t)
            return@withContext 
        }

        var loopCount = 0
        var lastSpeechTriggerTime = 0L
        val speechCooldownMs = 5000L  // Longer cooldown to reduce beeping
        val energyBuffer = mutableListOf<Int>()
        val bufferSize = 10
        
        while (running.get()) {
            if (mode != Mode.HOTWORD) { 
                delay(50)
                continue 
            }
            
            val n = recorder?.read(buf, 0, buf.size) ?: 0
            if (n <= 0) { 
                delay(10)
                continue 
            }

            // Periodic heartbeat (every ~5 seconds)
            if (loopCount++ % 100 == 0) {
                Log.d(TAG, "💓 Hotword detection active (count: $loopCount)")
            }

            // Calculate audio energy
            val rms = rms(buf, n)
            
            // Buffer energy readings for sustained speech detection
            energyBuffer.add(rms)
            if (energyBuffer.size > bufferSize) {
                energyBuffer.removeAt(0)
            }
            
            // Only trigger speech recognition on sustained loud speech
            val now = System.currentTimeMillis()
            val avgEnergy = energyBuffer.average()
            val sustainedSpeech = energyBuffer.size >= bufferSize && 
                                 avgEnergy > 1200 && 
                                 energyBuffer.takeLast(5).all { it > 1000 }
            
            if (sustainedSpeech && !pendingRecognition && (now - lastSpeechTriggerTime) > speechCooldownMs) {
                Log.d(TAG, "🔊 Sustained speech detected (avg RMS: ${avgEnergy.toInt()}) - starting recognition")
                lastSpeechTriggerTime = now
                pendingRecognition = true
                
                withContext(Dispatchers.Main) {
                    try {
                        speechRecognizer?.startListening(recognitionIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to start speech recognition", e)
                        pendingRecognition = false
                    }
                }
            }
        }
        Log.d(TAG, "🛑 Hotword loop ended")
    }
    
    private fun checkForHotword(spokenText: String) {
        val normalizedText = spokenText.lowercase(Locale.US).trim()
        Log.d(TAG, "🔍 Checking: '$normalizedText'")
        
        // Check for hotword patterns
        val hotwordPatterns = listOf(
            "hey dispatch",
            "hey clearway dispatch",
            "clearway dispatch", 
            "a dispatch",
            "hey despatch"
        )
        
        val isHotword = hotwordPatterns.any { pattern ->
            normalizedText.contains(pattern)
        }
        
        if (isHotword) {
            Log.d(TAG, "🎯 HOTWORD DETECTED: '$spokenText'")
            logb(TAG, "HOTWORD=HIT")
            scope.launch(Dispatchers.Main) {
                startRealtimeSession()
            }
        } else {
            Log.d(TAG, "❌ No hotword in: '$spokenText'")
        }
    }

    // ================= SESSION =================
    private fun startRealtimeSession() {
        if (mode == Mode.SESSION) return
        logb(TAG, "MODE=SESSION")
        mode = Mode.SESSION
        updateNotif("Dispatcher is listening…")
        realtimeStartWithRetry()
    }

    private fun stopSessionAndReturnToHotword() {
        logb(TAG, "MODE=HOTWORD (stop)")
        stopStreaming()
        realtimeStopAndReset()
        
        // Brief cooldown to avoid immediate retrigger
        scope.launch {
            delay(400)
            mode = Mode.HOTWORD
            updateNotif("Ready - Say \"Hey Dispatch\" to start")
            // The runHotwordLoop continues running and will automatically resume listening
        }
    }

    // ================= REALTIME CONNECTION MANAGEMENT =================
    private fun realtimeStartWithRetry() {
        if (rtActive) return
        rtActive = true
        rtReconnectAttempt = 0
        tryStartRealtime()
    }

    private fun tryStartRealtime() {
        scope.launch(Dispatchers.Main) {
            try {
                logb(TAG, "RT=start attempt=$rtReconnectAttempt")
                realtime?.startSession()
                
                // Give it time to establish connection
                delay(1000)
                realtime?.updateSessionConfig()
                
                rtReconnectAttempt = 0
                logb(TAG, "RT=started")
            } catch (t: Throwable) {
                logb(TAG, "RT=err attempt=$rtReconnectAttempt ${t.message}")
                val wait = backoffMs(rtReconnectAttempt++)
                delay(wait)
                if (mode == Mode.SESSION && running.get()) {
                    tryStartRealtime()
                } else {
                    logb(TAG, "RT=abort")
                }
            }
        }
    }

    private fun realtimeStopAndReset() {
        if (!rtActive) return
        logb(TAG, "RT=stop")
        rtActive = false
        try { 
            realtime?.stopSession() 
        } catch (_: Throwable) {}
        rtReconnectAttempt = 0
    }

    // ================= MIC STREAMING (SESSION only) =================
    private var streamJob: Job? = null

    private fun startStreaming() {
        if (isStreaming.get()) return
        isStreaming.set(true)
        logb(TAG, "STREAM=start")
        
        streamJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(minBuf / 2)
            try { 
                recorder?.startRecording()
                selectInputDevicePreferBT()
            } catch (_: Throwable) {}
            
            while (isStreaming.get() && running.get() && mode == Mode.SESSION) {
                if (isAgentSpeaking.get()) { 
                    delay(20)
                    continue 
                }
                
                val n = recorder?.read(buf, 0, buf.size) ?: 0
                if (n > 0) {
                    // Send PCM to RealtimeClient
                    // Note: RealtimeClient doesn't expose sendPcm16 directly
                    // It streams via WebRTC automatically when session is active
                    // The actual audio streaming is handled by WebRTC internally
                }
            }
        }
    }

    private fun stopStreaming() {
        if (!isStreaming.get()) return
        logb(TAG, "STREAM=stop")
        isStreaming.set(false)
        streamJob?.cancel()
        streamJob = null
    }

    private fun resumeStreamingIfSession() {
        if (mode == Mode.SESSION && !isStreaming.get()) {
            startStreaming()
        }
    }

    // ================= AUDIO DEVICE SELECTION =================
    private fun selectInputDevicePreferBT() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val bt = devices.firstOrNull { 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP 
        }
        if (bt != null) {
            try { 
                recorder?.preferredDevice = bt 
                logb(TAG, "AUDIO_IN=BT")
            } catch (_: Throwable) {}
        } else {
            logb(TAG, "AUDIO_IN=DEFAULT")
        }
    }

    // ================= GREETING =================
    private fun sendInitialGreeting() {
        Log.d(TAG, "👋 Sending initial greeting")
        try {
            // Use RealtimeClient's built-in greeting capability
            // The AI will naturally greet when the session starts due to system instructions
            Log.d(TAG, "✅ Session started - AI will greet naturally")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send initial greeting", e)
        }
    }

    // ================= UTIL =================
    private fun updateNotif(text: String) {
        val stopIntent = Intent(this, DispatchHotwordService::class.java).apply {
            action = "STOP_DISPATCHER"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setSilent(true)
            .setColor(Color.BLACK)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentTitle("Clearway Cargo — Dispatcher")
            .setContentText(text)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Dispatcher Voice", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Background dispatcher"
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    }
                )
            }
        }
    }

    private fun rms(buf: ShortArray, n: Int): Int {
        var acc = 0.0
        for (i in 0 until n) acc += (buf[i] * buf[i]).toDouble()
        return kotlin.math.sqrt(acc / n).toInt()
    }

    // ================= HELPER FUNCTIONS =================
    companion object {
        private const val PREFS_NAME = "cc_voice_prefs"
        private const val KEY_HINT_SHOWN = "hint_shown_once"

        // Logging helper
        private fun logb(tag: String, msg: String) = Log.d(tag, "DWK:$msg")

        // Normalization
        private fun norm(s: String): String = s
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Stop phrase matcher
        private val STOP_SET = setOf(
            "stop dispatch", "stop", "cancel", "end session", "end",
            "that is all", "thats all", "we're done", "were done"
        )

        private fun isStopPhrase(text: String): Boolean {
            val n = norm(text)
            if (n.isBlank()) return false
            if (n in STOP_SET) return true
            
            // Very aggressive partial matching to beat OpenAI response
            if (n.startsWith("stop")) return true  // Any "stop..." 
            if (n.startsWith("sto")) return true   // Even "sto..." 
            if (n.contains("stop")) return true    // "stop" anywhere
            if (n.startsWith("cancel")) return true
            if (n.startsWith("end")) return true
            if (n.contains("dispatch") && n.contains("stop")) return true
            
            return false
        }

        // Exponential backoff with jitter
        private fun backoffMs(attempt: Int): Long {
            val base = min(8000L, 1000L shl attempt.coerceAtMost(3))
            val jitter = Random.nextLong(-200, 200)
            return (base + jitter).coerceAtLeast(500L)
        }
    }
}

