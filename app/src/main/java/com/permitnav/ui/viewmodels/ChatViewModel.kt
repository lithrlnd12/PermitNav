package com.permitnav.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.permitnav.ai.ChatService
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.*
import com.permitnav.data.repository.PermitRepository
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
                
                // Prepare chat request
                val permitText = currentPermit?.let { chatService.formatPermitForChat(it) }
                    ?: "No specific permit loaded. General inquiry about $effectiveStateCode regulations."
                
                val chatRequest = ChatRequest(
                    stateKey = effectiveStateCode,
                    permitText = permitText,
                    userQuestion = message,
                    conversationId = conversationId
                )
                
                // Send to AI service
                val result = chatService.sendChatMessage(chatRequest)
                
                if (result.isSuccess) {
                    val response = result.getOrNull()!!
                    Log.d(TAG, "🤖 AI response received. Compliant: ${response.is_compliant}")
                    
                    // Format response message
                    val responseContent = formatComplianceResponse(response)
                    
                    val aiMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        content = responseContent,
                        isFromUser = false,
                        stateContext = effectiveStateCode,
                        messageType = MessageType.COMPLIANCE_RESULT
                    )
                    
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + aiMessage,
                        isLoading = false
                    )
                } else {
                    Log.e(TAG, "❌ Chat service error: ${result.exceptionOrNull()}")
                    handleChatError(result.exceptionOrNull())
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send message", e)
                handleChatError(e)
            }
        }
    }
    
    /**
     * Load available permits from database
     */
    private fun loadAvailablePermits() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "📋 Loading available permits")
                
                // Collect from Flow and filter for valid permits
                permitRepository.getValidPermits().collect { permits ->
                    _uiState.value = _uiState.value.copy(
                        availablePermits = permits
                    )
                    Log.d(TAG, "✅ Loaded ${permits.size} valid permits")
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