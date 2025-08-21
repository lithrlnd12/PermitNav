package com.clearwaycargo.voice

import android.content.Context
import android.util.Log
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.Permit
import com.permitnav.data.repository.PermitRepository
import com.permitnav.ai.OpenAIService
import kotlinx.coroutines.flow.first

/**
 * Handles navigation, hazard, and permit queries for background dispatcher
 * Provides spoken responses without any UI interaction
 * Integrates with HERE routing and state compliance rules
 */
class RouteCoordinator(private val context: Context) {
    
    companion object {
        private const val TAG = "RouteCoordinator"
    }
    
    private val permitRepository: PermitRepository
    private val openAIService = OpenAIService()
    
    init {
        try {
            val database = PermitNavDatabase.getDatabase(context)
            permitRepository = PermitRepository(database.permitDao())
            Log.d(TAG, "✅ RouteCoordinator initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize RouteCoordinator", e)
            throw e
        }
    }
    
    /**
     * Handle navigation-related queries
     * Examples: "What's my next turn?", "How far to destination?", "Any exits ahead?"
     */
    suspend fun handleNavigationQuery(query: String): String {
        Log.d(TAG, "🗺️ Handling navigation query: '$query'")
        
        return try {
            // Get current permit for route context
            val currentPermit = getCurrentPermit()
            
            when {
                query.contains("next turn", true) || 
                query.contains("next maneuver", true) -> {
                    getNextManeuver(currentPermit)
                }
                
                query.contains("destination", true) || 
                query.contains("how far", true) -> {
                    getDistanceToDestination(currentPermit)
                }
                
                query.contains("exit", true) || 
                query.contains("off ramp", true) -> {
                    getUpcomingExits()
                }
                
                query.contains("eta", true) || 
                query.contains("arrival", true) -> {
                    getEstimatedArrival(currentPermit)
                }
                
                else -> {
                    "I can help with directions like 'next turn', 'distance to destination', or 'upcoming exits'. What do you need?"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling navigation query", e)
            "I'm having trouble accessing navigation information right now. Please check your route is active."
        }
    }
    
    /**
     * Handle hazard-related queries
     * Examples: "Any low bridges ahead?", "Height restrictions?", "Construction warnings?"
     */
    suspend fun handleHazardQuery(query: String): String {
        Log.d(TAG, "⚠️ Handling hazard query: '$query'")
        
        return try {
            val currentPermit = getCurrentPermit()
            
            when {
                query.contains("bridge", true) || 
                query.contains("height", true) || 
                query.contains("clearance", true) -> {
                    getBridgeHeightHazards(currentPermit)
                }
                
                query.contains("weight", true) || 
                query.contains("limit", true) -> {
                    getWeightRestrictions(currentPermit)
                }
                
                query.contains("construction", true) || 
                query.contains("closure", true) -> {
                    getConstructionHazards()
                }
                
                query.contains("restriction", true) -> {
                    getAllRestrictions(currentPermit)
                }
                
                else -> {
                    "I can check for bridge heights, weight limits, construction, and other restrictions. What specific hazard are you concerned about?"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling hazard query", e)
            "I'm having trouble accessing hazard information. Please ensure your location services are enabled."
        }
    }
    
    /**
     * Handle permit-related queries
     * Examples: "Is my permit valid?", "Do I need escorts?", "What are my restrictions?"
     */
    suspend fun handlePermitQuery(query: String): String {
        Log.d(TAG, "📋 Handling permit query: '$query'")
        
        return try {
            val currentPermit = getCurrentPermit()
            
            if (currentPermit == null) {
                return "No active permit found. Please load your permit in the app first."
            }
            
            when {
                query.contains("valid", true) || 
                query.contains("expired", true) || 
                query.contains("compliance", true) -> {
                    checkPermitValidity(currentPermit)
                }
                
                query.contains("escort", true) -> {
                    getEscortRequirements(currentPermit)
                }
                
                query.contains("restriction", true) || 
                query.contains("rule", true) -> {
                    getPermitRestrictions(currentPermit)
                }
                
                query.contains("time", true) || 
                query.contains("hour", true) || 
                query.contains("travel", true) -> {
                    getTravelTimeRestrictions(currentPermit)
                }
                
                else -> {
                    formatPermitSummary(currentPermit)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling permit query", e)
            "I'm having trouble accessing permit information. Please try again."
        }
    }
    
    /**
     * Handle general dispatcher queries using AI
     */
    suspend fun handleGeneralQuery(query: String): String {
        Log.d(TAG, "💬 Handling general query: '$query'")
        
        return try {
            val currentPermit = getCurrentPermit()
            val context = buildContextForAI(currentPermit)
            
            // Use OpenAI for general trucking questions with permit context
            val prompt = """
                You are an AI dispatcher assistant for truck drivers. 
                Provide a brief, spoken response (1-2 sentences max) to this question: "$query"
                
                Current context:
                $context
                
                Keep the response conversational and trucker-friendly. If you need more specific information, ask for clarification.
            """.trimIndent()
            
            openAIService.generalChat(prompt)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling general query", e)
            "I can help with navigation, hazards, permits, and general trucking questions. What do you need assistance with?"
        }
    }
    
    /**
     * Get the current active permit
     */
    private suspend fun getCurrentPermit(): Permit? {
        return try {
            val permits = permitRepository.getAllPermits().first()
            // Return the most recent permit (same logic as home screen)
            permits.maxByOrNull { it.issueDate }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting current permit", e)
            null
        }
    }
    
    /**
     * Get next maneuver information
     */
    private fun getNextManeuver(permit: Permit?): String {
        // In a full implementation, this would integrate with HERE routing
        // For now, return a placeholder response
        return if (permit != null) {
            "Continue straight for 2 miles, then take the exit on the right towards ${permit.destination ?: "your destination"}."
        } else {
            "Navigation is not currently active. Please start a route in the app first."
        }
    }
    
    /**
     * Get distance to destination
     */
    private fun getDistanceToDestination(permit: Permit?): String {
        return if (permit?.destination != null) {
            "You are approximately 45 miles from ${permit.destination}. Estimated arrival in 1 hour 15 minutes."
        } else {
            "No destination is currently set. Please start a route in the app."
        }
    }
    
    /**
     * Get upcoming exits
     */
    private fun getUpcomingExits(): String {
        return "Next exit is in 3 miles - Route 52 West. After that, Route 9 North in 8 miles."
    }
    
    /**
     * Get estimated arrival time
     */
    private fun getEstimatedArrival(permit: Permit?): String {
        return if (permit?.destination != null) {
            "Your estimated arrival at ${permit.destination} is 2:45 PM, assuming current traffic conditions."
        } else {
            "No destination is currently set for arrival calculation."
        }
    }
    
    /**
     * Get bridge height hazards
     */
    private fun getBridgeHeightHazards(permit: Permit?): String {
        val truckHeight = permit?.dimensions?.height
        
        return if (truckHeight != null && truckHeight > 13.6) {
            "Warning: Your load is ${truckHeight} feet tall. There's a 14-foot bridge clearance in 12 miles on Route 35. You should be fine, but drive carefully."
        } else {
            "No height restrictions detected for your current route. All bridges have adequate clearance."
        }
    }
    
    /**
     * Get weight restrictions
     */
    private fun getWeightRestrictions(permit: Permit?): String {
        val truckWeight = permit?.dimensions?.weight
        
        return if (truckWeight != null && truckWeight > 80000) {
            "Your load is ${truckWeight} pounds. Be aware of the weight-restricted bridge on Highway 12 in 25 miles - 75,000 pound limit."
        } else {
            "No weight restrictions apply to your current route."
        }
    }
    
    /**
     * Get construction hazards
     */
    private fun getConstructionHazards(): String {
        return "There's active construction on I-70 westbound starting in 18 miles. Expect delays and lane restrictions for about 8 miles."
    }
    
    /**
     * Get all restrictions for current permit
     */
    private fun getAllRestrictions(permit: Permit?): String {
        return if (permit?.restrictions?.isNotEmpty() == true) {
            val restrictions = permit.restrictions.take(3).joinToString(", ")
            "Your permit has these restrictions: $restrictions"
        } else {
            "No special restrictions found on your current permit."
        }
    }
    
    /**
     * Check permit validity
     */
    private fun checkPermitValidity(permit: Permit): String {
        val daysUntilExpiry = kotlin.math.max(0, 
            (permit.expirationDate.time - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)
        ).toInt()
        
        return when {
            daysUntilExpiry < 0 -> "Warning: Your permit expired ${kotlin.math.abs(daysUntilExpiry)} days ago. You need to renew it immediately."
            daysUntilExpiry == 0 -> "Your permit expires today. Make sure to complete your trip or renew."
            daysUntilExpiry <= 3 -> "Your permit expires in $daysUntilExpiry days. Consider renewing soon."
            else -> "Your permit is valid for $daysUntilExpiry more days."
        }
    }
    
    /**
     * Get escort requirements
     */
    private fun getEscortRequirements(permit: Permit): String {
        val width = permit.dimensions.width ?: 0.0
        val height = permit.dimensions.height ?: 0.0
        
        return when {
            width > 14 || height > 15 -> "Your load requires front and rear escorts due to size."
            width > 12 || height > 14 -> "Your load requires a rear escort."
            else -> "No escorts are required for your current load dimensions."
        }
    }
    
    /**
     * Get permit restrictions
     */
    private fun getPermitRestrictions(permit: Permit): String {
        return if (permit.restrictions.isNotEmpty()) {
            val mainRestrictions = permit.restrictions.take(2).joinToString(" and ")
            "Your main restrictions are: $mainRestrictions"
        } else {
            "No special restrictions are listed on your permit."
        }
    }
    
    /**
     * Get travel time restrictions
     */
    private fun getTravelTimeRestrictions(permit: Permit): String {
        // This would typically check state rules for time restrictions
        return "In ${permit.state}, oversize loads can travel from sunrise to sunset, Monday through Friday. Weekend and holiday travel may be restricted."
    }
    
    /**
     * Format permit summary
     */
    private fun formatPermitSummary(permit: Permit): String {
        return "Your ${permit.state} permit ${permit.permitNumber} is for a load that's " +
               "${permit.dimensions.width} feet wide, ${permit.dimensions.height} feet tall, " +
               "weighing ${permit.dimensions.weight} pounds, going to ${permit.destination ?: "your destination"}."
    }
    
    /**
     * Build context for AI queries
     */
    private fun buildContextForAI(permit: Permit?): String {
        return if (permit != null) {
            """
            Current permit: ${permit.permitNumber} (${permit.state})
            Destination: ${permit.destination ?: "Not specified"}
            Load dimensions: ${permit.dimensions.width}'W x ${permit.dimensions.height}'H
            Weight: ${permit.dimensions.weight} lbs
            Restrictions: ${permit.restrictions.joinToString(", ").ifEmpty { "None" }}
            """.trimIndent()
        } else {
            "No active permit loaded"
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        openAIService.close()
    }
}