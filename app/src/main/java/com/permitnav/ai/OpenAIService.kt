package com.permitnav.ai

import com.permitnav.BuildConfig
import com.permitnav.data.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import com.clearwaycargo.ai.ComplianceCore
import com.clearwaycargo.ai.Prompts
import com.clearwaycargo.data.DotContact

class OpenAIService {
    
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json()
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        defaultRequest {
            header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY ?: ""}")
            contentType(ContentType.Application.Json)
        }
    }
    
    private val baseUrl = "https://api.openai.com/v1"
    
    /**
     * Extract permit fields from OCR text (Step 1 of 2-call flow)
     */
    suspend fun extractPermitFields(
        ocrTexts: List<String>,
        stateCode: String,
        stateRulesJson: String? = null,
        routeJson: String? = null
    ): ExtractionResult = withContext(Dispatchers.IO) {
        
        val combinedText = ocrTexts.joinToString("\n\n--- PAGE BREAK ---\n\n")
        val stateName = getStateName(stateCode)
        
        // This method is deprecated - using old prompts for backward compatibility
        val userPrompt = """CONTEXT:
{
  "state": "$stateName",
  "permit_text": ${JSONObject.quote(combinedText)},
  "state_rules": ${stateRulesJson ?: "null"},
  "route": ${routeJson ?: "null"}
}

OUTPUT: Fill the EXTRACTION OUTPUT schema exactly. Unknown fields → null. When text conflicts or key fields are missing, set needs_human=true and reduce confidence."""
        
        makeApiCallWithRetry(
            systemPrompt = "You are a calm dispatcher assistant for Clearway Cargo's OSOW permits. Extract facts strictly from provided context. If unknown, return null. Do not guess. Return valid JSON only.",
            userPrompt = userPrompt,
            maxTokens = 1200,
            operation = "extraction"
        ) { responseJson ->
            parseExtractionResult(responseJson)
        }
    }
    
    /**
     * Analyze compliance based on extracted fields (Step 2 of 2-call flow)
     */
    suspend fun reasonCompliance(
        extracted: ExtractionResult,
        stateRulesJson: String? = null
    ): ComplianceResult = withContext(Dispatchers.IO) {
        
        val extractedJson = serializeExtractionResult(extracted)
        val userPrompt = """CONTEXT:
{
  "extracted": $extractedJson,
  "state_rules": ${stateRulesJson ?: "null"}
}

OUTPUT: Fill the COMPLIANCE OUTPUT schema exactly. Avoid speculation. If bridges/routes/time limits are unspecified in rules/context, flag uncertainty."""
        
        makeApiCallWithRetry(
            systemPrompt = "You determine OSOW compliance using extracted fields and optional state rules. Use context only. If a needed rule is missing, state the uncertainty, set needs_human=true. Return valid JSON only.",
            userPrompt = userPrompt,
            maxTokens = 1500,
            operation = "compliance"
        ) { responseJson ->
            parseComplianceResult(responseJson)
        }
    }
    
    /**
     * Make API call with retry logic for 429/5xx errors
     */
    private suspend fun <T> makeApiCallWithRetry(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        operation: String,
        parser: (JSONObject) -> T
    ): T {
        var lastException: Exception? = null
        
        for (attempt in 1..2) {
            try {
                val requestBody = """
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": ${JSONObject.quote(systemPrompt)}},
    {"role": "user", "content": ${JSONObject.quote(userPrompt)}}
  ],
  "temperature": 0.0,
  "max_tokens": $maxTokens,
  "response_format": {"type": "json_object"}
}
                """.trimIndent()
                
                android.util.Log.d("OpenAIService", "Making $operation request (attempt $attempt)")
                
                val response = client.post("$baseUrl/chat/completions") {
                    setBody(requestBody)
                    header("Content-Type", "application/json")
                }
                
                // Check for rate limit or server errors
                if (response.status.value == 429 || response.status.value >= 500) {
                    if (attempt < 2) {
                        val delay = if (response.status.value == 429) 500L else 250L
                        android.util.Log.w("OpenAIService", "Retrying after ${response.status} (delay: ${delay}ms)")
                        delay(delay)
                        continue
                    }
                }
                
                if (response.status.value >= 400) {
                    val errorBody = response.bodyAsText()
                    android.util.Log.e("OpenAIService", "API Error: $errorBody")
                    throw Exception("OpenAI API error: ${response.status}")
                }
                
                val responseBody = response.bodyAsText()
                android.util.Log.d("OpenAIService", "$operation response received (${responseBody.length} chars)")
                
                val responseJson = JSONObject(responseBody)
                val choices = responseJson.getJSONArray("choices")
                
                if (choices.length() == 0) {
                    throw Exception("No response from AI")
                }
                
                val messageContent = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                
                val resultJson = JSONObject(messageContent)
                return parser(resultJson)
                
            } catch (e: Exception) {
                lastException = e
                android.util.Log.e("OpenAIService", "$operation attempt $attempt failed", e)
                
                if (attempt < 2) {
                    delay(250L) // Brief delay before retry
                } else {
                    break
                }
            }
        }
        
        // If we get here, all attempts failed
        android.util.Log.e("OpenAIService", "$operation failed after all retries", lastException)
        throw lastException ?: Exception("$operation failed after retries")
    }
    
    /**
     * Parse extraction API response into ExtractionResult
     */
    private fun parseExtractionResult(json: JSONObject): ExtractionResult {
        val permitInfo = json.optJSONObject("permitInfo")
        val vehicleInfo = json.optJSONObject("vehicleInfo") 
        val dimensions = json.optJSONObject("dimensions")
        
        return ExtractionResult(
            permitInfo = PermitInfo(
                state = permitInfo?.optString("state"),
                permitNumber = permitInfo?.optString("permitNumber"),
                issueDate = permitInfo?.optString("issueDate"),
                expirationDate = permitInfo?.optString("expirationDate"),
                permitType = permitInfo?.optString("permitType")
            ),
            vehicleInfo = AiVehicleInfo(
                licensePlate = vehicleInfo?.optString("licensePlate"),
                vin = vehicleInfo?.optString("vin"),
                carrierName = vehicleInfo?.optString("carrierName"),
                usdot = vehicleInfo?.optString("usdot")
            ),
            dimensions = Dimensions(
                length = dimensions?.optDouble("length")?.takeIf { !it.isNaN() },
                width = dimensions?.optDouble("width")?.takeIf { !it.isNaN() },
                height = dimensions?.optDouble("height")?.takeIf { !it.isNaN() },
                weight = dimensions?.optDouble("weight")?.takeIf { !it.isNaN() },
                axles = dimensions?.optInt("axles")?.takeIf { it > 0 }
            ),
            complianceInputsFound = jsonArrayToList(json.optJSONArray("complianceInputsFound")),
            confidence = json.optDouble("confidence", 0.0).clamp01(),
            needs_human = json.optBoolean("needs_human", false),
            sources_used = jsonArrayToList(json.optJSONArray("sources_used"))
        )
    }
    
    /**
     * Parse compliance API response into ComplianceResult
     */
    private fun parseComplianceResult(json: JSONObject): ComplianceResult {
        return ComplianceResult(
            isCompliant = if (json.has("isCompliant")) json.optBoolean("isCompliant") else null,
            violations = jsonArrayToList(json.optJSONArray("violations")),
            warnings = jsonArrayToList(json.optJSONArray("warnings")),
            recommendations = jsonArrayToList(json.optJSONArray("recommendations")),
            requiredActions = jsonArrayToList(json.optJSONArray("requiredActions")),
            stateSpecificNotes = jsonArrayToList(json.optJSONArray("stateSpecificNotes")),
            confidence = json.optDouble("confidence", 0.0).clamp01(),
            needs_human = json.optBoolean("needs_human", false),
            sources_used = jsonArrayToList(json.optJSONArray("sources_used"))
        )
    }
    
    /**
     * Serialize ExtractionResult back to JSON for compliance analysis
     */
    private fun serializeExtractionResult(extracted: ExtractionResult): String {
        return JSONObject().apply {
            put("permitInfo", JSONObject().apply {
                put("state", extracted.permitInfo.state)
                put("permitNumber", extracted.permitInfo.permitNumber)
                put("issueDate", extracted.permitInfo.issueDate)
                put("expirationDate", extracted.permitInfo.expirationDate)
                put("permitType", extracted.permitInfo.permitType)
            })
            put("vehicleInfo", JSONObject().apply {
                put("licensePlate", extracted.vehicleInfo.licensePlate)
                put("vin", extracted.vehicleInfo.vin)
                put("carrierName", extracted.vehicleInfo.carrierName)
                put("usdot", extracted.vehicleInfo.usdot)
            })
            put("dimensions", JSONObject().apply {
                put("length", extracted.dimensions.length)
                put("width", extracted.dimensions.width)
                put("height", extracted.dimensions.height)
                put("weight", extracted.dimensions.weight)
                put("axles", extracted.dimensions.axles)
            })
            put("complianceInputsFound", JSONArray(extracted.complianceInputsFound))
            put("confidence", extracted.confidence)
            put("needs_human", extracted.needs_human)
            put("sources_used", JSONArray(extracted.sources_used))
        }.toString()
    }
    
    /**
     * National permit analysis using OpenAI - works with any US state
     * This replaces the need for individual state JSON files
     * 
     * @deprecated Use extractPermitFields + reasonCompliance for better reliability
     */
    suspend fun analyzePermitNational(
        ocrTexts: List<String>,
        state: String,
        origin: String? = null,
        destination: String? = null
    ): NationalPermitAnalysis = withContext(Dispatchers.IO) {
        
        val combinedText = ocrTexts.joinToString("\n\n--- PAGE BREAK ---\n\n")
        val stateName = getStateName(state)
        
        val systemPrompt = """
You are an expert in US trucking regulations and OSOW (Oversize/Overweight) permits for all 50 states.
Analyze this $stateName permit and provide comprehensive regulatory compliance information.

Your expertise covers:
- State-specific dimension and weight limits
- Interstate vs intrastate regulations 
- Required escorts and pilot cars
- Time restrictions and routing constraints
- Bridge and road restrictions
- Special permit requirements

Return a detailed JSON analysis.
        """.trimIndent()
        
        val userPrompt = buildString {
            appendLine("Analyze this $stateName OSOW permit:")
            appendLine()
            appendLine("PERMIT TEXT:")
            appendLine(combinedText)
            appendLine()
            if (origin != null && destination != null) {
                appendLine("PROPOSED ROUTE: From $origin to $destination")
                appendLine()
            }
            appendLine("Provide analysis as JSON with these fields:")
            appendLine("""
{
  "permitInfo": {
    "state": "$state",
    "permitNumber": "extracted number",
    "issueDate": "YYYY-MM-DD",
    "expirationDate": "YYYY-MM-DD",
    "permitType": "annual/single-trip/special"
  },
  "vehicleInfo": {
    "licensePlate": "plate number",
    "vin": "vehicle identification",
    "carrierName": "carrier name",
    "usdot": "DOT number"
  },
  "dimensions": {
    "length": number_in_feet,
    "width": number_in_feet, 
    "height": number_in_feet,
    "weight": number_in_pounds,
    "axles": number
  },
  "compliance": {
    "isCompliant": boolean,
    "violations": ["list of violations"],
    "warnings": ["list of warnings"],
    "requiredEscorts": ["front", "rear", "height_pole"],
    "timeRestrictions": "description",
    "routeRestrictions": ["restricted roads/bridges"]
  },
  "routing": {
    "origin": "origin location",
    "destination": "destination location", 
    "preferredRoutes": ["recommended routes"],
    "avoidRoutes": ["routes to avoid"],
    "bridgeRestrictions": ["bridge limitations"]
  },
  "stateRegulations": {
    "maxDimensionsWithoutPermit": {
      "length": number,
      "width": number,
      "height": number,
      "weight": number
    },
    "maxDimensionsWithPermit": {
      "length": number,
      "width": number, 
      "height": number,
      "weight": number
    },
    "specialRequirements": ["state-specific requirements"]
  }
}
            """.trimIndent())
        }
        
        try {
            val requestBody = """
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": ${JSONObject.quote(systemPrompt)}},
    {"role": "user", "content": ${JSONObject.quote(userPrompt)}}
  ],
  "temperature": 0.1,
  "max_tokens": 2000,
  "response_format": {"type": "json_object"}
}
            """.trimIndent()
            
            android.util.Log.d("OpenAIService", "Making national permit analysis request for state: $state")
            
            val response = client.post("$baseUrl/chat/completions") {
                setBody(requestBody)
                header("Content-Type", "application/json")
            }
            
            if (response.status.value >= 400) {
                val errorBody = response.bodyAsText()
                android.util.Log.e("OpenAIService", "API Error: $errorBody")
                return@withContext NationalPermitAnalysis(
                    success = false,
                    error = "OpenAI API error: ${response.status}",
                    analysis = null
                )
            }
            
            val responseBody = response.bodyAsText()
            android.util.Log.d("OpenAIService", "OpenAI Response: $responseBody")
            
            val responseJson = JSONObject(responseBody)
            val choices = responseJson.getJSONArray("choices")
            
            if (choices.length() == 0) {
                return@withContext NationalPermitAnalysis(
                    success = false,
                    error = "No response from AI",
                    analysis = null
                )
            }
            
            val messageContent = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            
            val analysisJson = JSONObject(messageContent)
            val analysis = parseNationalAnalysis(analysisJson, state, ocrTexts)
            
            NationalPermitAnalysis(
                success = true,
                analysis = analysis,
                rawResponse = messageContent
            )
            
        } catch (e: Exception) {
            android.util.Log.e("OpenAIService", "National permit analysis failed", e)
            NationalPermitAnalysis(
                success = false,
                error = "Analysis failed: ${e.message}",
                analysis = null
            )
        }
    }
    
    /**
     * Analyze route compliance using national regulations knowledge
     * Works for interstate and intrastate routing across all US states
     */
    suspend fun analyzeRouteCompliance(
        permit: Permit,
        originState: String,
        destinationState: String,
        routeWaypoints: List<String> = emptyList()
    ): ComplianceAnalysis = withContext(Dispatchers.IO) {
        
        val isInterstate = originState != destinationState
        val routeDescription = if (routeWaypoints.isNotEmpty()) {
            "via ${routeWaypoints.joinToString(", ")}"
        } else {
            "direct route"
        }
        
        val prompt = """
Analyze the compliance of this truck route with OSOW regulations:

TRUCK DETAILS:
- Permit State: ${permit.state}
- Permit Number: ${permit.permitNumber}
- Length: ${permit.dimensions.length} ft
- Width: ${permit.dimensions.width} ft  
- Height: ${permit.dimensions.height} ft
- Weight: ${permit.dimensions.weight} lbs
- Axles: ${permit.dimensions.axles}

ROUTE:
- Type: ${if (isInterstate) "Interstate" else "Intrastate"}
- From: ${permit.origin ?: "Origin"} ($originState)
- To: ${permit.destination ?: "Destination"} ($destinationState)
- Route: $routeDescription

PERMIT RESTRICTIONS:
${permit.restrictions.joinToString("\n") { "- $it" }}

Provide compliance analysis covering:
1. Multi-state permit reciprocity (if interstate)
2. Bridge and road restrictions along route
3. Required escorts and pilot cars
4. Time-of-day restrictions
5. Routing compliance recommendations

Return as JSON:
{
  "isCompliant": boolean,
  "violations": ["list of violations"],
  "warnings": ["list of warnings"],
  "recommendations": ["list of suggestions"],
  "requiredActions": ["actions driver must take"],
  "stateSpecificNotes": ["state-by-state considerations"]
}
        """.trimIndent()
        
        try {
            val requestBody = """
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "You are an expert in US trucking regulations and multi-state OSOW permit compliance."},
    {"role": "user", "content": ${JSONObject.quote(prompt)}}
  ],
  "temperature": 0.1,
  "max_tokens": 1500,
  "response_format": {"type": "json_object"}
}
            """.trimIndent()
            
            android.util.Log.d("OpenAIService", "Analyzing route compliance: $originState -> $destinationState")
            
            val response = client.post("$baseUrl/chat/completions") {
                setBody(requestBody)
                header("Content-Type", "application/json")
            }
            
            val responseBody = response.bodyAsText()
            val responseJson = JSONObject(responseBody)
            val choices = responseJson.getJSONArray("choices")
            
            if (choices.length() == 0) {
                return@withContext ComplianceAnalysis(
                    isCompliant = true,
                    issues = emptyList(),
                    suggestions = listOf("Unable to analyze compliance - check manually"),
                    fullAnalysis = "Analysis unavailable"
                )
            }
            
            val messageContent = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            
            val analysisJson = JSONObject(messageContent)
            
            ComplianceAnalysis(
                isCompliant = analysisJson.optBoolean("isCompliant", true),
                issues = jsonArrayToList(analysisJson.optJSONArray("violations")),
                suggestions = jsonArrayToList(analysisJson.optJSONArray("recommendations")),
                fullAnalysis = messageContent
            )
            
        } catch (e: Exception) {
            android.util.Log.e("OpenAIService", "Compliance analysis failed", e)
            ComplianceAnalysis(
                isCompliant = true,
                issues = emptyList(),
                suggestions = listOf("Manual compliance check recommended"),
                fullAnalysis = "Analysis failed: ${e.message}"
            )
        }
    }
    
    /**
     * Parse OpenAI's national analysis into a Permit object
     */
    private fun parseNationalAnalysis(
        analysisJson: JSONObject,
        state: String,
        ocrTexts: List<String>
    ): Permit {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        // Extract permit info
        val permitInfo = analysisJson.optJSONObject("permitInfo")
        val vehicleInfo = analysisJson.optJSONObject("vehicleInfo")
        val dimensions = analysisJson.optJSONObject("dimensions")
        val compliance = analysisJson.optJSONObject("compliance")
        val routing = analysisJson.optJSONObject("routing")
        
        return Permit(
            id = "national_${System.currentTimeMillis()}",
            state = state,
            permitNumber = permitInfo?.optString("permitNumber") ?: "UNKNOWN",
            issueDate = try {
                dateFormat.parse(permitInfo?.optString("issueDate") ?: "")
            } catch (e: Exception) {
                Date()
            },
            expirationDate = try {
                dateFormat.parse(permitInfo?.optString("expirationDate") ?: "")
            } catch (e: Exception) {
                Date(System.currentTimeMillis() + 90 * 24 * 60 * 60 * 1000L)
            },
            vehicleInfo = VehicleInfo(
                make = null,
                model = null,
                year = null,
                licensePlate = vehicleInfo?.optString("licensePlate"),
                vin = vehicleInfo?.optString("vin"),
                unitNumber = null
            ),
            dimensions = TruckDimensions(
                length = dimensions?.optDouble("length")?.takeIf { it > 0 },
                width = dimensions?.optDouble("width")?.takeIf { it > 0 },
                height = dimensions?.optDouble("height")?.takeIf { it > 0 },
                weight = dimensions?.optDouble("weight")?.takeIf { it > 0 },
                axles = dimensions?.optInt("axles")?.takeIf { it > 0 },
                overhangFront = null,
                overhangRear = null
            ),
            routeDescription = routing?.optString("origin")?.let { origin ->
                routing.optString("destination")?.let { dest ->
                    "From $origin to $dest"
                }
            },
            origin = routing?.optString("origin"),
            destination = routing?.optString("destination"),
            restrictions = jsonArrayToList(compliance?.optJSONArray("routeRestrictions")),
            rawImagePath = null,
            ocrText = ocrTexts.joinToString("\n\n--- PAGE BREAK ---\n\n"),
            ocrTexts = ocrTexts,
            aiParsedData = analysisJson.toString(),
            processingMethod = ProcessingMethod.AI_ENHANCED,
            isValid = compliance?.optBoolean("isCompliant", false) == true,
            validationErrors = jsonArrayToList(compliance?.optJSONArray("violations")),
            imagePaths = emptyList() // Will be set later if needed
        )
    }
    
    /**
     * Helper function to convert JSONArray to List<String>
     */
    private fun jsonArrayToList(jsonArray: JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }
    
    /**
     * Complete US states mapping for national coverage
     */
    private fun getStateName(stateCode: String): String {
        return when (stateCode.uppercase()) {
            "AL" -> "Alabama"
            "AK" -> "Alaska"
            "AZ" -> "Arizona"
            "AR" -> "Arkansas"
            "CA" -> "California"
            "CO" -> "Colorado"
            "CT" -> "Connecticut"
            "DE" -> "Delaware"
            "FL" -> "Florida"
            "GA" -> "Georgia"
            "HI" -> "Hawaii"
            "ID" -> "Idaho"
            "IL" -> "Illinois"
            "IN" -> "Indiana"
            "IA" -> "Iowa"
            "KS" -> "Kansas"
            "KY" -> "Kentucky"
            "LA" -> "Louisiana"
            "ME" -> "Maine"
            "MD" -> "Maryland"
            "MA" -> "Massachusetts"
            "MI" -> "Michigan"
            "MN" -> "Minnesota"
            "MS" -> "Mississippi"
            "MO" -> "Missouri"
            "MT" -> "Montana"
            "NE" -> "Nebraska"
            "NV" -> "Nevada"
            "NH" -> "New Hampshire"
            "NJ" -> "New Jersey"
            "NM" -> "New Mexico"
            "NY" -> "New York"
            "NC" -> "North Carolina"
            "ND" -> "North Dakota"
            "OH" -> "Ohio"
            "OK" -> "Oklahoma"
            "OR" -> "Oregon"
            "PA" -> "Pennsylvania"
            "RI" -> "Rhode Island"
            "SC" -> "South Carolina"
            "SD" -> "South Dakota"
            "TN" -> "Tennessee"
            "TX" -> "Texas"
            "UT" -> "Utah"
            "VT" -> "Vermont"
            "VA" -> "Virginia"
            "WA" -> "Washington"
            "WV" -> "West Virginia"
            "WI" -> "Wisconsin"
            "WY" -> "Wyoming"
            "DC" -> "District of Columbia"
            else -> stateCode
        }
    }
    
    /**
     * Summarize compliance results into natural language
     * This is the new summarizer-only approach - no law lookup
     */
    suspend fun summarizeCompliance(
        core: ComplianceCore, 
        contact: DotContact?
    ): String = withContext(Dispatchers.IO) {
        
        val coreJson = serializeComplianceCore(core)
        val contactJson = contact?.let { serializeDotContact(it) }
        
        val userPrompt = Prompts.userSummaryPrompt(coreJson, contactJson)
        
        try {
            val requestBody = """
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": ${JSONObject.quote(Prompts.SYSTEM_SUMMARY)}},
    {"role": "user", "content": ${JSONObject.quote(userPrompt)}}
  ],
  "temperature": 0.0,
  "max_tokens": 150
}
            """.trimIndent()
            
            android.util.Log.d("OpenAIService", "Making compliance summary request")
            
            val response = client.post("$baseUrl/chat/completions") {
                setBody(requestBody)
                header("Content-Type", "application/json")
            }
            
            if (response.status.value >= 400) {
                val errorBody = response.bodyAsText()
                android.util.Log.e("OpenAIService", "Summary API Error: $errorBody")
                throw Exception("OpenAI API error: ${response.status}")
            }
            
            val responseBody = response.bodyAsText()
            android.util.Log.d("OpenAIService", "Summary response received")
            
            val responseJson = JSONObject(responseBody)
            val choices = responseJson.getJSONArray("choices")
            
            if (choices.length() == 0) {
                throw Exception("No response from AI summarizer")
            }
            
            val messageContent = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            
            return@withContext messageContent.trim()
            
        } catch (e: Exception) {
            android.util.Log.e("OpenAIService", "Compliance summarization failed", e)
            throw e
        }
    }
    
    /**
     * Serialize ComplianceCore to JSON for the summarizer
     */
    private fun serializeComplianceCore(core: ComplianceCore): String {
        return JSONObject().apply {
            put("verdict", core.verdict)
            put("reasons", JSONArray(core.reasons))
            put("mustDo", JSONArray(core.mustDo))
            put("confidence", core.confidence)
            put("needsHuman", core.needsHuman)
            put("escortHints", JSONArray(core.escortHints))
        }.toString()
    }
    
    /**
     * Serialize DotContact to JSON for the summarizer
     */
    private fun serializeDotContact(contact: DotContact): String {
        return JSONObject().apply {
            put("agency", contact.agency)
            put("phone", contact.phone)
            put("url", contact.url)
            put("hours", contact.hours)
        }.toString()
    }
    
    /**
     * Simple general chat method for non-compliance questions
     */
    suspend fun generalChat(message: String): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = """
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "You are Clearway Cargo's helpful dispatcher assistant. Keep responses brief, friendly, and professional. Answer in 1-3 sentences."},
    {"role": "user", "content": ${JSONObject.quote(message)}}
  ],
  "temperature": 0.3,
  "max_tokens": 150
}
            """.trimIndent()
            
            android.util.Log.d("OpenAIService", "Making general chat request")
            
            val response = client.post("$baseUrl/chat/completions") {
                setBody(requestBody)
                header("Content-Type", "application/json")
            }
            
            if (response.status.value >= 400) {
                val errorBody = response.bodyAsText()
                android.util.Log.e("OpenAIService", "General chat API Error: $errorBody")
                throw Exception("OpenAI API error: ${response.status}")
            }
            
            val responseBody = response.bodyAsText()
            android.util.Log.d("OpenAIService", "General chat response received")
            
            val responseJson = JSONObject(responseBody)
            val choices = responseJson.getJSONArray("choices")
            
            if (choices.length() == 0) {
                throw Exception("No response from AI")
            }
            
            val messageContent = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            
            return@withContext messageContent.trim()
            
        } catch (e: Exception) {
            android.util.Log.e("OpenAIService", "General chat failed", e)
            throw e
        }
    }
    
    fun close() {
        client.close()
    }
}

// Result classes for national permit analysis
data class NationalPermitAnalysis(
    val success: Boolean,
    val analysis: Permit?,
    val error: String? = null,
    val rawResponse: String? = null
)

data class PermitParseResult(
    val success: Boolean,
    val permit: Permit?,
    val error: String? = null,
    val aiResponse: String? = null
)

data class ComplianceAnalysis(
    val isCompliant: Boolean,
    val issues: List<String>,
    val suggestions: List<String>,
    val fullAnalysis: String
)