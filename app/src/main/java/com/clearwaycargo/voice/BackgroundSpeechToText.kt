package com.clearwaycargo.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*

/**
 * Background speech-to-text component for the dispatcher service
 * Uses Android's built-in SpeechRecognizer for continuous recognition
 * Handles "Stop Dispatch" commands locally without cloud roundtrip
 */
class BackgroundSpeechToText(
    private val context: Context,
    private val onTranscriptReceived: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "BackgroundSpeechToText"
        private const val RECOGNITION_TIMEOUT_MS = 10000L // 10 seconds
        private const val RESTART_DELAY_MS = 1000L // 1 second between restarts
        private const val MAX_CONSECUTIVE_ERRORS = 3 // Stop after too many errors
    }
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var recognitionJob: Job? = null
    private var restartJob: Job? = null
    private var consecutiveErrors = 0
    private var isSingleShot = false // One-shot mode for commands
    
    /**
     * Start speech recognition (single-shot mode for commands)
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "⚠️ Already listening for speech")
            return
        }
        
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "❌ Speech recognition not available")
            onTranscriptReceived("Speech recognition is not available on this device")
            return
        }
        
        Log.d(TAG, "🎤 Starting command speech recognition")
        
        isListening = true
        isSingleShot = true // Command mode - don't auto-restart
        consecutiveErrors = 0
        startRecognition()
    }
    
    /**
     * Stop speech recognition
     */
    fun stopListening() {
        if (!isListening) return
        
        Log.d(TAG, "🔇 Stopping background speech recognition")
        
        isListening = false
        
        // Cancel jobs
        recognitionJob?.cancel()
        recognitionJob = null
        
        restartJob?.cancel()
        restartJob = null
        
        // Stop and release speech recognizer
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping speech recognizer", e)
        }
        
        Log.d(TAG, "✅ Background speech recognition stopped")
    }
    
    /**
     * Start speech recognition with automatic restart
     */
    private fun startRecognition() {
        if (!isListening) return
        
        try {
            // Create speech recognizer
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            
            // Configure recognition intent
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
            }
            
            // Start recognition
            speechRecognizer?.startListening(intent)
            
            // Set timeout to restart recognition if needed
            recognitionJob = CoroutineScope(Dispatchers.Main).launch {
                delay(RECOGNITION_TIMEOUT_MS)
                if (isListening) {
                    Log.d(TAG, "🔄 Recognition timeout - restarting")
                    restartRecognition()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start speech recognition", e)
            scheduleRestart()
        }
    }
    
    /**
     * Create recognition listener
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            
            override fun onReadyForSpeech(params: Bundle?) {
                Log.v(TAG, "🎤 Ready for speech")
            }
            
            override fun onBeginningOfSpeech() {
                Log.v(TAG, "🗣️ Beginning of speech detected")
                // Cancel timeout since we detected speech
                recognitionJob?.cancel()
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // Audio level monitoring - could be used for UI feedback
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // Raw audio data - not needed for this implementation
            }
            
            override fun onEndOfSpeech() {
                Log.v(TAG, "🤐 End of speech detected")
            }
            
            override fun onError(error: Int) {
                val errorMessage = getErrorMessage(error)
                Log.w(TAG, "⚠️ Speech recognition error: $errorMessage")
                
                // In single-shot mode, don't auto-restart on normal errors
                if (isSingleShot) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            // Normal - user didn't say anything
                            Log.d(TAG, "🔇 No speech detected - stopping")
                            stopListening()
                            return
                        }
                    }
                }
                
                // Track consecutive errors
                consecutiveErrors++
                
                // Handle different error types
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Don't restart in command mode
                        if (isSingleShot) {
                            stopListening()
                        } else if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                            scheduleRestart()
                        } else {
                            Log.e(TAG, "❌ Too many consecutive errors - stopping")
                            stopListening()
                        }
                    }
                    
                    SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        // Recoverable errors - maybe restart
                        if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                            scheduleRestart()
                        } else {
                            Log.e(TAG, "❌ Too many consecutive errors - stopping")
                            stopListening()
                        }
                    }
                    
                    else -> {
                        // Other errors - stop to avoid loops
                        Log.e(TAG, "❌ Serious speech recognition error: $errorMessage")
                        stopListening()
                    }
                }
            }
            
            override fun onResults(results: Bundle?) {
                handleRecognitionResults(results, false)
                
                // Reset error counter on success
                consecutiveErrors = 0
                
                // In single-shot mode, stop after getting result
                if (isSingleShot) {
                    stopListening()
                } else {
                    // Continuous mode - restart
                    scheduleRestart()
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                handleRecognitionResults(partialResults, true)
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.v(TAG, "📡 Speech recognition event: $eventType")
            }
        }
    }
    
    /**
     * Handle recognition results (both partial and final)
     */
    private fun handleRecognitionResults(results: Bundle?, isPartial: Boolean) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        
        if (!matches.isNullOrEmpty()) {
            val transcript = matches[0]
            val logPrefix = if (isPartial) "📝 Partial" else "✅ Final"
            
            Log.d(TAG, "$logPrefix transcript: '$transcript'")
            
            // Only process final results for action
            if (!isPartial && transcript.isNotBlank()) {
                onTranscriptReceived(transcript)
            }
        }
    }
    
    /**
     * Restart speech recognition after a delay
     */
    private fun scheduleRestart() {
        if (!isListening) return
        
        restartJob = CoroutineScope(Dispatchers.Main).launch {
            delay(RESTART_DELAY_MS)
            if (isListening) {
                restartRecognition()
            }
        }
    }
    
    /**
     * Restart speech recognition
     */
    private fun restartRecognition() {
        if (!isListening) return
        
        Log.v(TAG, "🔄 Restarting speech recognition")
        
        // Clean up current recognizer
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up speech recognizer", e)
        }
        
        // Start new recognition
        startRecognition()
    }
    
    /**
     * Get human-readable error message
     */
    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error ($error)"
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopListening()
    }
}