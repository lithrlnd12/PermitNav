package com.permitnav.ai

import android.util.Log
import com.permitnav.BuildConfig
import com.permitnav.data.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.InternalSerializationApi

/**
 * Service for communicating with the AI chat backend
 * Handles state regulation queries and compliance checking
 */
@OptIn(InternalSerializationApi::class)
class ChatService {
    
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }
    
    companion object {
        private const val TAG = "ChatService"
        // PDF-powered backend endpoints (Cloud Functions)
        private const val BACKEND_URL = "https://us-central1-permit-nav.cloudfunctions.net/pdfchat"
        private const val CHAT_ENDPOINT = "$BACKEND_URL/api/chat"
        private const val STATES_ENDPOINT = "$BACKEND_URL/api/states"
        private const val HEALTH_ENDPOINT = "$BACKEND_URL/health"
    }
    
    @Serializable
    data class ChatApiRequest(
        val stateKey: String,
        val permitText: String,
        val userQuestion: String,
        val conversationId: String? = null
    )
    
    @Serializable
    data class ChatApiResponse(
        val is_compliant: Boolean,
        val violations: List<String>,
        val escorts: String,
        val travel_restrictions: String,
        val notes: String,
        val contact_info: ContactInfoApi? = null,
        val confidence: Double? = null,
        val sources: List<String>? = null,
        val state: String? = null,
        val permitNumber: String? = null
    )
    
    @Serializable
    data class ContactInfoApi(
        val department: String,
        val phone: String,
        val email: String? = null,
        val website: String? = null,
        val office_hours: String? = null
    )
    
    /**
     * Send a chat message - tries PDF backend first, falls back to direct OpenAI
     */
    suspend fun sendChatMessage(request: ChatRequest): Result<ComplianceResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🤖 Starting chat request")
            Log.d(TAG, "📍 State: ${request.stateKey}")
            Log.d(TAG, "❓ Question: ${request.userQuestion}")
            Log.d(TAG, "📋 Has permit context: ${request.permitText.contains("PERMIT")}")
            
            // First try PDF-powered backend if we have permit context
            if (request.permitText.contains("PERMIT", ignoreCase = true)) {
                Log.d(TAG, "🔍 Trying PDF-powered backend for permit-specific question")
                
                val backendResult = tryPDFBackend(request)
                if (backendResult.isSuccess) {
                    Log.d(TAG, "✅ PDF backend successful")
                    return@withContext backendResult
                } else {
                    Log.w(TAG, "⚠️ PDF backend failed, falling back to OpenAI: ${backendResult.exceptionOrNull()?.message}")
                }
            } else {
                Log.d(TAG, "📝 General question - using direct OpenAI")
            }
            
            // Fallback to direct OpenAI for general questions or if backend fails
            return@withContext callOpenAIDirect(request)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chat service error: ${e.javaClass.simpleName}")
            Log.e(TAG, "❌ Error message: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Try the PDF-powered backend
     */
    private suspend fun tryPDFBackend(request: ChatRequest): Result<ComplianceResponse> {
        return try {
            val backendRequest = ChatApiRequest(
                stateKey = request.stateKey,
                permitText = request.permitText,
                userQuestion = request.userQuestion,
                conversationId = request.conversationId
            )
            
            val response = client.post(CHAT_ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(backendRequest)
            }
            
            if (!response.status.isSuccess()) {
                throw Exception("Backend error: ${response.status}")
            }
            
            val backendResponse = response.body<ChatApiResponse>()
            
            Result.success(ComplianceResponse(
                is_compliant = backendResponse.is_compliant,
                violations = backendResponse.violations,
                escorts = backendResponse.escorts,
                travel_restrictions = backendResponse.travel_restrictions,
                notes = backendResponse.notes,
                contact_info = backendResponse.contact_info?.let { contact ->
                    ContactInfo(
                        department = contact.department,
                        phone = contact.phone,
                        email = contact.email,
                        website = contact.website,
                        office_hours = contact.office_hours
                    )
                }
            ))
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Call OpenAI directly with general trucking knowledge
     */
    private suspend fun callOpenAIDirect(request: ChatRequest): Result<ComplianceResponse> {
        return try {
            Log.d(TAG, "🌐 Calling OpenAI directly")
            Log.d(TAG, "🔑 API Key available: ${BuildConfig.OPENAI_API_KEY.isNotBlank()}")
            
            if (BuildConfig.OPENAI_API_KEY.isBlank()) {
                throw Exception("OpenAI API key not configured")
            }
            
            val systemPrompt = """You are a professional trucking permit compliance assistant for ${request.stateKey.uppercase()} state.
                
                Provide responses in JSON format with these keys:
                - is_compliant (boolean): Conservative estimate of compliance
                - violations (array): Potential violations or concerns
                - escorts (string): Escort requirements information
                - travel_restrictions (string): Time and route restrictions
                - notes (string): Additional guidance and recommendations
                
                Use your knowledge of trucking regulations, but be conservative and recommend contacting state DOT for official guidance when uncertain."""
            
            val userPrompt = """
                STATE: ${request.stateKey.uppercase()}
                CONTEXT: ${request.permitText}
                QUESTION: ${request.userQuestion}
                
                Provide compliance guidance based on general trucking knowledge.
            """.trimIndent()
            
            val openAIRequest = """
            {
              "model": "gpt-4o-mini",
              "messages": [
                {"role": "system", "content": ${org.json.JSONObject.quote(systemPrompt)}},
                {"role": "user", "content": ${org.json.JSONObject.quote(userPrompt)}}
              ],
              "temperature": 0.1,
              "response_format": {"type": "json_object"}
            }
            """.trimIndent()
            
            val openAIResponse = client.post("https://api.openai.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(openAIRequest)
                header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            }
            
            if (!openAIResponse.status.isSuccess()) {
                val errorBody = try { openAIResponse.body<String>() } catch (e: Exception) { "Could not read error" }
                throw Exception("OpenAI API error: ${openAIResponse.status} - $errorBody")
            }
            
            val responseBody = openAIResponse.body<String>()
            val responseJson = org.json.JSONObject(responseBody as String)
            val choices = responseJson.getJSONArray("choices")
            val messageContent = choices.getJSONObject(0).getJSONObject("message").getString("content")
            val resultJson = org.json.JSONObject(messageContent as String)
            
            Log.d(TAG, "✅ OpenAI direct response received")
            
            Result.success(ComplianceResponse(
                is_compliant = resultJson.optBoolean("is_compliant", false),
                violations = jsonArrayToList(resultJson.optJSONArray("violations")),
                escorts = resultJson.optString("escorts", "Contact state DOT for escort requirements"),
                travel_restrictions = resultJson.optString("travel_restrictions", "Check state regulations for travel restrictions"),
                notes = resultJson.optString("notes", "") + "\n\n⚠️ This is general guidance. Contact your state DOT for official permit requirements.",
                contact_info = getDefaultContactInfo(request.stateKey)
            ))
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ OpenAI direct call failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Get default contact info for a state
     */
    private fun getDefaultContactInfo(state: String): ContactInfo {
        return ContactInfo(
            department = "${state.uppercase()} Department of Transportation",
            phone = "Contact state DOT for current phone number",
            email = null,
            website = "Search for '${state.uppercase()} DOT permit office'",
            office_hours = "Standard business hours"
        )
    }
    
    private fun jsonArrayToList(jsonArray: org.json.JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }
    
    /**
     * Get available states from the PDF-powered backend
     */
    suspend fun getAvailableStates(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🗺️ Fetching available states from PDF backend")
            
            val response = client.get(STATES_ENDPOINT)
            
            if (!response.status.isSuccess()) {
                Log.e(TAG, "❌ PDF backend states error: ${response.status}")
                return@withContext Result.failure(Exception("Failed to fetch states from PDF backend: ${response.status}"))
            }
            
            val states = response.body<List<String>>()
            Log.d(TAG, "✅ PDF backend available states: ${states.size} states")
            
            Result.success(states)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch states from PDF backend", e)
            // Fallback to all supported states
            val fallbackStates = listOf(
                "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
                "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
                "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
                "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
                "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY"
            )
            Log.d(TAG, "🔄 Using fallback states: ${fallbackStates.size} states")
            Result.success(fallbackStates)
        }
    }
    
    /**
     * Check if PDF-powered backend is healthy and available
     */
    suspend fun checkBackendHealth(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🏥 Checking PDF backend health")
            
            val response = client.get(HEALTH_ENDPOINT)
            
            if (response.status.isSuccess()) {
                val healthData = response.body<Map<String, Any>>()
                Log.d(TAG, "✅ PDF backend healthy: ${healthData}")
                Result.success(true)
            } else {
                Log.e(TAG, "❌ PDF backend unhealthy: ${response.status}")
                Result.success(false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ PDF backend health check failed", e)
            Result.success(false)
        }
    }
    
    /**
     * Test the PDF-powered chat service with a simple query
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🧪 Testing PDF-powered chat connection")
            
            // First check if backend is healthy
            val healthCheck = checkBackendHealth()
            if (!healthCheck.getOrDefault(false)) {
                Log.e(TAG, "❌ Backend health check failed")
                return@withContext Result.failure(Exception("PDF backend is not healthy"))
            }
            
            // Test with a simple compliance question
            val testRequest = ChatRequest(
                stateKey = "IN",
                permitText = "Test permit: Width 12ft, Height 14ft, Weight 85000lbs, Indiana DOT permit",
                userQuestion = "Do I need escorts for this load in Indiana?"
            )
            
            val result = sendChatMessage(testRequest)
            if (result.isSuccess) {
                Log.d(TAG, "✅ PDF chat test successful")
                val response = result.getOrNull()
                Log.d(TAG, "📊 Test response compliance: ${response?.is_compliant}")
                Log.d(TAG, "🚗 Test response escorts: ${response?.escorts}")
            } else {
                Log.e(TAG, "❌ PDF chat test failed: ${result.exceptionOrNull()}")
            }
            
            Result.success(result.isSuccess)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ PDF connection test failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Format permit data for chat context
     */
    fun formatPermitForChat(permit: Permit): String {
        return buildString {
            append("PERMIT DETAILS:\n")
            append("Permit Number: ${permit.permitNumber}\n")
            append("State: ${permit.state}\n")
            append("Issue Date: ${permit.issueDate}\n")
            append("Expiration Date: ${permit.expirationDate}\n")
            append("\nDIMENSIONS:\n")
            permit.dimensions.let { dim ->
                dim.length?.let { append("Length: ${it}ft\n") }
                dim.width?.let { append("Width: ${it}ft\n") }
                dim.height?.let { append("Height: ${it}ft\n") }
                dim.weight?.let { append("Weight: ${it}lbs\n") }
                dim.axles?.let { append("Axles: $it\n") }
            }
            append("\nVEHICLE INFO:\n")
            append("License Plate: ${permit.vehicleInfo.licensePlate}\n")
            append("Make/Model: ${permit.vehicleInfo.make} ${permit.vehicleInfo.model}\n")
            
            if (permit.restrictions.isNotEmpty()) {
                append("\nRESTRICTIONS:\n")
                permit.restrictions.forEach { restriction ->
                    append("- $restriction\n")
                }
            }
            
            if (!permit.routeDescription.isNullOrBlank()) {
                append("\nROUTE: ${permit.routeDescription}\n")
            }
        }
    }
    
    fun close() {
        client.close()
    }
}