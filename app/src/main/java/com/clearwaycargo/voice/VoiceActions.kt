package com.clearwaycargo.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.permitnav.ai.OpenAIService
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.repository.PermitRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.*

object VoiceActions {
    private const val TAG = "VoiceActions"
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * Initialize TTS if not already initialized
     */
    private fun ensureTTS(ctx: Context, onReady: () -> Unit = {}) {
        if (ttsInitialized && tts != null) {
            onReady()
            return
        }
        
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    engine.language = Locale.US
                    engine.setSpeechRate(0.95f)
                    engine.setPitch(1.0f)
                    
                    // Set audio attributes for navigation
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    engine.setAudioAttributes(audioAttributes)
                    
                    // Try to use a better voice if available
                    try {
                        val voices = engine.voices
                        val preferredVoice = voices?.firstOrNull { voice ->
                            voice.name.contains("en-us-x-tpf", ignoreCase = true) ||
                            voice.name.contains("en-us-x-tpm", ignoreCase = true) ||
                            voice.quality == android.speech.tts.Voice.QUALITY_VERY_HIGH
                        }
                        if (preferredVoice != null) {
                            engine.voice = preferredVoice
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set preferred voice", e)
                    }
                    
                    ttsInitialized = true
                    onReady()
                }
            }
        }
    }
    
    /**
     * Speak text using TTS
     */
    private fun speak(ctx: Context, text: String) {
        Log.d(TAG, "🗣️ Speaking: $text")
        ensureTTS(ctx) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "voice_action")
        }
    }
    
    /**
     * Read next navigation maneuver
     */
    fun readNextManeuver(ctx: Context) {
        Log.d(TAG, "📍 Reading next maneuver")
        // In a real implementation, this would pull from NavigationViewModel or HERE SDK
        // For now, provide a sample response
        speak(ctx, "In 2 miles, take exit 45 toward Route 52 West")
    }
    
    /**
     * Read upcoming hazards
     */
    fun readUpcomingHazards(ctx: Context) {
        Log.d(TAG, "⚠️ Reading upcoming hazards")
        // In a real implementation, this would check HERE hazards cache
        speak(ctx, "Low bridge ahead in 5 miles. Height limit 13 feet 6 inches. Your load height is safe to proceed.")
    }
    
    /**
     * Read ETA
     */
    fun readEta(ctx: Context) {
        Log.d(TAG, "⏰ Reading ETA")
        // In a real implementation, this would get ETA from HERE routing
        speak(ctx, "Your estimated arrival time is 3:45 PM, approximately 2 hours and 15 minutes from now")
    }
    
    /**
     * Request compliance-based reroute
     */
    fun requestComplianceReroute(ctx: Context) {
        Log.d(TAG, "🔄 Requesting compliance reroute")
        // In a real implementation, this would trigger HERE route recalculation
        speak(ctx, "Calculating compliant alternate route. Please wait.")
        
        // Simulate route calculation
        scope.launch {
            delay(2000)
            speak(ctx, "New route found. Take Highway 9 North to avoid weight restriction. This adds 12 minutes to your trip.")
        }
    }
    
    /**
     * Check permit compliance
     */
    fun checkPermitCompliance(ctx: Context) {
        Log.d(TAG, "📋 Checking permit compliance")
        
        scope.launch {
            try {
                // Get current permit from database
                val database = PermitNavDatabase.getDatabase(ctx)
                val permitRepository = PermitRepository(database.permitDao())
                val permits = permitRepository.getAllPermits()
                    .first()
                    .maxByOrNull { it.issueDate }
                
                if (permits != null) {
                    val daysUntilExpiry = kotlin.math.max(0, 
                        (permits.expirationDate.time - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)
                    ).toInt()
                    
                    val message = when {
                        daysUntilExpiry < 0 -> "Warning: Your permit expired ${kotlin.math.abs(daysUntilExpiry)} days ago"
                        daysUntilExpiry == 0 -> "Your permit expires today"
                        daysUntilExpiry <= 3 -> "Your permit expires in $daysUntilExpiry days"
                        else -> "Your permit is valid for $daysUntilExpiry more days"
                    }
                    speak(ctx, message)
                } else {
                    speak(ctx, "No active permit found. Please load a permit in the app.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking permit", e)
                speak(ctx, "Unable to check permit status right now")
            }
        }
    }
    
    /**
     * Answer general question using AI
     */
    fun answerGeneral(ctx: Context, text: String) {
        Log.d(TAG, "💬 Answering general question: $text")
        
        scope.launch {
            try {
                // Use OpenAI for general dispatch questions
                val openAIService = OpenAIService()
                val prompt = """
                    You are an AI dispatcher assistant for truck drivers.
                    Provide a brief, spoken response (1-2 sentences max) to this question: "$text"
                    Keep the response conversational and trucker-friendly.
                """.trimIndent()
                
                val response = openAIService.generalChat(prompt)
                speak(ctx, response)
            } catch (e: Exception) {
                Log.e(TAG, "Error with AI response", e)
                speak(ctx, "I can help with navigation, hazards, permits, and general trucking questions. Please try rephrasing your question.")
            }
        }
    }
    
    /**
     * Clean up TTS when service stops
     */
    fun cleanup() {
        tts?.shutdown()
        tts = null
        ttsInitialized = false
        scope.cancel()
    }
}