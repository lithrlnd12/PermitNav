package com.permitnav.services

import android.location.Location
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.permitnav.data.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/**
 * Service for detecting and managing truck navigation hazards
 */
class HazardDetectionService {
    
    companion object {
        private const val TAG = "HazardDetection"
        private const val HAZARD_SCAN_RADIUS_MILES = 5.0
        private const val CRITICAL_DISTANCE_MILES = 0.5
        private const val WARNING_DISTANCE_MILES = 2.0
    }
    
    private val _activeHazards = MutableStateFlow<List<TruckHazard>>(emptyList())
    val activeHazards: StateFlow<List<TruckHazard>> = _activeHazards
    
    private val _upcomingAlerts = MutableStateFlow<List<HazardAlert>>(emptyList())
    val upcomingAlerts: StateFlow<List<HazardAlert>> = _upcomingAlerts
    
    /**
     * Scan for hazards along the route
     */
    fun scanRouteForHazards(
        route: Route,
        permit: Permit,
        currentLocation: Location? = null
    ): List<TruckHazard> {
        val hazards = mutableListOf<TruckHazard>()
        
        // Get truck dimensions from permit
        val truckHeight = permit.dimensions.height ?: 13.5 // Default legal height
        val truckWeight = permit.dimensions.weight ?: 80000.0 // Default legal weight
        val truckWidth = permit.dimensions.width ?: 8.5 // Default legal width
        
        // Scan for bridge clearances along route
        hazards.addAll(checkBridgeClearances(route, truckHeight))
        
        // Check weight restrictions
        hazards.addAll(checkWeightRestrictions(route, truckWeight))
        
        // Check time restrictions from permit
        hazards.addAll(checkTimeRestrictions(permit))
        
        // Add construction zones (would come from real-time data)
        hazards.addAll(getConstructionZones(route))
        
        // Add weather hazards
        hazards.addAll(getWeatherHazards(route))
        
        // Add operational points (parking, fuel, etc.)
        hazards.addAll(getOperationalPoints(route))
        
        // Sort by distance from current location if available
        currentLocation?.let { location ->
            val sortedHazards = hazards.sortedBy { hazard ->
                calculateDistance(
                    location.latitude, location.longitude,
                    hazard.location.latitude, hazard.location.longitude
                )
            }
            _activeHazards.value = sortedHazards
            
            // Generate alerts for nearby hazards
            generateAlerts(sortedHazards, location)
            
            return sortedHazards
        }
        
        _activeHazards.value = hazards
        return hazards
    }
    
    /**
     * Check for low bridge clearances
     */
    private fun checkBridgeClearances(route: Route, truckHeight: Double): List<TruckHazard> {
        val hazards = mutableListOf<TruckHazard>()
        
        // Sample bridge data - in production, this would come from a database
        val bridges = listOf(
            BridgeData("BR001", LatLng(39.85, -86.10), 13.0, "Railroad Bridge on US-31"),
            BridgeData("BR002", LatLng(39.90, -85.95), 12.5, "Old Mill Rd Overpass"),
            BridgeData("BR003", LatLng(40.05, -85.80), 14.0, "I-70 Overpass")
        )
        
        bridges.forEach { bridge ->
            if (isNearRoute(bridge.location, route)) {
                val clearanceMargin = bridge.clearanceHeight - truckHeight
                
                val severity = when {
                    clearanceMargin < 0 -> HazardSeverity.CRITICAL
                    clearanceMargin < 0.5 -> HazardSeverity.HIGH
                    clearanceMargin < 1.0 -> HazardSeverity.MEDIUM
                    else -> HazardSeverity.LOW
                }
                
                if (clearanceMargin < 2.0) { // Only add if within 2 feet margin
                    hazards.add(
                        TruckHazard(
                            id = bridge.id,
                            type = HazardType.LOW_CLEARANCE,
                            location = bridge.location,
                            title = "Low Bridge: ${bridge.clearanceHeight}' clearance",
                            description = "${bridge.name}\nYour height: ${truckHeight}' | Clearance: ${bridge.clearanceHeight}' | Margin: ${String.format("%.1f", clearanceMargin)}'",
                            severity = severity,
                            restriction = HazardRestriction(maxHeight = bridge.clearanceHeight),
                            isOnRoute = true
                        )
                    )
                }
            }
        }
        
        return hazards
    }
    
