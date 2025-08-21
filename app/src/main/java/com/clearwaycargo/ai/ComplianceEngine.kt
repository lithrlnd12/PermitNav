package com.clearwaycargo.ai

import android.util.Log
import com.permitnav.data.models.Permit
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class ComplianceCore(
    val verdict: String,            // "compliant" | "not_compliant" | "uncertain"
    val reasons: List<String>,      // short phrases (<=10 words)
    val mustDo: List<String>,       // short phrases (<=10 words)
    val confidence: Double,         // 0..1
    val needsHuman: Boolean,
    val escortHints: List<String> = emptyList()
)

data class StateRules(
    val maxDimensions: Dimensions?,
    val maxDimensionsWithPermit: Dimensions?,
    val escortRequirements: EscortRequirements?,
    val timeRestrictions: TimeRestrictions?,
    val routeRestrictions: List<String> = emptyList(),
    val specialRequirements: List<String> = emptyList()
) {
    data class Dimensions(
        val length: Double?,
        val width: Double?,
        val height: Double?,
        val weight: Double?
    )
    
    data class EscortRequirements(
        val frontEscortWidthFt: Double?,
        val rearEscortWidthFt: Double?,
        val frontEscortHeightFt: Double?,
        val heightPoleHeightFt: Double?,
        val pilotCarWeightLbs: Double?
    )
    
    data class TimeRestrictions(
        val daylightOnly: Boolean = false,
        val noWeekends: Boolean = false,
        val restrictedHours: List<String> = emptyList()
    )
}

object ComplianceEngine {
    
    private const val TAG = "ComplianceEngine"
    
    /**
     * Compare permit against state rules deterministically
     * 
     * @param permit The permit to check
     * @param rulesJson State rules JSON string (nullable)
     * @return ComplianceCore with verdict and details
     */
    fun compare(permit: Permit, rulesJson: String?): ComplianceCore {
        Log.d(TAG, "Comparing permit ${permit.permitNumber} against state rules")
        
        // Parse state rules
        val stateRules = if (rulesJson != null) {
            try {
                parseStateRules(rulesJson)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse state rules", e)
                null
            }
        } else {
            Log.w(TAG, "No state rules provided")
            null
        }
        
        // Start with high confidence, subtract for missing data
        var confidence = 0.9
        val reasons = mutableListOf<String>()
        val mustDo = mutableListOf<String>()
        val escortHints = mutableListOf<String>()
        var needsHuman = false
        
        // Check if we have basic permit data
        val dimensions = permit.dimensions
        if (dimensions.length == null || dimensions.width == null || 
            dimensions.height == null || dimensions.weight == null) {
            confidence -= 0.3
            reasons.add("Missing permit dimensions")
            needsHuman = true
        }
        
        // If no state rules, we're uncertain
        if (stateRules == null) {
            confidence = min(confidence, 0.5)
            reasons.add("State rules unavailable")
            needsHuman = true
            
            return ComplianceCore(
                verdict = "uncertain",
                reasons = reasons.take(3),
                mustDo = listOf("Verify with state DOT"),
                confidence = confidence.clamp01(),
                needsHuman = true,
                escortHints = emptyList()
            )
        }
        
        // Check dimensional compliance
        val dimensionCheck = checkDimensions(permit, stateRules)
        confidence -= dimensionCheck.confidencePenalty
        reasons.addAll(dimensionCheck.reasons)
        mustDo.addAll(dimensionCheck.mustDo)
        escortHints.addAll(dimensionCheck.escortHints)
        
        // Check route restrictions
        val routeCheck = checkRouteRestrictions(permit, stateRules)
        confidence -= routeCheck.confidencePenalty
        reasons.addAll(routeCheck.reasons)
        mustDo.addAll(routeCheck.mustDo)
        
        // Check time restrictions
        val timeCheck = checkTimeRestrictions(permit, stateRules)
        confidence -= timeCheck.confidencePenalty
        reasons.addAll(timeCheck.reasons)
        mustDo.addAll(timeCheck.mustDo)
        
        // Determine final verdict
        val verdict = when {
            confidence < 0.5 || needsHuman -> "uncertain"
            reasons.any { it.contains("exceeds", ignoreCase = true) || it.contains("banned", ignoreCase = true) } -> "not_compliant"
            else -> "compliant"
        }
        
        // Set needsHuman if uncertain or non-compliant
        if (verdict != "compliant" || confidence < 0.7) {
            needsHuman = true
        }
        
        Log.d(TAG, "Compliance check complete: verdict=$verdict, confidence=$confidence")
        
        return ComplianceCore(
            verdict = verdict,
            reasons = reasons.take(3), // Limit to 3 reasons
            mustDo = mustDo.take(2),   // Limit to 2 actions
            confidence = confidence.clamp01(),
            needsHuman = needsHuman,
            escortHints = escortHints.distinct().take(3)
        )
    }
    
