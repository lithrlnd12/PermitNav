package com.clearwaycargo.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Hotword detection engine for "Hey Clearway Dispatch"
 * 
 * This is a simplified implementation that detects voice activity and energy patterns.
 * In production, you would integrate with Porcupine Wake Word engine or similar.
 * 
 * For now, it uses basic voice activity detection and keyword spotting.
 */
class HotwordEngine(
    private val context: Context,
    private val onHotwordDetected: () -> Unit
) {
    
    companion object {
        private const val TAG = "HotwordEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
        
        // Voice activity detection thresholds
        private const val VAD_THRESHOLD = 0.02f
        private const val SPEECH_TIMEOUT_MS = 3000L
        private const val MIN_SPEECH_DURATION_MS = 500L
        
        // Hotword detection parameters  
        private const val HOTWORD_CONFIDENCE_THRESHOLD = 0.4f // Lower threshold for better sensitivity
        private const val SUPPRESSION_TIME_MS = 2000L // Prevent multiple triggers
    }
    
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var detectionJob: Job? = null
    private var lastTriggerTime = 0L
    
    // Audio processing
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
    private val audioBuffer = ShortArray(bufferSize / 2) // 16-bit samples
    
    /**
     * Start hotword listening
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "⚠️ Already listening for hotwords")
            return
        }
        
        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "❌ RECORD_AUDIO permission not granted")
            return
        }
        
        Log.d(TAG, "🎤 Starting hotword detection")
        
        try {
            // Initialize AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioRecord initialization failed")
                return
            }
            
            // Start recording
            audioRecord?.startRecording()
            isListening = true
            
            // Start detection coroutine
            detectionJob = CoroutineScope(Dispatchers.IO).launch {
                processAudioStream()
            }
            
            Log.d(TAG, "✅ Hotword detection started")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception starting hotword detection", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start hotword detection", e)
        }
    }
    
    /**
     * Stop hotword listening
     */
    fun stopListening() {
        if (!isListening) return
        
        Log.d(TAG, "🔇 Stopping hotword detection")
        
        isListening = false
        
        // Cancel detection job
        detectionJob?.cancel()
        detectionJob = null
        
        // Stop and release AudioRecord
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        
        Log.d(TAG, "✅ Hotword detection stopped")
    }
    
    /**
     * Main audio processing loop
     */
    private suspend fun processAudioStream() {
        Log.d(TAG, "🎧 Starting audio stream processing")
        
        val energyHistory = mutableListOf<Float>()
        val maxHistorySize = 50 // ~1.6 seconds at 16kHz with 512 sample buffer
        var speechStartTime = 0L
        var inSpeech = false
        
        while (isListening && currentCoroutineContext().isActive) {
            try {
                val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                
                if (bytesRead > 0) {
                    // Calculate audio energy
                    val energy = calculateAudioEnergy(audioBuffer, bytesRead)
                    
                    // Voice Activity Detection
                    if (energy > VAD_THRESHOLD) {
                        if (!inSpeech) {
                            speechStartTime = System.currentTimeMillis()
                            inSpeech = true
                            Log.v(TAG, "🗣️ Speech started")
                        }
                        
                        // Store energy for pattern analysis
                        energyHistory.add(energy)
                        if (energyHistory.size > maxHistorySize) {
                            energyHistory.removeFirst()
                        }
                        
                    } else if (inSpeech) {
                        // Check if speech has ended
                        val speechDuration = System.currentTimeMillis() - speechStartTime
                        
                        if (speechDuration > MIN_SPEECH_DURATION_MS) {
                            Log.v(TAG, "🗣️ Speech ended, analyzing for hotword")
                            analyzeForHotword(energyHistory)
                        }
                        
                        inSpeech = false
                        energyHistory.clear()
                    }
                    
                    // Timeout check for long speech
                    if (inSpeech && (System.currentTimeMillis() - speechStartTime) > SPEECH_TIMEOUT_MS) {
                        Log.v(TAG, "🗣️ Speech timeout, analyzing for hotword")
                        analyzeForHotword(energyHistory)
                        inSpeech = false
                        energyHistory.clear()
                    }
                }
                
                // Small delay to prevent excessive CPU usage
                delay(10)
                
            } catch (e: Exception) {
                if (isListening) {
                    Log.e(TAG, "❌ Error in audio processing", e)
                }
                break
            }
        }
        
        Log.d(TAG, "🏁 Audio stream processing stopped")
    }
    
    /**
     * Calculate RMS energy of audio buffer
     */
    private fun calculateAudioEnergy(buffer: ShortArray, length: Int): Float {
        if (length == 0) return 0f
        
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        
        val rms = sqrt(sum / length)
        return (rms / Short.MAX_VALUE).toFloat()
    }
    
    /**
     * Analyze energy pattern for hotword detection
     * This is a simplified implementation - in production use Porcupine or similar
     */
    private fun analyzeForHotword(energyHistory: List<Float>) {
        if (energyHistory.size < 10) return
        
        // Prevent multiple triggers too close together
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTriggerTime < SUPPRESSION_TIME_MS) {
            Log.v(TAG, "⏰ Suppressing trigger - too soon after last detection")
            return
        }
        
        // Simple pattern analysis for "Hey Clearway Dispatch"
        // Look for energy patterns that match the phrase structure
        val confidence = calculateHotwordConfidence(energyHistory)
        
        Log.v(TAG, "🎯 Hotword confidence: $confidence")
        
        if (confidence > HOTWORD_CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "🎉 Hotword detected! Confidence: $confidence")
            lastTriggerTime = currentTime
            onHotwordDetected()
        }
    }
    
    /**
     * Calculate confidence that the audio contains the hotword
     * Improved algorithm with better sensitivity for "Hey Clearway Dispatch"
     */
    private fun calculateHotwordConfidence(energyHistory: List<Float>): Float {
        if (energyHistory.isEmpty()) return 0f
        
        // Analyze speech patterns for "Hey Clearway Dispatch"
        val peaks = findEnergyPeaks(energyHistory)
        val avgEnergy = energyHistory.average().toFloat()
        val maxEnergy = energyHistory.maxOrNull() ?: 0f
        val duration = energyHistory.size
        
        var confidence = 0f
        
        // Base confidence for any speech activity
        if (avgEnergy > VAD_THRESHOLD) {
            confidence += 0.25f
        }
        
        // Syllable count: "Hey Clear-way Dis-patch" = 4-5 syllables
        when (peaks.size) {
            in 3..6 -> confidence += 0.35f  // Good syllable count
            in 2..7 -> confidence += 0.20f  // Acceptable range
            else -> confidence += 0.05f     // Some speech detected
        }
        
        // Duration analysis: 1.5-4 seconds is reasonable for the phrase
        when (duration) {
            in 20..80 -> confidence += 0.25f  // Optimal duration
            in 15..100 -> confidence += 0.15f // Acceptable duration
            else -> confidence += 0.05f       // Some duration
        }
        
        // Energy consistency - good speech should have varied but consistent energy
        val energyVariance = calculateEnergyVariance(energyHistory, avgEnergy)
        when {
            energyVariance in 0.1f..0.8f -> confidence += 0.15f  // Good variance
            energyVariance > 0.05f -> confidence += 0.10f        // Some variance
        }
        
        Log.v(TAG, "📊 Analysis - Peaks: ${peaks.size}, Duration: $duration, AvgEnergy: %.3f, Variance: %.3f".format(avgEnergy, energyVariance))
        
        return confidence.coerceAtMost(1.0f)
    }
    
    /**
     * Calculate energy variance for speech consistency analysis
     */
    private fun calculateEnergyVariance(energyHistory: List<Float>, avgEnergy: Float): Float {
        if (energyHistory.size < 2) return 0f
        
        val variance = energyHistory.map { (it - avgEnergy) * (it - avgEnergy) }.average()
        return sqrt(variance).toFloat()
    }
    
    /**
     * Find energy peaks in the signal - improved for syllable detection
     */
    private fun findEnergyPeaks(energyHistory: List<Float>): List<Int> {
        if (energyHistory.size < 3) return emptyList()
        
        val peaks = mutableListOf<Int>()
        val avgEnergy = energyHistory.average().toFloat()
        val threshold = avgEnergy * 1.2f  // Lower threshold for better sensitivity
        val minPeakDistance = 3  // Minimum samples between peaks to avoid noise
        
        for (i in 1 until energyHistory.size - 1) {
            val current = energyHistory[i]
            val prev = energyHistory[i - 1]
            val next = energyHistory[i + 1]
            
            // Local maximum above threshold
            if (current > prev && current > next && current > threshold) {
                // Check minimum distance from last peak
                if (peaks.isEmpty() || i - peaks.last() >= minPeakDistance) {
                    peaks.add(i)
                }
            }
        }
        
        return peaks
    }
    
    /**
     * Check if RECORD_AUDIO permission is granted
     */
    private fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopListening()
    }
}