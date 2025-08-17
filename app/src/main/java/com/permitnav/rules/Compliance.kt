package com.permitnav.rules

import android.content.Context
import com.google.gson.Gson
import com.permitnav.data.models.*
import java.io.InputStreamReader
import java.util.*

class ComplianceEngine(private val context: Context) {
    
    private val gson = Gson()
    private val stateRulesCache = mutableMapOf<String, StateRules>()
    
    fun validatePermit(permit: Permit): ComplianceResult {
        // For Indiana (IN), use the new validation logic
        if (permit.state.uppercase() == "IN" || permit.state.uppercase() == "INDIANA") {
            return validateIndianaPermit(permit)
        }
        
        // For other states, try to load the old format
        val stateRules = loadStateRules(permit.state)
            ?: return ComplianceResult(
                isCompliant = false,
                violations = listOf("State rules not found for ${permit.state}"),
                warnings = emptyList(),
                suggestions = emptyList()
            )
        
        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        validateDimensions(permit.dimensions, stateRules.maxDimensions, violations, warnings)
        
        validateDates(permit, violations, warnings)
        
        validateRequiredFields(permit, stateRules.requiredFields, violations)
        
        validateTimeRestrictions(permit, stateRules.timeRestrictions, warnings, suggestions)
        
        validateEscortRequirements(permit.dimensions, stateRules.escortRequirements, warnings, suggestions)
        
        validateRouteCompliance(permit, stateRules.routeRequirements, violations, warnings)
        
        val permitType = determinePermitType(permit.dimensions, stateRules.permitTypes)
        if (permitType != null) {
            validateAgainstPermitType(permit.dimensions, permitType, violations, warnings)
        } else {
            violations.add("No suitable permit type found for these dimensions")
        }
        
        return ComplianceResult(
            isCompliant = violations.isEmpty(),
            violations = violations,
            warnings = warnings,
            suggestions = suggestions,
            permitType = permitType?.name,
            requiredEscorts = determineEscortRequirements(permit.dimensions, stateRules.escortRequirements)
        )
    }
    
    private fun validateIndianaPermit(permit: Permit): ComplianceResult {
        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        // Basic validation for Indiana
        validateDates(permit, violations, warnings)
        
        // Simple dimension validation
        val dimensions = permit.dimensions
        dimensions.height?.let { height ->
            when {
                height > 15.0 -> {
                    violations.add("Height ${height}ft exceeds Indiana single trip maximum of 15ft")
                }
                height > 13.5 -> {
                    warnings.add("Height over 13.5ft requires special routing")
                    if (height > 14.5) {
                        suggestions.add("Front escort with height pole required")
                    } else {
                        // No additional escort needed
                    }
                }
                else -> {
                    // No action needed for legal height
                }
            }
        }
        
        dimensions.width?.let { width ->
            when {
                width > 16.0 -> {
                    violations.add("Width ${width}ft exceeds Indiana single trip maximum of 16ft")
                }
                width > 12.333 -> {
                    warnings.add("Width exceeds annual permit limit - single trip permit required")
                    if (width > 12) {
                        suggestions.add("Rear escort required")
                    } else {
                        // No escort needed yet
                    }
                    if (width > 14) {
                        suggestions.add("Front and rear escort required")
                    } else {
                        // Front escort not needed
                    }
                }
                width > 8.5 -> {
                    warnings.add("Overwidth load - annual permit available")
                }
                else -> {
                    // No action needed for legal width
                }
            }
        }
        
        dimensions.length?.let { length ->
            when {
                length > 150.0 -> {
                    violations.add("Length ${length}ft exceeds Indiana single trip maximum of 150ft")
                }
                length > 110.0 -> {
                    warnings.add("Length exceeds annual permit limit - single trip permit required")
                    if (length > 100) {
                        suggestions.add("Rear escort required")
                    } else {
                        // No escort needed for length
                    }
                }
                else -> {
                    // No action needed for legal length
                }
            }
        }
        
        dimensions.weight?.let { weight ->
            if (weight > 200000) {
                violations.add("Weight ${weight}lbs exceeds Indiana single trip maximum of 200,000lbs")
            }
        }
        
        // Basic field validation
        if (permit.permitNumber.isBlank() || permit.permitNumber == "NUMBER") {
            violations.add("Missing or invalid permit number")
        }
        
        return ComplianceResult(
            isCompliant = violations.isEmpty(),
            violations = violations,
            warnings = warnings,
            suggestions = suggestions,
            permitType = if (violations.isEmpty()) "Indiana Oversize/Overweight" else null,
            requiredEscorts = suggestions.filter { it.contains("escort") }
        )
    }
    