    /**
     * Check dimensional compliance against state rules
     */
    private fun checkDimensions(permit: Permit, rules: StateRules): CheckResult {
        val reasons = mutableListOf<String>()
        val mustDo = mutableListOf<String>()
        val escortHints = mutableListOf<String>()
        var confidencePenalty = 0.0
        
        val dimensions = permit.dimensions
        val maxWithPermit = rules.maxDimensionsWithPermit
        
        if (maxWithPermit == null) {
            confidencePenalty += 0.2
            reasons.add("Dimension limits unknown")
            return CheckResult(reasons, mustDo, escortHints, confidencePenalty)
        }
        
        // Check each dimension
        dimensions.width?.let { width ->
            maxWithPermit.width?.let { maxWidth ->
                if (width > maxWidth) {
                    reasons.add("Width exceeds ${maxWidth}ft limit")
                }
                
                // Check escort requirements
                rules.escortRequirements?.let { escorts ->
                    escorts.frontEscortWidthFt?.let { frontThreshold ->
                        if (width >= frontThreshold) {
                            escortHints.add("Front escort required")
                        }
                    }
                    escorts.rearEscortWidthFt?.let { rearThreshold ->
                        if (width >= rearThreshold) {
                            escortHints.add("Rear escort required")
                        }
                    }
                }
            }
        }
        
        dimensions.height?.let { height ->
            maxWithPermit.height?.let { maxHeight ->
                if (height > maxHeight) {
                    reasons.add("Height exceeds ${maxHeight}ft limit")
                }
                
                // Check height pole requirement
                rules.escortRequirements?.heightPoleHeightFt?.let { poleThreshold ->
                    if (height >= poleThreshold) {
                        escortHints.add("Height pole required")
                    }
                }
            }
        }
        
        dimensions.length?.let { length ->
            maxWithPermit.length?.let { maxLength ->
                if (length > maxLength) {
                    reasons.add("Length exceeds ${maxLength}ft limit")
                }
            }
        }
        
        dimensions.weight?.let { weight ->
            maxWithPermit.weight?.let { maxWeight ->
                if (weight > maxWeight) {
                    reasons.add("Weight exceeds ${maxWeight}lbs limit")
                }
                
                // Check pilot car weight requirement
                rules.escortRequirements?.pilotCarWeightLbs?.let { pilotThreshold ->
                    if (weight >= pilotThreshold) {
                        escortHints.add("Pilot car required")
                    }
                }
            }
        }
        
        return CheckResult(reasons, mustDo, escortHints, confidencePenalty)
    }
    
    /**
     * Check route restrictions
     */
    private fun checkRouteRestrictions(permit: Permit, rules: StateRules): CheckResult {
        val reasons = mutableListOf<String>()
        val mustDo = mutableListOf<String>()
        
        // Simple route checking - in production would need route analysis
        if (rules.routeRestrictions.isNotEmpty()) {
            val routeDesc = permit.routeDescription?.lowercase() ?: ""
            val origin = permit.origin?.lowercase() ?: ""
            val destination = permit.destination?.lowercase() ?: ""
            
            val routeText = "$routeDesc $origin $destination"
            
            val bannedRoutes = rules.routeRestrictions.filter { restriction ->
                routeText.contains(restriction.lowercase())
            }
            
            if (bannedRoutes.isNotEmpty()) {
                reasons.add("Route includes banned segments")
                mustDo.add("Verify alternate route")
            }
        }
        
        return CheckResult(reasons, mustDo, emptyList(), 0.0)
    }
    