    /**
     * Check for weight-restricted bridges and roads
     */
    private fun checkWeightRestrictions(route: Route, truckWeight: Double): List<TruckHazard> {
        val hazards = mutableListOf<TruckHazard>()
        
        // Sample weight restriction data
        val restrictions = listOf(
            WeightRestrictionData("WR001", LatLng(39.88, -86.05), 60000.0, "County Bridge 42"),
            WeightRestrictionData("WR002", LatLng(39.95, -85.90), 75000.0, "SR-37 Bridge")
        )
        
        restrictions.forEach { restriction ->
            if (isNearRoute(restriction.location, route) && truckWeight > restriction.maxWeight) {
                hazards.add(
                    TruckHazard(
                        id = restriction.id,
                        type = HazardType.WEIGHT_RESTRICTION,
                        location = restriction.location,
                        title = "Weight Limit: ${restriction.maxWeight / 1000}k lbs",
                        description = "${restriction.name}\nYour weight: ${truckWeight / 1000}k lbs | Limit: ${restriction.maxWeight / 1000}k lbs",
                        severity = HazardSeverity.CRITICAL,
                        restriction = HazardRestriction(maxWeight = restriction.maxWeight),
                        isOnRoute = true
                    )
                )
            }
        }
        
        return hazards
    }
    
    /**
     * Check time restrictions from permit
     */
    private fun checkTimeRestrictions(permit: Permit): List<TruckHazard> {
        val hazards = mutableListOf<TruckHazard>()
        
        // Parse permit restrictions for time-based rules
        permit.restrictions.forEach { restriction ->
            when {
                restriction.contains("daylight", ignoreCase = true) -> {
                    hazards.add(
                        TruckHazard(
                            id = "TIME001",
                            type = HazardType.TIME_RESTRICTION,
                            location = LatLng(permit.origin?.let { 39.7684 } ?: 0.0, -86.1581),
                            title = "Daylight Hours Only",
                            description = "Permit requires travel during daylight hours only (typically 30 min after sunrise to 30 min before sunset)",
                            severity = HazardSeverity.HIGH,
                            restriction = HazardRestriction(
                                timeRestriction = HazardTimeRestriction(
                                    allowedHours = "Sunrise + 30min to Sunset - 30min"
                                )
                            )
                        )
                    )
                }
                restriction.contains("weekend", ignoreCase = true) -> {
                    hazards.add(
                        TruckHazard(
                            id = "TIME002",
                            type = HazardType.TIME_RESTRICTION,
                            location = LatLng(39.7684, -86.1581),
                            title = "No Weekend Travel",
                            description = "Permit prohibits travel on weekends",
                            severity = HazardSeverity.HIGH,
                            restriction = HazardRestriction(
                                timeRestriction = HazardTimeRestriction(
                                    restrictedDays = listOf("Saturday", "Sunday")
                                )
                            )
                        )
                    )
                }
                restriction.contains("escort", ignoreCase = true) -> {
                    hazards.add(
                        TruckHazard(
                            id = "ESCORT001",
                            type = HazardType.ESCORT_REQUIRED,
                            location = LatLng(39.7684, -86.1581),
                            title = "Escort Required",
                            description = restriction,
                            severity = HazardSeverity.HIGH,
                            restriction = HazardRestriction(requiresEscort = true)
                        )
                    )
                }
            }
        }
        
        return hazards
    }
    
    /**
     * Get construction zones along route
     */
    private fun getConstructionZones(route: Route): List<TruckHazard> {
        // In production, this would query real-time construction data
        return listOf(
            TruckHazard(
                id = "CONST001",
                type = HazardType.CONSTRUCTION,
                location = LatLng(39.92, -86.00),
                title = "Construction Zone",
                description = "I-70 EB: Lane closure, reduced width. Use caution.",
                severity = HazardSeverity.MEDIUM,
                isOnRoute = true
            )
        )
    }
    