    private fun validateDimensions(
        dimensions: TruckDimensions,
        maxDimensions: MaxDimensions,
        violations: MutableList<String>,
        warnings: MutableList<String>
    ) {
        dimensions.weight?.let { weight ->
            if (weight > maxDimensions.maxWeight) {
                violations.add("Weight ${weight}lbs exceeds maximum ${maxDimensions.maxWeight}lbs")
            }
            if (weight > maxDimensions.maxWeight * 0.9) {
                warnings.add("Weight is above 90% of maximum allowed")
            }
        }
        
        dimensions.height?.let { height ->
            if (height > maxDimensions.maxHeight) {
                violations.add("Height ${height}ft exceeds maximum ${maxDimensions.maxHeight}ft")
            }
            if (height > 13.5) {
                warnings.add("Height over 13.5ft requires route survey for bridges/overpasses")
            }
        }
        
        dimensions.width?.let { width ->
            if (width > maxDimensions.maxWidth) {
                violations.add("Width ${width}ft exceeds maximum ${maxDimensions.maxWidth}ft")
            }
            if (width > 8.5) {
                warnings.add("Overwidth load - check for escort requirements")
            }
        }
        
        dimensions.length?.let { length ->
            if (length > maxDimensions.maxLength) {
                violations.add("Length ${length}ft exceeds maximum ${maxDimensions.maxLength}ft")
            }
        }
        
        dimensions.overhangFront?.let { overhang ->
            if (overhang > maxDimensions.maxOverhangFront) {
                violations.add("Front overhang ${overhang}ft exceeds maximum ${maxDimensions.maxOverhangFront}ft")
            }
        }
        
        dimensions.overhangRear?.let { overhang ->
            if (overhang > maxDimensions.maxOverhangRear) {
                violations.add("Rear overhang ${overhang}ft exceeds maximum ${maxDimensions.maxOverhangRear}ft")
            }
        }
    }
    
    private fun validateDates(permit: Permit, violations: MutableList<String>, warnings: MutableList<String>) {
        val now = Date()
        
        if (permit.issueDate.after(now)) {
            violations.add("Permit issue date is in the future")
        }
        
        if (permit.expirationDate.before(now)) {
            violations.add("Permit has expired")
        }
        
        val calendar = Calendar.getInstance()
        calendar.time = permit.expirationDate
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        if (now.after(calendar.time)) {
            warnings.add("Permit expires within 24 hours")
        }
        
        calendar.time = permit.issueDate
        calendar.add(Calendar.DAY_OF_YEAR, 5)
        if (permit.expirationDate.after(calendar.time)) {
            warnings.add("Permit validity period exceeds typical 5-day limit")
        }
    }
    
    private fun validateRequiredFields(
        permit: Permit,
        requiredFields: List<String>,
        violations: MutableList<String>
    ) {
        if (permit.permitNumber.isBlank()) {
            violations.add("Missing permit number")
        }
        
        if (permit.vehicleInfo.licensePlate.isNullOrBlank() && permit.vehicleInfo.vin.isNullOrBlank()) {
            violations.add("Missing vehicle identification (license plate or VIN)")
        }
        
        if (permit.dimensions.weight == null) {
            violations.add("Missing weight information")
        }
        
        if (permit.routeDescription.isNullOrBlank() && requiredFields.contains("route")) {
            violations.add("Missing route description")
        }
    }
    
    private fun validateTimeRestrictions(
        permit: Permit,
        timeRestrictions: List<TimeRestriction>,
        warnings: MutableList<String>,
        suggestions: MutableList<String>
    ) {
        val dimensions = permit.dimensions
        
        for (restriction in timeRestrictions) {
            if (evaluateCondition(restriction.applicableCondition, dimensions)) {
                warnings.add(restriction.description)
                if (restriction.startTime != "sunset" && restriction.endTime != "sunrise") {
                    suggestions.add("Avoid travel ${restriction.startTime}-${restriction.endTime} on ${restriction.daysOfWeek.joinToString()}")
                }
            }
        }
    }
    
    private fun validateEscortRequirements(
        dimensions: TruckDimensions,
        escortReqs: EscortRequirements,
        warnings: MutableList<String>,
        suggestions: MutableList<String>
    ) {
        val width = dimensions.width ?: 0.0
        val length = dimensions.length ?: 0.0
        val weight = dimensions.weight ?: 0.0
        
        if (escortReqs.frontEscortRequired.widthThreshold?.let { width > it } == true ||
            escortReqs.frontEscortRequired.lengthThreshold?.let { length > it } == true) {
            warnings.add("Front escort vehicle required")
            suggestions.add("Arrange for certified front escort vehicle")
        }
        
        if (escortReqs.rearEscortRequired.widthThreshold?.let { width > it } == true ||
            escortReqs.rearEscortRequired.lengthThreshold?.let { length > it } == true) {
            warnings.add("Rear escort vehicle required")
            suggestions.add("Arrange for certified rear escort vehicle")
        }
        
        if (escortReqs.policeEscortRequired.widthThreshold?.let { width > it } == true ||
            escortReqs.policeEscortRequired.lengthThreshold?.let { length > it } == true ||
            escortReqs.policeEscortRequired.weightThreshold?.let { weight > it } == true) {
            warnings.add("Police escort required")
            suggestions.add("Contact Indiana State Police for escort coordination")
        }
    }
    