    /**
     * Check time/daylight restrictions
     */
    private fun checkTimeRestrictions(permit: Permit, rules: StateRules): CheckResult {
        val reasons = mutableListOf<String>()
        val mustDo = mutableListOf<String>()
        
        rules.timeRestrictions?.let { timeRules ->
            if (timeRules.daylightOnly) {
                reasons.add("Daylight hours only")
            }
            
            if (timeRules.noWeekends) {
                reasons.add("Weekday travel only")
            }
            
            if (timeRules.restrictedHours.isNotEmpty()) {
                mustDo.add("Check time restrictions")
            }
        }
        
        return CheckResult(reasons, mustDo, emptyList(), 0.0)
    }
    
    /**
     * Parse state rules JSON into StateRules object
     */
    private fun parseStateRules(rulesJson: String): StateRules {
        val json = JSONObject(rulesJson)
        
        val maxDimensions = json.optJSONObject("maxDimensions")?.let { dims ->
            StateRules.Dimensions(
                length = dims.optDouble("length").takeIf { !it.isNaN() },
                width = dims.optDouble("width").takeIf { !it.isNaN() },
                height = dims.optDouble("height").takeIf { !it.isNaN() },
                weight = dims.optDouble("weight").takeIf { !it.isNaN() }
            )
        }
        
        val maxDimensionsWithPermit = json.optJSONObject("maxDimensionsWithPermit")?.let { dims ->
            StateRules.Dimensions(
                length = dims.optDouble("length").takeIf { !it.isNaN() },
                width = dims.optDouble("width").takeIf { !it.isNaN() },
                height = dims.optDouble("height").takeIf { !it.isNaN() },
                weight = dims.optDouble("weight").takeIf { !it.isNaN() }
            )
        }
        
        val escortRequirements = json.optJSONObject("escortRequirements")?.let { escorts ->
            StateRules.EscortRequirements(
                frontEscortWidthFt = escorts.optDouble("frontEscortWidthFt").takeIf { !it.isNaN() },
                rearEscortWidthFt = escorts.optDouble("rearEscortWidthFt").takeIf { !it.isNaN() },
                frontEscortHeightFt = escorts.optDouble("frontEscortHeightFt").takeIf { !it.isNaN() },
                heightPoleHeightFt = escorts.optDouble("heightPoleHeightFt").takeIf { !it.isNaN() },
                pilotCarWeightLbs = escorts.optDouble("pilotCarWeightLbs").takeIf { !it.isNaN() }
            )
        }
        
        val timeRestrictions = json.optJSONObject("timeRestrictions")?.let { time ->
            StateRules.TimeRestrictions(
                daylightOnly = time.optBoolean("daylightOnly", false),
                noWeekends = time.optBoolean("noWeekends", false),
                restrictedHours = jsonArrayToList(time.optJSONArray("restrictedHours"))
            )
        }
        
        val routeRestrictions = jsonArrayToList(json.optJSONArray("routeRestrictions"))
        val specialRequirements = jsonArrayToList(json.optJSONArray("specialRequirements"))
        
        return StateRules(
            maxDimensions = maxDimensions,
            maxDimensionsWithPermit = maxDimensionsWithPermit,
            escortRequirements = escortRequirements,
            timeRestrictions = timeRestrictions,
            routeRestrictions = routeRestrictions,
            specialRequirements = specialRequirements
        )
    }
    
    /**
     * Helper to convert JSONArray to List<String>
     */
    private fun jsonArrayToList(jsonArray: org.json.JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }
    
    /**
     * Helper to clamp confidence to [0, 1]
     */
    private fun Double.clamp01(): Double = max(0.0, min(1.0, this))
    
    /**
     * Internal data class for check results
     */
    private data class CheckResult(
        val reasons: List<String>,
        val mustDo: List<String>,
        val escortHints: List<String>,
        val confidencePenalty: Double
    )
}