    /**
     * Get weather hazards
     */
    private fun getWeatherHazards(route: Route): List<TruckHazard> {
        // In production, integrate with weather API
        return listOf(
            TruckHazard(
                id = "WX001",
                type = HazardType.HIGH_WIND,
                location = LatLng(40.10, -85.70),
                title = "High Wind Advisory",
                description = "Winds 25-35 mph, gusts to 45 mph. Use extreme caution with high profile loads.",
                severity = HazardSeverity.MEDIUM,
                isOnRoute = true
            )
        )
    }
    
    /**
     * Get operational points (parking, fuel, etc.)
     */
    private fun getOperationalPoints(route: Route): List<TruckHazard> {
        return listOf(
            TruckHazard(
                id = "PARK001",
                type = HazardType.TRUCK_PARKING,
                location = LatLng(39.95, -85.95),
                title = "Truck Stop - Parking Available",
                description = "Pilot Travel Center\n50 spaces available\nFuel, food, showers",
                severity = HazardSeverity.INFO,
                isOnRoute = false,
                distanceFromRoute = 0.2
            ),
            TruckHazard(
                id = "WEIGH001",
                type = HazardType.WEIGH_STATION,
                location = LatLng(40.00, -85.85),
                title = "Weigh Station - OPEN",
                description = "I-70 EB Weigh Station\nAll commercial vehicles must enter when open",
                severity = HazardSeverity.HIGH,
                isOnRoute = true
            )
        )
    }
    
    /**
     * Generate alerts for upcoming hazards
     */
    private fun generateAlerts(hazards: List<TruckHazard>, currentLocation: Location) {
        val alerts = mutableListOf<HazardAlert>()
        
        hazards.forEach { hazard ->
            val distance = calculateDistance(
                currentLocation.latitude, currentLocation.longitude,
                hazard.location.latitude, hazard.location.longitude
            )
            
            // Only alert for hazards within warning distance
            if (distance <= WARNING_DISTANCE_MILES) {
                val timeToReach = estimateTimeToReach(distance, 55.0) // Assume 55 mph average
                
                val recommendedAction = when (hazard.type) {
                    HazardType.LOW_CLEARANCE -> "Check clearance! Consider alternate route if insufficient."
                    HazardType.WEIGHT_RESTRICTION -> "Weight limit ahead! Must use alternate route."
                    HazardType.CONSTRUCTION -> "Reduce speed, stay alert for lane shifts."
                    HazardType.HIGH_WIND -> "Reduce speed, maintain firm grip on steering."
                    HazardType.WEIGH_STATION -> "Prepare documents. Station is OPEN."
                    HazardType.TRUCK_PARKING -> "Rest area available in $distance miles."
                    else -> "Exercise caution."
                }
                
                alerts.add(
                    HazardAlert(
                        hazard = hazard,
                        distanceAhead = distance,
                        estimatedTimeToReach = timeToReach,
                        recommendedAction = recommendedAction
                    )
                )
            }
        }
        
        _upcomingAlerts.value = alerts.sortedBy { it.distanceAhead }
    }
    
    /**
     * Check if a location is near the route
     */
    private fun isNearRoute(location: LatLng, route: Route, thresholdMiles: Double = 0.5): Boolean {
        // Simplified check - in production, would check actual polyline distance
        val origin = route.origin
        val dest = route.destination
        
        val distToOrigin = calculateDistance(
            location.latitude, location.longitude,
            origin.latitude, origin.longitude
        )
        
        val distToDest = calculateDistance(
            location.latitude, location.longitude,
            dest.latitude, dest.longitude
        )
        
        val routeLength = calculateDistance(
            origin.latitude, origin.longitude,
            dest.latitude, dest.longitude
        )
        
        // Simple heuristic: if point is roughly between origin and destination
        return (distToOrigin + distToDest) < (routeLength + thresholdMiles * 2)
    }
    
    /**
     * Calculate distance between two points in miles
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 3959.0 // Earth's radius in miles
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
    
    /**
     * Estimate time to reach a point
     */
    private fun estimateTimeToReach(distanceMiles: Double, avgSpeedMph: Double): Int {
        return ((distanceMiles / avgSpeedMph) * 60).toInt() // Convert to minutes
    }
}

// Helper data classes for demo data
private data class BridgeData(
    val id: String,
    val location: LatLng,
    val clearanceHeight: Double,
    val name: String
)

private data class WeightRestrictionData(
    val id: String,
    val location: LatLng,
    val maxWeight: Double,
    val name: String
)