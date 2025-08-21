package com.clearwaycargo.ai

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.permitnav.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * WebRTC client for OpenAI Realtime API
 * Handles ephemeral token fetching, peer connection setup, and real-time audio streaming
 */
class RealtimeClient(
    private val context: Context,
    private val callbacks: RealtimeCallbacks
) {
    
    companion object {
        private const val TAG = "RealtimeClient"
        private const val SERVER_TOKEN_URL = "https://us-central1-permit-nav.cloudfunctions.net/pdfchat/api/realtime-token"
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val AUDIO_TRACK_ID = "audio_track"
    }
    
    interface RealtimeCallbacks {
        fun onTtsStart()
        fun onTtsEnd()
        fun onAssistantAudioLevel(level: Float)
        fun onUserPartialTranscript(text: String)
        fun onUserFinalTranscript(text: String)
        fun onError(error: Throwable)
        fun onConnectionStateChange(connected: Boolean)
    }
    
    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var dataChannel: DataChannel? = null
    // Audio device module will be created internally by PeerConnectionFactory
    
    // Session state
    private var isConnected = false
    private var ephemeralToken: String? = null
    private var sessionScope: CoroutineScope? = null
    private var currentPermitContext: String? = null
    
    // Audio routing
    private var audioManager: AudioManager? = null
    
    /**
     * Initialize WebRTC infrastructure
     */
    fun initialize() {
        Log.d(TAG, "🔧 Initializing WebRTC infrastructure")
        
        try {
            // Initialize audio manager for speaker routing
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Initialize PeerConnectionFactory
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            
            PeerConnectionFactory.initialize(initializationOptions)
            
            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()
            
            Log.d(TAG, "✅ WebRTC infrastructure initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize WebRTC", e)
            callbacks.onError(e)
        }
    }
    
    /**
     * Start a new realtime session
     */
    fun startSession() {
        if (sessionScope != null) {
            Log.w(TAG, "⚠️ Session already active, ignoring start request")
            return
        }
        
        sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        sessionScope?.launch {
            try {
                Log.d(TAG, "🚀 Starting realtime session")
                
                // Step 1: Get ephemeral token
                ephemeralToken = getEphemeralToken()
                Log.d(TAG, "🔑 Obtained ephemeral token")
                
                // Step 2: Setup peer connection
                setupPeerConnection()
                
                // Step 3: Set audio to speaker
                setAudioToSpeaker()
                
                // Step 4: Create offer and exchange SDP
                createOfferAndConnect()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start session", e)
                callbacks.onError(e)
            }
        }
    }
    
    /**
     * Stop the current session
     */
    fun stopSession() {
        Log.d(TAG, "⏹️ Stopping realtime session")
        
        isConnected = false
        callbacks.onConnectionStateChange(false)
        
        // Close peer connection
        peerConnection?.close()
        peerConnection = null
        
        // Stop local tracks
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null
        
        audioSource?.dispose()
        audioSource = null
        
        // Close data channel
        dataChannel?.close()
        dataChannel = null
        
        // Cancel coroutines
        sessionScope?.cancel()
        sessionScope = null
        
        ephemeralToken = null
        currentPermitContext = null
        
        // Restore audio routing
        restoreAudioRouting()
        
        Log.d(TAG, "✅ Session stopped")
    }
    
    /**
     * Update session configuration for better voice quality
     */
    fun updateSessionConfig() {
        Log.d(TAG, "⚙️ Updating session configuration for better voice quality")
        
        try {
            val sessionConfig = JSONObject().apply {
                put("voice", "verse")
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 200)
                })
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                    put("language", "en")  // Force English transcription
                })
                put("temperature", 0.8)
                put("max_response_output_tokens", 4096)
                
                // Add system instructions including permit context if available
                val systemInstructions = buildSystemInstructions()
                put("instructions", systemInstructions)
            }
            
            val updateEvent = JSONObject().apply {
                put("type", "session.update")
                put("session", sessionConfig)
            }
            
            sendDataChannelMessage(updateEvent.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update session config", e)
        }
    }
    
    /**
     * Set permit context for AI to reference
     * This mimics the text chat behavior of including permit details in system prompts
     */
    fun setPermitContext(permitContext: String) {
        Log.d(TAG, "📋 Setting permit context for AI system")
        currentPermitContext = permitContext
        
        // If already connected, update the session with new context
        if (isConnected) {
            updateSessionConfig()
        }
    }
    
    /**
     * Build system instructions including permit context if available
     */
    private fun buildSystemInstructions(): String {
        val baseInstructions = """
            You are an AI Dispatch Assistant for truck drivers specializing in oversize/overweight permit compliance.
            
            IMPORTANT: Always communicate in English. Never respond in Spanish or any other language.
            
            GREETING: When a conversation starts, immediately say "How may I help you?" in a friendly, professional tone.
            
            STOP COMMANDS: If you hear "stop dispatch", "stop", "cancel", "end session", or similar stop commands, DO NOT respond or acknowledge them. These commands are handled by the system to end our conversation.
            
            Your role:
            - Help drivers understand permit regulations
            - Provide escort requirements and restrictions  
            - Explain travel time and route limitations
            - Answer questions about permit compliance
            - Provide DOT contact information when needed
            
            Communication style:
            - Always respond in clear English
            - Be concise but thorough
            - Use trucker-friendly language
            - Prioritize safety and legal compliance
            - When uncertain, recommend contacting the state DOT
        """.trimIndent()
        
        return if (currentPermitContext != null) {
            """
            $baseInstructions
            
            CURRENT PERMIT CONTEXT:
            $currentPermitContext
            
            You now have access to the driver's specific permit details above. Use this information to provide accurate, permit-specific guidance. Reference the permit number, state, dimensions, and restrictions when relevant to the driver's questions.
            """.trimIndent()
        } else {
            baseInstructions
        }
    }
    
    /**
     * Force audio to speaker instead of headphones
     */
    private fun setAudioToSpeaker() {
        Log.d(TAG, "🔊 Setting audio output to speaker")
        
        try {
            audioManager?.let { am ->
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                Log.d(TAG, "✅ Audio routed to speaker")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to set audio to speaker", e)
        }
    }
    
    /**
     * Restore original audio routing
     */
    private fun restoreAudioRouting() {
        Log.d(TAG, "🔊 Restoring audio routing")
        
        try {
            audioManager?.let { am ->
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
                Log.d(TAG, "✅ Audio routing restored")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to restore audio routing", e)
        }
    }
    
    /**
     * Implement barge-in functionality via data channel
     */
    fun bargeIn() {
        Log.d(TAG, "✋ Barge-in triggered - interrupting OpenAI")
        
        try {
            // Send interruption event to OpenAI via data channel
            val bargeInEvent = JSONObject().apply {
                put("type", "response.cancel")
            }
            
            sendDataChannelMessage(bargeInEvent.toString())
            callbacks.onTtsEnd()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send barge-in event", e)
            // Fallback to local state change
            callbacks.onTtsEnd()
        }
    }
    
    /**
     * Send message via data channel to OpenAI
     */
    private fun sendDataChannelMessage(message: String) {
        try {
            val buffer = DataChannel.Buffer(
                java.nio.ByteBuffer.wrap(message.toByteArray(StandardCharsets.UTF_8)),
                false
            )
            
            val success = dataChannel?.send(buffer) ?: false
            if (success) {
                Log.d(TAG, "📤 Sent data channel message: ${message.take(100)}")
            } else {
                Log.w(TAG, "⚠️ Failed to send data channel message")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending data channel message", e)
            throw e
        }
    }
    
    /**
     * Get ephemeral token from your backend server
     */
    private suspend fun getEphemeralToken(): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔑 Requesting ephemeral token from server")
            
            val url = URL(SERVER_TOKEN_URL)
            val connection = url.openConnection() as HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = CONNECTION_TIMEOUT_MS.toInt()
            connection.readTimeout = CONNECTION_TIMEOUT_MS.toInt()
            
            // Empty request body - server handles everything
            val requestBody = "{}"
            
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(response)
                
                Log.d(TAG, "✅ Received ephemeral token from server")
                
                // Extract the ephemeral token from server response
                return@withContext responseJson.getJSONObject("client_secret")
                    .getString("value")
                
            } else {
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "HTTP $responseCode"
                throw IOException("Failed to get ephemeral token from server: $errorMessage")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get ephemeral token from server", e)
            throw e
        }
    }
    
    /**
     * Setup WebRTC peer connection with OpenAI
     */
    private fun setupPeerConnection() {
        Log.d(TAG, "🔌 Setting up peer connection")
        
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "📡 Signaling state: $state")
            }
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "🧊 ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        isConnected = true
                        callbacks.onConnectionStateChange(true)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        isConnected = false
                        callbacks.onConnectionStateChange(false)
                    }
                    else -> {
                        // Other states (NEW, CHECKING) don't change connection status
                    }
                }
            }
            
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "📡 ICE receiving: $receiving")
            }
            
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "🧊 ICE gathering: $state")
            }
            
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "🧊 ICE candidate: ${candidate?.sdp}")
                // In a full implementation, you'd send this to the signaling server
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "🧊 ICE candidates removed: ${candidates?.size}")
            }
            
            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "➕ Remote stream added")
                stream?.audioTracks?.firstOrNull()?.let { audioTrack ->
                    audioTrack.setEnabled(true)
                    // Monitor audio levels for assistant speech
                    monitorAssistantAudio(audioTrack)
                }
            }
            
            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "➖ Remote stream removed")
            }
            
            override fun onDataChannel(dataChannel: DataChannel?) {
                Log.d(TAG, "📊 Data channel: ${dataChannel?.label()}")
                // Handle transcription data if available
            }
            
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "🔄 Renegotiation needed")
            }
            
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "🎵 Track added: ${receiver?.track()?.kind()}")
            }
        }
        
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        
        // Add audio transceiver FIRST before setting up anything else
        val audioTransceiverInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        )
        peerConnection?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, audioTransceiverInit)
        Log.d(TAG, "🎵 Added audio transceiver during peer connection setup")
        
        // Setup local audio track
        setupLocalAudio()
        
        // Setup data channel for OpenAI events
        setupDataChannel()
    }
    
    /**
     * Setup local audio capture
     */
    private fun setupLocalAudio() {
        Log.d(TAG, "🎤 Setting up local audio")
        
        try {
            // Create audio source
            val mediaConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            
            audioSource = peerConnectionFactory?.createAudioSource(mediaConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)
            
            localAudioTrack?.setEnabled(true)
            
            // Add track to peer connection using addTrack (Unified Plan compatible)
            localAudioTrack?.let { track ->
                val streamIds = listOf("local_stream")
                peerConnection?.addTrack(track, streamIds)
            }
            
            Log.d(TAG, "✅ Local audio setup complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup local audio", e)
            callbacks.onError(e)
        }
    }
    
    /**
     * Setup data channel for OpenAI realtime events
     */
    private fun setupDataChannel() {
        Log.d(TAG, "📊 Setting up data channel for OpenAI events")
        
        try {
            val dataChannelInit = DataChannel.Init().apply {
                ordered = true
                maxRetransmits = -1
                maxRetransmitTimeMs = -1
                protocol = ""
                negotiated = false
                id = -1
            }
            
            dataChannel = peerConnection?.createDataChannel("oai-events", dataChannelInit)
            
            dataChannel?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(amount: Long) {
                    Log.v(TAG, "📊 Data channel buffered amount: $amount")
                }
                
                override fun onStateChange() {
                    val state = dataChannel?.state()
                    Log.d(TAG, "📊 Data channel state changed to: $state")
                    
                    if (state == DataChannel.State.OPEN) {
                        Log.d(TAG, "✅ Data channel is now OPEN - can receive transcripts")
                    }
                }
                
                override fun onMessage(buffer: DataChannel.Buffer) {
                    try {
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        val message = String(data, StandardCharsets.UTF_8)
                        Log.d(TAG, "📨 Received data channel message (${data.size} bytes)")
                        handleOpenAIEvent(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to parse data channel message", e)
                    }
                }
            })
            
            Log.d(TAG, "✅ Data channel setup complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup data channel", e)
            // Continue without data channel - audio will still work
        }
    }
    
    /**
     * Handle OpenAI realtime events from data channel
     */
    private fun handleOpenAIEvent(message: String) {
        try {
            val event = JSONObject(message)
            val type = event.optString("type", "")
            
            Log.d(TAG, "📥 OpenAI event: $type")
            Log.v(TAG, "📥 Full event: ${message.take(200)}")  // Debug: see full event
            
            when (type) {
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = event.getString("transcript")
                    Log.d(TAG, "🎤 FINAL transcript: '$transcript'")
                    callbacks.onUserFinalTranscript(transcript)
                }
                "conversation.item.input_audio_transcription.partial" -> {
                    val transcript = event.getString("transcript")
                    Log.d(TAG, "🎤 PARTIAL transcript: '$transcript'")
                    callbacks.onUserPartialTranscript(transcript)
                }
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "🎤 User started speaking")
                }
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "🎤 User stopped speaking")
                }
                "response.audio_transcript.started" -> {
                    Log.d(TAG, "🗣️ AI started speaking")
                    callbacks.onTtsStart()
                }
                "response.audio_transcript.completed" -> {
                    Log.d(TAG, "🗣️ AI finished speaking")
                    callbacks.onTtsEnd()
                }
                "error" -> {
                    val error = event.optString("message", "Unknown OpenAI error")
                    Log.e(TAG, "❌ OpenAI error: $error")
                    callbacks.onError(Exception("OpenAI error: $error"))
                }
                else -> {
                    Log.v(TAG, "📥 Unhandled OpenAI event: $type")
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle OpenAI event: $message", e)
        }
    }
    
    /**
     * Create SDP offer and connect to OpenAI
     */
    private suspend fun createOfferAndConnect() = withContext(Dispatchers.IO) {
        Log.d(TAG, "📝 Creating SDP offer")
        
        try {
            // Audio transceiver already added during peer connection setup
            Log.d(TAG, "🎵 Creating audio-only SDP offer")
            
            // Create audio-only offer
            val offerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            
            val offer = suspendCancellableCoroutine<SessionDescription> { continuation ->
                peerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp != null) {
                            // Test with audio-only first
                            Log.d(TAG, "✅ SDP offer created - testing audio-only")
                            continuation.resume(sdp)
                        } else {
                            continuation.resumeWithException(Exception("Failed to create offer"))
                        }
                    }
                    
                    override fun onCreateFailure(error: String?) {
                        continuation.resumeWithException(Exception("Create offer failed: $error"))
                    }
                    
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, offerConstraints)
                
                continuation.invokeOnCancellation {
                    Log.d(TAG, "Create offer cancelled")
                }
            }
            
            // Set local description with audio-only SDP
            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "✅ Set local description (audio-only) succeeded")
                        continuation.resume(Unit)
                    }
                    
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "❌ Set local description failed: $error")
                        continuation.resumeWithException(Exception("Set local description failed: $error"))
                    }
                    
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, offer)
                
                continuation.invokeOnCancellation {
                    Log.d(TAG, "Set local description cancelled")
                }
            }
            
            Log.d(TAG, "🤝 SDP offer created and set as local description")
            
            // Send SDP offer to OpenAI Realtime endpoint
            val answer = sendOfferToOpenAI(offer.description, ephemeralToken!!)
            
            // Set remote description (OpenAI's answer)
            val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answer)
            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        continuation.resume(Unit)
                        Log.d(TAG, "✅ Remote description set - WebRTC connection established")
                    }
                    
                    override fun onSetFailure(error: String?) {
                        continuation.resumeWithException(Exception("Set remote description failed: $error"))
                    }
                    
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, remoteDesc)
                
                continuation.invokeOnCancellation {
                    Log.d(TAG, "Set remote description cancelled")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create offer", e)
            callbacks.onError(e)
        }
    }
    
    /**
     * Send SDP offer to OpenAI Realtime endpoint and get answer
     */
    private suspend fun sendOfferToOpenAI(offer: String, token: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "📤 Sending SDP offer to OpenAI")
        
        val url = URL("https://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview")
        val connection = url.openConnection() as HttpURLConnection
        
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/sdp")
        connection.setRequestProperty("OpenAI-Beta", "realtime=v1")
        connection.doOutput = true
        connection.connectTimeout = CONNECTION_TIMEOUT_MS.toInt()
        connection.readTimeout = CONNECTION_TIMEOUT_MS.toInt()
        
        // Send the SDP offer
        connection.outputStream.use { output ->
            output.write(offer.toByteArray(StandardCharsets.UTF_8))
        }
        
        val responseCode = connection.responseCode
        // OpenAI returns 201 Created for successful WebRTC session creation
        if (responseCode in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED)) {
            val answer = connection.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "✅ Received SDP answer from OpenAI (HTTP $responseCode)")
            return@withContext answer
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                ?: "HTTP $responseCode"
            throw IOException("Failed to exchange SDP with OpenAI: $error")
        }
    }
    
    /**
     * Strip video m-lines from SDP to make it audio-only
     */
    private fun stripVideoFromSdp(sdp: String): String {
        val lines = sdp.lines()
        val out = StringBuilder()
        var skipVideo = false
        
        for (line in lines) {
            when {
                line.startsWith("m=video") -> {
                    skipVideo = true
                    continue
                }
                skipVideo && line.startsWith("m=") -> {
                    // End of video section, start of new media section
                    skipVideo = false
                }
                skipVideo -> {
                    // Skip all lines in video section
                    continue
                }
            }
            
            if (!skipVideo) {
                out.append(line).append("\r\n")
            }
        }
        
        // Clean up BUNDLE group to remove video references
        var result = out.toString()
        result = result.replace(Regex("a=group:BUNDLE \\d+ \\d+"), "a=group:BUNDLE 0")
        result = result.replace(Regex("a=mid:video\\d*\r?\n"), "")
        
        Log.d(TAG, "🎵 Stripped video from SDP, audio-only offer created")
        return result
    }
    
    /**
     * Monitor assistant audio levels
     */
    private fun monitorAssistantAudio(audioTrack: AudioTrack) {
        // In a real implementation, this would analyze the actual audio track
        // For now, audio levels come from OpenAI via data channel events
        Log.d(TAG, "🎵 Assistant audio track monitoring enabled")
    }
    
    
    /**
     * Check if client is connected
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up RealtimeClient")
        
        stopSession()
        
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        
        PeerConnectionFactory.shutdownInternalTracer()
    }
}