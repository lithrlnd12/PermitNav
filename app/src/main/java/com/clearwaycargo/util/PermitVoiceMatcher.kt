package com.clearwaycargo.util

import android.util.Log
import com.permitnav.data.models.Permit
import com.permitnav.data.repository.PermitRepository
import kotlinx.coroutines.flow.first

/**
 * Utility class for matching permits from voice commands
 * Handles natural language permit identification for hands-free operation
 */
class PermitVoiceMatcher(
    private val permitRepository: PermitRepository
) {
    
    companion object {
        private const val TAG = "PermitVoiceMatcher"
        
        // Keywords that indicate permit reference
        private val PERMIT_KEYWORDS = listOf(
            "permit", "authorization", "oversize", "overweight", "osow", 
            "load", "haul", "transport", "cargo"
        )
        
        // State keywords
        private val STATE_KEYWORDS = mapOf(
            "indiana" to "IN",
            "illinois" to "IL",
            "ohio" to "OH",
            "michigan" to "MI",
            "kentucky" to "KY",
            "texas" to "TX",
            "california" to "CA",
            "florida" to "FL",
            "new york" to "NY",
            "pennsylvania" to "PA"
        )
    }
    
    /**
     * Find a permit based on voice command text
     * Examples:
     * - "Check permit ending in 789"
     * - "My Chicago permit"
     * - "The Indiana permit"
     * - "Permit 12345"
     * - "My Texas load"
     */
    suspend fun findPermitFromVoiceCommand(voiceText: String): PermitMatch? {
        val lowerText = voiceText.lowercase()
        Log.d(TAG, "Analyzing voice command: $voiceText")
        
        // Check if the text mentions a permit
        val mentionsPermit = PERMIT_KEYWORDS.any { keyword ->
            lowerText.contains(keyword)
        }
        
        if (!mentionsPermit) {
            Log.d(TAG, "No permit keywords found in voice command")
            return null
        }
        
        // Get all permits (same as home screen)
        val permits = permitRepository.getAllPermits().first()
        if (permits.isEmpty()) {
            Log.d(TAG, "No permits found")
            return PermitMatch(
                permit = null,
                confidence = 0.0f,
                reason = "No permits found"
            )
        }
        
        Log.d(TAG, "Found ${permits.size} permits to search through")
        permits.forEach { permit ->
            Log.d(TAG, "Available permit: ${permit.permitNumber} (${permit.state})")
        }
        
        // Try different matching strategies
        
        // 1. Match by last digits of permit number
        val lastDigitsMatch = findByLastDigits(lowerText, permits)
        if (lastDigitsMatch != null) {
            return lastDigitsMatch
        }
        
        // 2. Match by full permit number
        val fullNumberMatch = findByFullNumber(lowerText, permits)
        if (fullNumberMatch != null) {
            return fullNumberMatch
        }
        
        // 3. Match by destination
        val destinationMatch = findByDestination(lowerText, permits)
        if (destinationMatch != null) {
            return destinationMatch
        }
        
        // 4. Match by state
        val stateMatch = findByState(lowerText, permits)
        if (stateMatch != null) {
            return stateMatch
        }
        
        // 5. If only one permit exists and user mentions "permit", assume they mean that one
        if (permits.size == 1 && mentionsPermit) {
            Log.d(TAG, "Only one permit exists, assuming user means: ${permits[0].permitNumber}")
            return PermitMatch(
                permit = permits[0],
                confidence = 0.8f,
                reason = "Only active permit"
            )
        }
        
        // 6. Return most recent permit as fallback
        val mostRecent = permits.maxByOrNull { it.issueDate }
        if (mostRecent != null) {
            Log.d(TAG, "No specific match, returning most recent: ${mostRecent.permitNumber}")
            return PermitMatch(
                permit = mostRecent,
                confidence = 0.5f,
                reason = "Most recent permit"
            )
        }
        
        return null
    }
    
    /**
     * Find permit by last 3-4 digits
     * Example: "ending in 789" or "ends with 1234"
     */
    private fun findByLastDigits(text: String, permits: List<Permit>): PermitMatch? {
        Log.d(TAG, "Searching for last digits in text: '$text'")
        
        // Patterns for last digits
        val patterns = listOf(
            Regex("ending\\s+(?:in|with)\\s+(\\d{3,4})"),
            Regex("ends\\s+(?:in|with)\\s+(\\d{3,4})"),
            Regex("last\\s+(?:three|four|3|4)\\s+(?:are|is)\\s+(\\d{3,4})"),
            Regex("(\\d{3,4})\\s+at\\s+the\\s+end"),
            // More flexible patterns
            Regex("(\\d{3,4})\\s*$"),  // Just digits at end
            Regex("\\b(\\d{3,4})\\b")  // Any 3-4 digit number
        )
        
        for ((index, pattern) in patterns.withIndex()) {
            val match = pattern.find(text)
            if (match != null) {
                val digits = match.groupValues[1]
                Log.d(TAG, "Pattern $index found digits: '$digits'")
                
                val foundPermit = permits.find { permit ->
                    val permitEndsWithDigits = permit.permitNumber.endsWith(digits)
                    Log.d(TAG, "Checking permit ${permit.permitNumber} ends with '$digits': $permitEndsWithDigits")
                    permitEndsWithDigits
                }
                
                if (foundPermit != null) {
                    Log.d(TAG, "Found permit by last digits: ${foundPermit.permitNumber}")
                    return PermitMatch(
                        permit = foundPermit,
                        confidence = 0.95f,
                        reason = "Matched last digits: $digits"
                    )
                } else {
                    Log.d(TAG, "No permit found ending with digits: '$digits'")
                }
            }
        }
        
        Log.d(TAG, "No last digit patterns matched")
        return null
    }
    
    /**
     * Find permit by full number
     * Example: "permit 12345" or "permit number 67890"
     */
    private fun findByFullNumber(text: String, permits: List<Permit>): PermitMatch? {
        // Extract potential permit numbers (5+ digits)
        val numberPattern = Regex("\\b(\\d{5,})\\b")
        val matches = numberPattern.findAll(text)
        
        for (match in matches) {
            val number = match.value
            val foundPermit = permits.find { permit ->
                permit.permitNumber.contains(number) || 
                permit.permitNumber.replace("-", "").contains(number)
            }
            if (foundPermit != null) {
                Log.d(TAG, "Found permit by full number: ${foundPermit.permitNumber}")
                return PermitMatch(
                    permit = foundPermit,
                    confidence = 0.9f,
                    reason = "Matched permit number: $number"
                )
            }
        }
        
        return null
    }
    
    /**
     * Find permit by destination
     * Example: "my Chicago permit" or "the Columbus load"
     */
    private fun findByDestination(text: String, permits: List<Permit>): PermitMatch? {
        // Common city names
        val cities = listOf(
            "chicago", "columbus", "indianapolis", "cincinnati", "cleveland",
            "detroit", "milwaukee", "louisville", "nashville", "st louis",
            "kansas city", "dallas", "houston", "austin", "san antonio",
            "denver", "phoenix", "las vegas", "los angeles", "san francisco",
            "seattle", "portland", "miami", "atlanta", "charlotte"
        )
        
        for (city in cities) {
            if (text.contains(city)) {
                val foundPermit = permits.find { permit ->
                    permit.destination?.lowercase()?.contains(city) == true ||
                    permit.routeDescription?.lowercase()?.contains(city) == true
                }
                if (foundPermit != null) {
                    Log.d(TAG, "Found permit by destination: ${foundPermit.permitNumber} for $city")
                    return PermitMatch(
                        permit = foundPermit,
                        confidence = 0.85f,
                        reason = "Destination: ${city.capitalize()}"
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Find permit by state
     * Example: "my Indiana permit" or "the Texas authorization"
     */
    private fun findByState(text: String, permits: List<Permit>): PermitMatch? {
        for ((stateName, stateCode) in STATE_KEYWORDS) {
            if (text.contains(stateName) || text.contains(stateCode.lowercase())) {
                val foundPermit = permits.find { permit ->
                    permit.state.equals(stateCode, ignoreCase = true)
                }
                if (foundPermit != null) {
                    Log.d(TAG, "Found permit by state: ${foundPermit.permitNumber} for $stateCode")
                    return PermitMatch(
                        permit = foundPermit,
                        confidence = 0.8f,
                        reason = "State: $stateCode"
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Switch permit context during active voice session
     * Returns a response message for the voice assistant to speak
     */
    fun generatePermitSwitchResponse(match: PermitMatch?): String {
        return when {
            match == null -> {
                "I couldn't find any permits. Please make sure you have an active permit loaded."
            }
            match.permit == null -> {
                match.reason ?: "No permits available to discuss."
            }
            match.confidence >= 0.9f -> {
                "Got it! Now discussing permit ${match.permit.permitNumber}. " +
                "This permit expires on ${formatDate(match.permit.expirationDate)} " +
                "and covers ${match.permit.state} routes."
            }
            match.confidence >= 0.7f -> {
                "I found permit ${match.permit.permitNumber}. " +
                "Is this the one you want to discuss?"
            }
            else -> {
                "I'm not sure, but I found permit ${match.permit.permitNumber}. " +
                "You can specify by saying the last three digits or the destination."
            }
        }
    }
    
    private fun formatDate(date: java.util.Date): String {
        val formatter = java.text.SimpleDateFormat("MMMM d", java.util.Locale.US)
        return formatter.format(date)
    }
}

/**
 * Result of permit matching from voice command
 */
data class PermitMatch(
    val permit: Permit?,
    val confidence: Float,
    val reason: String
)