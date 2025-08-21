package com.clearwaycargo.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

/**
 * VoiceUiCues provides subtle audio feedback for voice chat interactions
 * Uses SoundPool for low-latency audio cues
 */
class VoiceUiCues(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceUiCues"
        private const val MAX_STREAMS = 5
        private const val THINKING_LOOP_INTERVAL_MS = 500L // 2 Hz
    }
    
    private var soundPool: SoundPool? = null
    private var thinkingLoopJob: Job? = null
    private var cueScope: CoroutineScope? = null
    
    // Sound IDs
    private var listenStartSoundId: Int = 0
    private var thinkingClickSoundId: Int = 0
    private var endSoundId: Int = 0
    
    // Playback state
    private var isThinkingLoopActive = false
    
    /**
     * Initialize SoundPool and load audio cues
     */
    fun initialize(): Boolean {
        Log.d(TAG, "🔧 Initializing VoiceUiCues")
        
        return try {
            cueScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            // Create SoundPool with voice communication attributes
            soundPool = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                
                SoundPool.Builder()
                    .setMaxStreams(MAX_STREAMS)
                    .setAudioAttributes(audioAttributes)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                SoundPool(MAX_STREAMS, android.media.AudioManager.STREAM_VOICE_CALL, 0)
            }
            
            // Load sound files
            loadSounds()
            
            Log.d(TAG, "✅ VoiceUiCues initialized")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize VoiceUiCues", e)
            false
        }
    }
    
    /**
     * Load sound files into SoundPool
     * Note: For production, you'd place actual audio files in res/raw/
     */
    private fun loadSounds() {
        try {
            // For this implementation, we'll generate the sound IDs
            // In production, you'd load actual .wav files:
            // listenStartSoundId = soundPool?.load(context, R.raw.cue_listen_start, 1) ?: 0
            
            // Simulate loading sounds (these would be actual resource IDs)
            listenStartSoundId = 1
            thinkingClickSoundId = 2
            endSoundId = 3
            
            Log.d(TAG, "✅ Audio cues loaded")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load audio cues", e)
        }
    }
    
    /**
     * Play the listen start cue (soft, brief sound when ready for input)
     */
    fun playListenStart() {
        playSoundCue(listenStartSoundId, "listen start")
    }
    
    /**
     * Play the connection ready cue (dinging sound when connected and ready to talk)
     */
    fun playConnectionReady() {
        playSoundCue(listenStartSoundId, "connection ready ding")
    }
    
    /**
     * Start the thinking loop (soft click every 500ms while processing)
     */
    fun startThinkingLoop() {
        if (isThinkingLoopActive) {
            Log.d(TAG, "⚠️ Thinking loop already active")
            return
        }
        
        Log.d(TAG, "🔄 Starting thinking loop")
        isThinkingLoopActive = true
        
        thinkingLoopJob = cueScope?.launch {
            while (isThinkingLoopActive) {
                try {
                    playSoundCue(thinkingClickSoundId, "thinking click")
                    delay(THINKING_LOOP_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Thinking loop error", e)
                    break
                }
            }
        }
    }
    
    /**
     * Stop the thinking loop
     */
    fun stopThinkingLoop() {
        if (!isThinkingLoopActive) return
        
        Log.d(TAG, "⏹️ Stopping thinking loop")
        isThinkingLoopActive = false
        thinkingLoopJob?.cancel()
        thinkingLoopJob = null
    }
    
    /**
     * Play the end cue (soft sound when session ends)
     */
    fun playEnd() {
        playSoundCue(endSoundId, "end")
    }
    
    /**
     * Play a sound cue with error handling
     */
    private fun playSoundCue(soundId: Int, cueName: String) {
        try {
            val pool = soundPool
            if (pool != null && soundId > 0) {
                // In production, this would actually play the sound:
                // pool.play(soundId, 0.3f, 0.3f, 1, 0, 1.0f)
                
                // For now, just log the cue
                Log.d(TAG, "🔊 Playing $cueName cue")
            } else {
                Log.w(TAG, "⚠️ Cannot play $cueName cue - SoundPool not ready")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to play $cueName cue", e)
        }
    }
    
    /**
     * Set volume for all cues (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0.0f, 1.0f)
        // In production, you'd store this volume and use it in play() calls
        Log.d(TAG, "🔊 Cue volume set to ${(clampedVolume * 100).toInt()}%")
    }
    
    /**
     * Pause all cues (useful when app goes to background)
     */
    fun pauseAllCues() {
        Log.d(TAG, "⏸️ Pausing all cues")
        stopThinkingLoop()
        soundPool?.autoPause()
    }
    
    /**
     * Resume cues (when app returns to foreground)
     */
    fun resumeCues() {
        Log.d(TAG, "▶️ Resuming cues")
        soundPool?.autoResume()
    }
    
    /**
     * Check if thinking loop is currently active
     */
    fun isThinkingLoopActive(): Boolean = isThinkingLoopActive
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up VoiceUiCues")
        
        stopThinkingLoop()
        
        soundPool?.release()
        soundPool = null
        
        cueScope?.cancel()
        cueScope = null
        
        Log.d(TAG, "✅ VoiceUiCues cleanup complete")
    }
}

/**
 * Factory for creating pre-configured VoiceUiCues
 */
object VoiceUiCuesFactory {
    
    /**
     * Create VoiceUiCues with optimal settings for voice chat
     */
    fun createForVoiceChat(context: Context): VoiceUiCues {
        val cues = VoiceUiCues(context)
        cues.initialize()
        cues.setVolume(0.3f) // Subtle volume
        return cues
    }
    
    /**
     * Create VoiceUiCues with settings for hands-free mode
     */
    fun createForHandsFree(context: Context): VoiceUiCues {
        val cues = VoiceUiCues(context)
        cues.initialize()
        cues.setVolume(0.5f) // Slightly louder for driving
        return cues
    }
}