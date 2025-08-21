package com.permitnav.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.permitnav.ai.ChatService
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.*
import com.permitnav.data.repository.PermitRepository
import com.clearwaycargo.data.StateDataRepository
import com.clearwaycargo.ai.ComplianceEngine
import com.permitnav.ai.OpenAIService
import com.clearwaycargo.ui.chat.Renderer
import com.clearwaycargo.util.SafetyGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.*

class ChatViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    private val chatService = ChatService()
    private val database = PermitNavDatabase.getDatabase(getApplication())
    private val permitRepository = PermitRepository(database.permitDao())
    
    // New compliance flow services
    private val openAIService = OpenAIService()
    private val safetyGate = SafetyGate(getApplication())
    
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    private var currentPermit: Permit? = null
    private val conversationId = UUID.randomUUID().toString()
    
    companion object {
        private const val TAG = "ChatViewModel"
    }
    
    init {
        loadAvailableStates()
        loadAvailablePermits()
        addWelcomeMessage()
    }
    
    /**
     * Load permit context for chat
     */
    fun loadPermitContext(permitId: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "🔍 Loading permit context: $permitId")
                
                val permit = permitRepository.getPermitById(permitId)
                if (permit != null) {
                    currentPermit = permit
                    _uiState.value = _uiState.value.copy(
                        hasPermitContext = true,
                        permitContext = chatService.formatPermitForChat(permit)
                    )
                    
                    // Add system message about loaded permit
                    val contextMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        content = "📋 Permit ${permit.permitNumber} loaded. I can now answer questions specific to this permit and ${permit.state} regulations.",
                        isFromUser = false,
                        messageType = MessageType.SYSTEM
                    )
                    
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + contextMessage
                    )
                    
                    Log.d(TAG, "✅ Permit context loaded: ${permit.permitNumber}")
                } else {
                    Log.w(TAG, "⚠️ Permit not found: $permitId")
                    _uiState.value = _uiState.value.copy(
                        error = "Permit not found"
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to load permit context", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load permit: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Send a message to the AI chat service
     */
    fun sendMessage(message: String, stateCode: String? = null) {
        viewModelScope.launch {
            try {
                // Use selected permit's state, or fallback to provided stateCode, or default to "IN"
                val effectiveStateCode = currentPermit?.state ?: stateCode ?: "IN"
                
                // Add user message
                val userMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    content = message,
                    isFromUser = true,
                    stateContext = effectiveStateCode
                )
                
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + userMessage,
                    isLoading = true,
                    error = null
                )
                
                Log.d(TAG, "💬 Sending message: $message")
                Log.d(TAG, "🏛️ State context: $effectiveStateCode")
                Log.d(TAG, "📋 Using permit: ${currentPermit?.permitNumber ?: "None"}")
                
                // NEW: Check if this is a compliance question and we have a permit
                if (currentPermit != null && isComplianceQuestion(message)) {
                    Log.d(TAG, "🎯 Detected compliance question with permit - using new compliance flow")
                    
                    // Use the new compliance flow
                    checkPermitComplianceForMessage(currentPermit!!)
                } else if (isComplianceQuestion(message)) {
                    Log.d(TAG, "🎯 Compliance question without permit - providing general guidance")
                    
                    // Compliance question but no permit loaded
                    val summary = try {
                        openAIService.generalChat("Question about $effectiveStateCode permit regulations: $message")
                    } catch (e: Exception) {
                        Log.w(TAG, "General compliance chat failed", e)
                        "For specific permit compliance questions, I need you to load a permit first. For general $effectiveStateCode regulations, please contact your state DOT office."
                    }
                    
                    val aiMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        content = summary,
                        isFromUser = false,
                        stateContext = effectiveStateCode,
                        messageType = MessageType.TEXT
                    )
                    
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + aiMessage,
                        isLoading = false
                    )
                } else {
                    Log.d(TAG, "💬 General chat question - using OpenAI for general response")
                    
                    // For general questions (not compliance-related)
                    val summary = try {
                        openAIService.generalChat(message)
                    } catch (e: Exception) {
                        Log.w(TAG, "General chat failed", e)
                        "I'm having trouble connecting right now. I'm here to help with permit compliance questions and general trucking assistance."
                    }
                    
                    val aiMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        content = summary,
                        isFromUser = false,
                        stateContext = effectiveStateCode,
                        messageType = MessageType.TEXT
                    )
                    
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + aiMessage,
                        isLoading = false
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send message", e)
                handleChatError(e)
            }
        }
    }
    
    /**
     * NEW COMPLIANCE FLOW: Check permit compliance using deterministic engine + AI summarizer
     * This replaces the old AI-only approach with a more reliable system
     */
    fun checkPermitCompliance(permitId: String? = null) {
        viewModelScope.launch {
            try {
                // Use current permit or load specified one
                val permit = if (permitId != null) {
                    permitRepository.getPermitById(permitId)
                } else {
                    currentPermit
                }
                
                if (permit == null) {
                    Log.w(TAG, "No permit available for compliance check")
                    addSystemMessage("Please select a permit first for compliance checking.")
                    return@launch
                }
                
                Log.d(TAG, "🔍 Starting new compliance flow for permit: ${permit.permitNumber}")
                
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                
                // Step 1: Load state data (rules + DOT contact)
                val stateData = StateDataRepository.load(permit.state)
                Log.d(TAG, "📊 Loaded state data for ${permit.state}")
                
                // Step 2: Run deterministic compliance check (Kotlin-only)
                val complianceCore = ComplianceEngine.compare(permit, stateData.rulesJson)
                Log.d(TAG, "⚖️ Compliance analysis: ${complianceCore.verdict} (${complianceCore.confidence})")
                
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
                
                Log.d(TAG, "📝 Generated summary: $summary")
                
                // Step 5: Create response based on hands-free mode
                val isHandsFree = safetyGate.isHandsFree()
                val response = if (isHandsFree) {
                    Renderer.toVoiceLine(summary)
                } else {
                    Renderer.toTextLine(summary)
                }
                
                // Step 6: Add message to chat
                val aiMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    content = response,
                    isFromUser = false,
                    stateContext = permit.state,
                    messageType = MessageType.COMPLIANCE_RESULT
                )
                
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + aiMessage,
                    isLoading = false
                )
                
                // Step 7: If hands-free, speak the response
                if (isHandsFree) {
                    // TODO: Implement TTS speaking
                    Log.d(TAG, "🔊 Would speak: $response")
                }
                
                Log.d(TAG, "✅ New compliance flow completed successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ New compliance flow failed", e)
                handleChatError(e)
            }
        }
    }
    
    /**
     * Check if message is asking about compliance
     */
    private fun isComplianceQuestion(message: String): Boolean {
        val complianceKeywords = listOf(
            "compliant", "compliance", "legal", "violation", "permit", "route", 
            "escort", "restriction", "regulation", "legal", "allowed", "check"
        )
        val lowerMessage = message.lowercase()
        return complianceKeywords.any { lowerMessage.contains(it) }
    }
    
    /**
     * Run compliance check specifically for a chat message
     */
    private suspend fun checkPermitComplianceForMessage(permit: Permit) {
        try {
            Log.d(TAG, "🔍 Running compliance check for chat message")
            
            // Step 1: Load state data (rules + DOT contact)
            val stateData = StateDataRepository.load(permit.state)
            Log.d(TAG, "📊 Loaded state data for ${permit.state}")
            
            // Step 2: Run deterministic compliance check (Kotlin-only)
            val complianceCore = ComplianceEngine.compare(permit, stateData.rulesJson)
            Log.d(TAG, "⚖️ Compliance analysis: ${complianceCore.verdict} (${complianceCore.confidence})")
            
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
            
            Log.d(TAG, "📝 Generated summary: $summary")
            
            // Step 5: Create response based on hands-free mode
            val isHandsFree = safetyGate.isHandsFree()
            val response = if (isHandsFree) {
                Renderer.toVoiceLine(summary)
            } else {
                Renderer.toTextLine(summary)
            }
            
            // Step 6: Add message to chat
            val aiMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                content = response,
                isFromUser = false,
                stateContext = permit.state,
                messageType = MessageType.COMPLIANCE_RESULT
            )
            
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + aiMessage,
                isLoading = false
            )
            
            // Step 7: If hands-free, speak the response
            if (isHandsFree) {
                Log.d(TAG, "🔊 Would speak: $response")
            }
            
            Log.d(TAG, "✅ Compliance check for chat completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compliance check for chat failed", e)
            handleChatError(e)
        }
    }
    
    /**
     * Add a system message to the chat
     */
    private fun addSystemMessage(message: String) {
        val systemMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            content = message,
            isFromUser = false,
            stateContext = currentPermit?.state ?: "SYSTEM",
            messageType = MessageType.SYSTEM
        )
        
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + systemMessage
        )
    }
    
    /**
     * Load available permits from database
     */
    private fun loadAvailablePermits() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "📋 Loading available permits")
                
                // Collect from Flow - get all permits (same as HomeScreen)
                permitRepository.getAllPermits().collect { permits ->
                    _uiState.value = _uiState.value.copy(
                        availablePermits = permits
                    )
                    Log.d(TAG, "✅ Loaded ${permits.size} permits (including unvalidated)")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading permits", e)
            }
        }
    }
    
    /**
     * Select a permit for chat context
     */
    fun selectPermit(permit: Permit?) {
        viewModelScope.launch {
            currentPermit = permit
            
            _uiState.value = _uiState.value.copy(
                selectedPermit = permit,
                hasPermitContext = permit != null,
                permitContext = permit?.let { chatService.formatPermitForChat(it) }
            )
            
            if (permit != null) {
                Log.d(TAG, "🔧 Selected permit: ${permit.permitNumber} (${permit.state})")
                
                // Add system message about selected permit
                val contextMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    content = "📋 Now discussing permit ${permit.permitNumber} from ${permit.state.uppercase()}. I'll provide state-specific compliance guidance based on this permit.",
                    isFromUser = false,
                    messageType = MessageType.SYSTEM
                )
                
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + contextMessage
                )
            } else {
                Log.d(TAG, "🔧 Cleared permit selection")
                
                val contextMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    content = "📋 Permit selection cleared. I'll now provide general trucking regulation guidance.",
                    isFromUser = false,
                    messageType = MessageType.SYSTEM
                )
                
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + contextMessage
                )
            }
        }
    }
    
    /**
     * Load available states from the backend
     */
    private fun loadAvailableStates() {
        viewModelScope.launch {
            try {
                val result = chatService.getAvailableStates()
                if (result.isSuccess) {
                    val states = result.getOrNull() ?: emptyList()
                    _uiState.value = _uiState.value.copy(
                        availableStates = states
                    )
                    Log.d(TAG, "✅ Loaded states: $states")
                } else {
                    Log.w(TAG, "⚠️ Failed to load states, using fallback")
                    // Use fallback states
                    _uiState.value = _uiState.value.copy(
                        availableStates = listOf("IN", "IL", "OH", "MI", "KY", "DE", "PA", "TX", "CA", "FL")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading states", e)
            }
        }
    }
    
    /**
     * Add welcome message
     */
    private fun addWelcomeMessage() {
        val welcomeMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            content = """
                👋 Hello! I'm your AI Dispatch Assistant.
                
                I can help you with:
                • State permit regulations and requirements
                • Escort requirements and restrictions  
                • Travel time and route restrictions
                • Compliance checking for your permits
                • DOT contact information
                
                Ask me anything about trucking regulations!
            """.trimIndent(),
            isFromUser = false,
            messageType = MessageType.SYSTEM
        )
        
        _uiState.value = _uiState.value.copy(
            messages = listOf(welcomeMessage)
        )
    }
    
    /**
     * Format compliance response for display
     */
    private fun formatComplianceResponse(response: ComplianceResponse): String {
        return buildString {
            // Compliance status
            if (response.is_compliant) {
                append("✅ **COMPLIANT**\n\n")
            } else {
                append("❌ **NOT COMPLIANT**\n\n")
            }
            
            // Violations
            if (response.violations.isNotEmpty()) {
                append("🚫 **Violations:**\n")
                response.violations.forEach { violation ->
                    append("• $violation\n")
                }
                append("\n")
            }
            
            // Escorts
            if (response.escorts.isNotBlank()) {
                append("🚗 **Escort Requirements:**\n")
                append("${response.escorts}\n\n")
            }
            
            // Travel restrictions
            if (response.travel_restrictions.isNotBlank()) {
                append("⏰ **Travel Restrictions:**\n")
                append("${response.travel_restrictions}\n\n")
            }
            
            // Notes
            if (response.notes.isNotBlank()) {
                append("📝 **Additional Notes:**\n")
                append("${response.notes}\n\n")
            }
            
            // Contact info
            response.contact_info?.let { contact ->
                append("📞 **DOT Contact:**\n")
                append("${contact.department}\n")
                append("Phone: ${contact.phone}\n")
                contact.email?.let { append("Email: $it\n") }
                contact.website?.let { append("Website: $it\n") }
                contact.office_hours?.let { append("Hours: $it\n") }
            }
        }
    }
    
    /**
     * Handle chat errors
     */
    private fun handleChatError(error: Throwable?) {
        val errorMessage = when {
            error?.message?.contains("network", ignoreCase = true) == true -> 
                "Network error. Please check your connection and try again."
            error?.message?.contains("unauthorized", ignoreCase = true) == true -> 
                "Chat service unavailable. Please try again later."
            else -> 
                "Failed to get response. Please try again or contact dispatch."
        }
        
        val aiMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            content = "⚠️ $errorMessage\n\nFor immediate assistance, please contact your local DOT office or dispatch.",
            isFromUser = false,
            messageType = MessageType.ERROR
        )
        
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + aiMessage,
            isLoading = false,
            error = null // Clear error since we've handled it with a message
        )
    }
    
    /**
     * Clear the chat conversation
     */
    fun clearChat() {
        _uiState.value = _uiState.value.copy(
            messages = emptyList(),
            error = null
        )
        addWelcomeMessage()
        Log.d(TAG, "🧹 Chat cleared")
    }
    
    /**
     * Clear any displayed error
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    override fun onCleared() {
        super.onCleared()
        chatService.close()
        openAIService.close()
        safetyGate.stopMonitoring()
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasPermitContext: Boolean = false,
    val permitContext: String? = null,
    val availableStates: List<String> = emptyList(),
    val availablePermits: List<Permit> = emptyList(),
    val selectedPermit: Permit? = null
)