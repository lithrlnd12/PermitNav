package com.clearwaycargo.ui.voice

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clearwaycargo.ai.ComplianceEngine
import com.clearwaycargo.data.StateDataRepository
import com.clearwaycargo.ui.chat.Renderer
import com.clearwaycargo.util.SafetyGate
import com.permitnav.ai.OpenAIService
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.Permit
import com.permitnav.data.repository.PermitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State machine for voice chat interactions
 */
enum class VoiceState {
    CONNECTING,   // Establishing WebRTC connection
    LISTENING,    // Waiting for user input
    PROCESSING,   // Processing user input (alias for THINKING)
    THINKING,     // Processing user input
    SPEAKING      // Playing AI response
}

/**
 * UI state for voice chat
 */
data class VoiceChatUiState(
    val voiceState: VoiceState = VoiceState.LISTENING,
    val micLevel: Float = 0.0f,
    val assistantLevel: Float = 0.0f,
    val transcript: String = "",
    val isConnected: Boolean = false,
    val error: String? = null,
    val currentPermit: Permit? = null,
    val isHandsFree: Boolean = false
)

/**
 * ViewModel for voice chat state machine and business logic
 * Coordinates between UI, SafetyGate, and voice services
 */
class VoiceChatViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "VoiceChatViewModel"
    }
    
    // Dependencies
    private val database = PermitNavDatabase.getDatabase(getApplication())
    private val permitRepository = PermitRepository(database.permitDao())
    private val openAIService = OpenAIService()
    private val safetyGate = SafetyGate(getApplication())
    
    // UI State
    private val _uiState = MutableStateFlow(VoiceChatUiState())
    val uiState: StateFlow<VoiceChatUiState> = _uiState.asStateFlow()
    
    init {
        // Monitor hands-free status
        viewModelScope.launch {
            safetyGate.isHandsFree.collect { isHandsFree ->
                _uiState.value = _uiState.value.copy(isHandsFree = isHandsFree)
                Log.d(TAG, "🚗 Hands-free mode: $isHandsFree")
            }
        }
        
        // Start safety monitoring
        safetyGate.startMonitoring()
    }
    
    /**
     * Initialize voice session with optional permit context
     */
    fun initializeSession(permitId: String? = null) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "🎤 Initializing voice session with permit: $permitId")
                
                // Load permit context if provided
                val permit = permitId?.let { id ->
                    permitRepository.getPermitById(id)
                }
                
                _uiState.value = _uiState.value.copy(
                    currentPermit = permit,
                    isConnected = false,
                    error = null
                )
                
                if (permit != null) {
                    Log.d(TAG, "📋 Loaded permit context: ${permit.permitNumber}")
                } else {
                    Log.d(TAG, "💬 Starting general voice chat session")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize session", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to initialize voice session: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Handle voice state transitions
     */
    fun onVoiceStateChange(newState: VoiceState) {
        val oldState = _uiState.value.voiceState
        
        if (oldState != newState) {
            Log.d(TAG, "🔄 Voice state: $oldState → $newState")
            
            _uiState.value = _uiState.value.copy(voiceState = newState)
            
            // Handle state-specific actions
            when (newState) {
                VoiceState.CONNECTING -> {
                    // Establishing connection
                    Log.d(TAG, "🔌 Connecting to voice service...")
                }
                VoiceState.LISTENING -> {
                    // Ready to receive user input
                    _uiState.value = _uiState.value.copy(transcript = "")
                }
                VoiceState.PROCESSING -> {
                    // Processing user input (alias for THINKING)
                    Log.d(TAG, "🤔 Processing user input...")
                }
                VoiceState.THINKING -> {
                    // Processing user input
                    Log.d(TAG, "🤔 Processing user input...")
                }
                VoiceState.SPEAKING -> {
                    // Playing AI response
                    Log.d(TAG, "🗣️ Playing AI response...")
                }
            }
        }
    }
    
    /**
     * Update microphone audio level
     */
    fun onMicLevelUpdate(level: Float) {
        _uiState.value = _uiState.value.copy(micLevel = level.coerceIn(0.0f, 1.0f))
    }
    
    /**
     * Update assistant audio level
     */
    fun onAssistantLevelUpdate(level: Float) {
        _uiState.value = _uiState.value.copy(assistantLevel = level.coerceIn(0.0f, 1.0f))
    }
    
    /**
     * Update transcript text
     */
    fun onTranscriptUpdate(transcript: String) {
        val truncated = if (transcript.length > 200) {
            transcript.take(197) + "..."
        } else {
            transcript
        }
        
        _uiState.value = _uiState.value.copy(transcript = truncated)
    }
    
    /**
     * Handle user utterance completion
     */
    fun onUserUtteranceComplete(transcript: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "🎯 User said: \"$transcript\"")
                
                onVoiceStateChange(VoiceState.THINKING)
                
                // Process the utterance
                val response = processUserUtterance(transcript)
                
                // Send response to voice service for TTS
                onResponseGenerated(response)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to process utterance", e)
                onVoiceStateChange(VoiceState.LISTENING)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to process your request: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Process user utterance and generate response
     */
    private suspend fun processUserUtterance(transcript: String): String {
        val currentPermit = _uiState.value.currentPermit
        
        return if (currentPermit != null && isComplianceQuestion(transcript)) {
            // Use compliance flow for permit-related questions
            Log.d(TAG, "🎯 Processing compliance question with permit context")
            processComplianceQuestion(currentPermit)
        } else {
            // Use general chat for other questions
            Log.d(TAG, "💬 Processing general question")
            processGeneralQuestion(transcript)
        }
    }
    
    /**
     * Process compliance question using the deterministic + AI summarizer flow
     */
    private suspend fun processComplianceQuestion(permit: Permit): String {
        return try {
            // Step 1: Load state data
            val stateData = StateDataRepository.load(permit.state)
            Log.d(TAG, "📊 Loaded state data for ${permit.state}")
            
            // Step 2: Run deterministic compliance check
            val complianceCore = ComplianceEngine.compare(permit, stateData.rulesJson)
            Log.d(TAG, "⚖️ Compliance analysis: ${complianceCore.verdict} (confidence: ${complianceCore.confidence})")
            
            // Step 3: Determine if we should include DOT contact
            val contact = if (Renderer.shouldIncludeDotContact(complianceCore)) {
                stateData.contact
            } else {
                null
            }
            
            // Step 4: Use AI to summarize results into natural language
            val summary = try {
                openAIService.summarizeCompliance(complianceCore, contact)
            } catch (e: Exception) {
                Log.w(TAG, "AI summarizer failed, using fallback", e)
                Renderer.createFallbackResponse(complianceCore, contact)
            }
            
            // Step 5: Convert to voice-optimized format
            Renderer.toVoiceLine(summary)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compliance processing failed", e)
            "I'm having trouble checking compliance right now. Please contact your dispatcher for assistance."
        }
    }
    
    /**
     * Process general question using OpenAI
     */
    private suspend fun processGeneralQuestion(transcript: String): String {
        return try {
            val response = openAIService.generalChat(transcript)
            Renderer.toVoiceLine(response)
        } catch (e: Exception) {
            Log.e(TAG, "❌ General chat failed", e)
            "I'm having trouble connecting right now. Please try again or contact dispatch for assistance."
        }
    }
    
    /**
     * Check if the utterance is asking about compliance
     */
    private fun isComplianceQuestion(transcript: String): Boolean {
        val complianceKeywords = listOf(
            "compliant", "compliance", "legal", "violation", "permit", "route", 
            "escort", "restriction", "regulation", "allowed", "check", "rules",
            "legal", "okay", "safe", "can i", "am i allowed"
        )
        val lowerTranscript = transcript.lowercase()
        return complianceKeywords.any { lowerTranscript.contains(it) }
    }
    
    /**
     * Handle generated response
     */
    private fun onResponseGenerated(response: String) {
        Log.d(TAG, "🗣️ Generated response: \"$response\"")
        
        // Transition to speaking state
        onVoiceStateChange(VoiceState.SPEAKING)
        
        // The VoiceSessionService should handle TTS playback
        // and call onTTSComplete when done
    }
    
    /**
     * Handle TTS completion
     */
    fun onTTSComplete() {
        Log.d(TAG, "✅ TTS playback completed")
        onVoiceStateChange(VoiceState.LISTENING)
    }
    
    /**
     * Handle barge-in (user interrupts AI)
     */
    fun onBargeIn() {
        Log.d(TAG, "✋ User barge-in detected")
        onVoiceStateChange(VoiceState.LISTENING)
    }
    
    /**
     * Handle connection status changes
     */
    fun onConnectionStatusChange(isConnected: Boolean) {
        _uiState.value = _uiState.value.copy(isConnected = isConnected)
        Log.d(TAG, "📡 Connection status: $isConnected")
    }
    
    /**
     * Handle errors
     */
    fun onError(error: String) {
        Log.e(TAG, "❌ Voice chat error: $error")
        _uiState.value = _uiState.value.copy(
            error = error,
            voiceState = VoiceState.LISTENING
        )
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Clean up resources
     */
    override fun onCleared() {
        super.onCleared()
        safetyGate.stopMonitoring()
        openAIService.close()
        Log.d(TAG, "🧹 VoiceChatViewModel cleaned up")
    }
    
    /**
     * Get welcome message for session start
     */
    fun getWelcomeMessage(): String {
        val permit = _uiState.value.currentPermit
        return if (permit != null) {
            "Hello! I'm ready to help with permit ${permit.permitNumber} from ${permit.state}. What would you like to know?"
        } else {
            "Hello! I'm your AI dispatch assistant. I can help with permit compliance, routing, and general trucking questions. What can I help you with?"
        }
    }
    
    /**
     * Force hands-free mode for testing
     */
    fun setHandsFreeOverride(handsFree: Boolean) {
        safetyGate.setHandsFreeOverride(handsFree)
    }
}