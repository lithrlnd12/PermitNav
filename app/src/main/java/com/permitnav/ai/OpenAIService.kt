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
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

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
     * National permit analysis using OpenAI - works with any US state
     * This replaces the need for individual state JSON files
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