    private fun validateRouteCompliance(
        permit: Permit,
        routeReqs: RouteRequirements,
        violations: MutableList<String>,
        warnings: MutableList<String>
    ) {
        if (routeReqs.requiresSpecificRoute && permit.routeDescription.isNullOrBlank()) {
            violations.add("Specific route required but not provided")
        }
        
        if (routeReqs.requiresStateApprovedRoute) {
            warnings.add("Route must be state-approved before travel")
        }
        
        val weight = permit.dimensions.weight ?: 0.0
        for (bridge in routeReqs.bridgeRestrictions) {
            if (weight > bridge.maxWeight) {
                warnings.add("Weight exceeds limit for ${bridge.name} (max: ${bridge.maxWeight}lbs)")
            }
        }
    }
    
    private fun determinePermitType(dimensions: TruckDimensions, permitTypes: List<PermitType>): PermitType? {
        val weight = dimensions.weight ?: 0.0
        val height = dimensions.height ?: 0.0
        val width = dimensions.width ?: 0.0
        val length = dimensions.length ?: 0.0
        
        return permitTypes.firstOrNull { permitType ->
            weight <= permitType.allowedDimensions.maxWeight &&
            height <= permitType.allowedDimensions.maxHeight &&
            width <= permitType.allowedDimensions.maxWidth &&
            length <= permitType.allowedDimensions.maxLength
        }
    }
    
    private fun validateAgainstPermitType(
        dimensions: TruckDimensions,
        permitType: PermitType,
        violations: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val allowed = permitType.allowedDimensions
        
        dimensions.weight?.let { weight ->
            if (weight > allowed.maxWeight) {
                violations.add("Weight exceeds limit for ${permitType.name} permit")
            }
        }
        
        dimensions.height?.let { height ->
            if (height > allowed.maxHeight) {
                violations.add("Height exceeds limit for ${permitType.name} permit")
            }
        }
        
        dimensions.width?.let { width ->
            if (width > allowed.maxWidth) {
                violations.add("Width exceeds limit for ${permitType.name} permit")
            }
        }
        
        dimensions.length?.let { length ->
            if (length > allowed.maxLength) {
                violations.add("Length exceeds limit for ${permitType.name} permit")
            }
        }
    }
    
    private fun determineEscortRequirements(
        dimensions: TruckDimensions,
        escortReqs: EscortRequirements
    ): List<String> {
        val escorts = mutableListOf<String>()
        val width = dimensions.width ?: 0.0
        val length = dimensions.length ?: 0.0
        val weight = dimensions.weight ?: 0.0
        
        if (escortReqs.frontEscortRequired.widthThreshold?.let { width > it } == true ||
            escortReqs.frontEscortRequired.lengthThreshold?.let { length > it } == true) {
            escorts.add("Front Escort")
        }
        
        if (escortReqs.rearEscortRequired.widthThreshold?.let { width > it } == true ||
            escortReqs.rearEscortRequired.lengthThreshold?.let { length > it } == true) {
            escorts.add("Rear Escort")
        }
        
        if (escortReqs.policeEscortRequired.widthThreshold?.let { width > it } == true ||
            escortReqs.policeEscortRequired.lengthThreshold?.let { length > it } == true ||
            escortReqs.policeEscortRequired.weightThreshold?.let { weight > it } == true) {
            escorts.add("Police Escort")
        }
        
        return escorts
    }
    
    private fun evaluateCondition(condition: String?, dimensions: TruckDimensions): Boolean {
        condition ?: return false
        
        val width = dimensions.width ?: 0.0
        val length = dimensions.length ?: 0.0
        val weight = dimensions.weight ?: 0.0
        
        return when {
            condition.contains("width > 10ft") && width > 10 -> true
            condition.contains("width > 12ft") && width > 12 -> true
            condition.contains("length > 75ft") && length > 75 -> true
            condition.contains("length > 100ft") && length > 100 -> true
            else -> false
        }
    }
    
    private fun loadStateRules(stateCode: String): StateRules? {
        val cachedRules = stateRulesCache[stateCode.uppercase()]
        if (cachedRules != null) return cachedRules
        
        return try {
            val fileName = "state_rules/${stateCode.lowercase()}.json"
            context.assets.open(fileName).use { inputStream ->
                val reader = InputStreamReader(inputStream)
                val rules = gson.fromJson(reader, StateRules::class.java)
                stateRulesCache[stateCode.uppercase()] = rules
                rules
            }
        } catch (e: Exception) {
            null
        }
    }
}

data class ComplianceResult(
    val isCompliant: Boolean,
    val violations: List<String>,
    val warnings: List<String>,
    val suggestions: List<String>,
    val permitType: String? = null,
    val requiredEscorts: List<String> = emptyList()
)