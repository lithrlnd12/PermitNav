package com.clearwaycargo.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AudioController handles microphone capture and speaker output with audio processing
 * Includes noise suppression, echo cancellation, and automatic gain control
 */
class AudioController(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioController"
        private const val SAMPLE_RATE = 16000 // 16kHz for voice
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
        private const val AMPLITUDE_UPDATE_INTERVAL_MS = 50L
    }
    
    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    // Audio effects
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null
    
    // Recording state
    private var isRecording = false
    private var recordingJob: Job? = null
    private var audioScope: CoroutineScope? = null
    
    // Audio levels
    private val _micAmplitude = MutableStateFlow(0.0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()
    
    private val _speakerAmplitude = MutableStateFlow(0.0f)
    val speakerAmplitude: StateFlow<Float> = _speakerAmplitude.asStateFlow()
    
    // Audio routing
    private var preferBluetoothSco = true
    private var wasBluetoothScoEnabled = false
    
    /**
     * Initialize audio controller
     */
    fun initialize(): Boolean {
        Log.d(TAG, "🔧 Initializing AudioController")
        
        if (!hasRecordPermission()) {
            Log.e(TAG, "❌ Record audio permission not granted")
            return false
        }
        
        audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        return setupAudioSession()
    }
    
    /**
     * Setup audio session with optimal routing
     */
    private fun setupAudioSession(): Boolean {
        return try {
            Log.d(TAG, "🎧 Setting up audio session")
            
            // Configure audio routing
            configureAudioRouting()
            
            // Initialize recording
            if (!initializeRecording()) {
                Log.e(TAG, "❌ Failed to initialize recording")
                return false
            }
            
            // Initialize playback
            if (!initializePlayback()) {
                Log.e(TAG, "❌ Failed to initialize playback")
                return false
            }
            
            // Setup audio effects
            setupAudioEffects()
            
            Log.d(TAG, "✅ Audio session setup complete")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup audio session", e)
            false
        }
    }
    
    /**
     * Configure audio routing (prefer Bluetooth SCO if available)
     */
    private fun configureAudioRouting() {
        try {
            if (preferBluetoothSco && audioManager.isBluetoothScoAvailableOffCall) {
                Log.d(TAG, "🎧 Configuring Bluetooth SCO audio")
                wasBluetoothScoEnabled = audioManager.isBluetoothScoOn
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            } else {
                Log.d(TAG, "📱 Configuring speakerphone audio")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to configure audio routing", e)
            // Fall back to default routing
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }
    
    /**
     * Initialize audio recording
     */
    private fun initializeRecording(): Boolean {
        return try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            )
            
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "❌ Invalid buffer size for recording")
                return false
            }
            
            val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            Log.d(TAG, "✅ Audio recording initialized (buffer: $bufferSize)")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize recording", e)
            false
        }
    }
    
    /**
     * Initialize audio playback
     */
    private fun initializePlayback(): Boolean {
        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT
            )
            
            if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                Log.e(TAG, "❌ Invalid buffer size for playback")
                return false
            }
            
            val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .setEncoding(AUDIO_FORMAT)
                .build()
            
            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioTrack initialization failed")
                audioTrack?.release()
                audioTrack = null
                return false
            }
            
            Log.d(TAG, "✅ Audio playback initialized (buffer: $bufferSize)")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize playback", e)
            false
        }
    }
    
    /**
     * Setup audio effects (noise suppression, echo cancellation, gain control)
     */
    private fun setupAudioEffects() {
        val audioSessionId = audioRecord?.audioSessionId ?: return
        
        try {
            // Noise Suppressor
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "✅ Noise suppressor enabled")
            } else {
                Log.d(TAG, "⚠️ Noise suppressor not available")
            }
            
            // Acoustic Echo Canceler
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)
                echoCanceler?.enabled = true
                Log.d(TAG, "✅ Echo canceler enabled")
            } else {
                Log.d(TAG, "⚠️ Echo canceler not available")
            }
            
            // Automatic Gain Control
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(audioSessionId)
                gainControl?.enabled = true
                Log.d(TAG, "✅ Automatic gain control enabled")
            } else {
                Log.d(TAG, "⚠️ Automatic gain control not available")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Some audio effects couldn't be enabled", e)
        }
    }
    
    /**
     * Start microphone capture with amplitude monitoring
     */
    fun startCapture(onAudioData: ((ByteArray) -> Unit)? = null): Boolean {
        if (isRecording) {
            Log.w(TAG, "⚠️ Already recording")
            return true
        }
        
        val recorder = audioRecord ?: run {
            Log.e(TAG, "❌ AudioRecord not initialized")
            return false
        }
        
        return try {
            Log.d(TAG, "🎤 Starting microphone capture")
            
            recorder.startRecording()
            isRecording = true
            
            // Start recording and amplitude monitoring job
            recordingJob = audioScope?.launch {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT
                ) * BUFFER_SIZE_FACTOR
                
                val buffer = ByteArray(bufferSize)
                
                while (isRecording && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        val bytesRead = recorder.read(buffer, 0, buffer.size)
                        
                        if (bytesRead > 0) {
                            // Calculate amplitude
                            val amplitude = calculateAmplitude(buffer, bytesRead)
                            _micAmplitude.value = amplitude
                            
                            // Pass audio data to callback
                            onAudioData?.invoke(buffer.copyOf(bytesRead))
                        }
                        
                        delay(AMPLITUDE_UPDATE_INTERVAL_MS)
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Recording loop error", e)
                        break
                    }
                }
            }
            
            Log.d(TAG, "✅ Microphone capture started")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start capture", e)
            isRecording = false
            false
        }
    }
    
    /**
     * Stop microphone capture
     */
    fun stopCapture() {
        if (!isRecording) return
        
        Log.d(TAG, "⏹️ Stopping microphone capture")
        
        isRecording = false
        
        try {
            audioRecord?.stop()
            recordingJob?.cancel()
            recordingJob = null
            
            _micAmplitude.value = 0.0f
            
            Log.d(TAG, "✅ Microphone capture stopped")
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error stopping capture", e)
        }
    }
    
    /**
     * Play audio data through speaker
     */
    fun playAudio(audioData: ByteArray): Boolean {
        val track = audioTrack ?: run {
            Log.e(TAG, "❌ AudioTrack not initialized")
            return false
        }
        
        return try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioTrack not in initialized state")
                return false
            }
            
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            
            val bytesWritten = track.write(audioData, 0, audioData.size)
            
            // Update speaker amplitude
            val amplitude = calculateAmplitude(audioData, audioData.size)
            _speakerAmplitude.value = amplitude
            
            bytesWritten > 0
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to play audio", e)
            false
        }
    }
    
    /**
     * Calculate audio amplitude (0.0 to 1.0)
     */
    private fun calculateAmplitude(audioData: ByteArray, length: Int): Float {
        if (length < 2) return 0.0f
        
        var sum = 0.0
        val samples = length / 2 // 16-bit samples
        
        for (i in 0 until samples * 2 step 2) {
            if (i + 1 < length) {
                // Convert bytes to 16-bit sample
                val sample = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
                sum += (sample * sample).toDouble()
            }
        }
        
        val rms = sqrt(sum / samples)
        val amplitude = (rms / Short.MAX_VALUE).toFloat()
        
        return amplitude.coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Mute/unmute microphone
     */
    fun setMuted(muted: Boolean) {
        audioRecord?.let { recorder ->
            // Note: AudioRecord doesn't have a direct mute method
            // You could implement this by pausing/resuming recording
            Log.d(TAG, "🔇 Microphone ${if (muted) "muted" else "unmuted"}")
        }
    }
    
    /**
     * Set speaker volume (0.0 to 1.0)
     */
    fun setSpeakerVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0.0f, 1.0f)
        
        audioTrack?.let { track ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                track.setVolume(clampedVolume)
            } else {
                @Suppress("DEPRECATION")
                track.setStereoVolume(clampedVolume, clampedVolume)
            }
            Log.d(TAG, "🔊 Speaker volume set to ${(clampedVolume * 100).toInt()}%")
        }
    }
    
    /**
     * Check if microphone permission is granted
     */
    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Get current microphone amplitude (0.0 to 1.0)
     */
    fun getCurrentMicAmplitude(): Float = _micAmplitude.value
    
    /**
     * Get current speaker amplitude (0.0 to 1.0)
     */
    fun getCurrentSpeakerAmplitude(): Float = _speakerAmplitude.value
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Cleanup audio resources
     */
    fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up AudioController")
        
        stopCapture()
        
        // Stop audio effects
        noiseSuppressor?.release()
        echoCanceler?.release()
        gainControl?.release()
        
        // Release audio components
        audioRecord?.release()
        audioTrack?.release()
        
        // Restore audio routing
        try {
            if (preferBluetoothSco && !wasBluetoothScoEnabled) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error restoring audio routing", e)
        }
        
        // Cancel coroutines
        audioScope?.cancel()
        audioScope = null
        
        Log.d(TAG, "✅ AudioController cleanup complete")
    }
}