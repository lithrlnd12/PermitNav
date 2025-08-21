package com.permitnav.data.models

import com.google.android.gms.maps.model.LatLng

/**
 * Represents various hazards and restrictions for truck navigation
 */
data class TruckHazard(
    val id: String,
    val type: HazardType,
    val location: LatLng,
    val title: String,
    val description: String,
    val severity: HazardSeverity,
    val restriction: HazardRestriction? = null,
    val isOnRoute: Boolean = false,
    val distanceFromRoute: Double? = null // in meters
)

enum class HazardType {
    // Physical Infrastructure
    LOW_CLEARANCE,
    WEIGHT_RESTRICTION,
    NARROW_ROAD,
    SHARP_TURN,
    STEEP_GRADE,
    CONSTRUCTION,
    
    // Regulatory
    PERMIT_RESTRICTION,
    TIME_RESTRICTION,
    ESCORT_REQUIRED,
    NO_TRUCKS,
    
    // Environmental
    WEATHER_ALERT,
    FLOOD_ZONE,
    HIGH_WIND,
    ICE_WARNING,
    
    // Operational
    WEIGH_STATION,
    TRUCK_PARKING,
    FUEL_STOP,
    REST_AREA,
    
    // Safety
    ACCIDENT,
    TRAFFIC_CONGESTION,
    HAZMAT_RESTRICTION
}

enum class HazardSeverity {
    INFO,      // Blue - Information only (parking, fuel)
    LOW,       // Green - Minor concern
    MEDIUM,    // Yellow - Caution required
    HIGH,      // Orange - Significant hazard
    CRITICAL   // Red - Must avoid/comply
}

data class HazardRestriction(
    val maxHeight: Double? = null,     // in feet
    val maxWeight: Double? = null,     // in pounds
    val maxWidth: Double? = null,      // in feet
    val maxLength: Double? = null,     // in feet
    val timeRestriction: HazardTimeRestriction? = null,
    val requiresEscort: Boolean = false,
    val requiresPermit: Boolean = false
)

data class HazardTimeRestriction(
    val allowedHours: String? = null,  // e.g., "6:00 AM - 9:00 PM"
    val restrictedDays: List<String> = emptyList(), // e.g., ["Sunday", "Holiday"]
    val seasonal: String? = null       // e.g., "No travel Nov-Mar"
)

/**
 * Truck stop with amenities information
 */
data class TruckStop(
    val id: String,
    val name: String,
    val location: LatLng,
    val amenities: TruckStopAmenities,
    val parkingSpaces: Int? = null,
    val parkingAvailable: Boolean? = null,
    val fuelPrice: FuelPrice? = null
)

data class TruckStopAmenities(
    val parking: Boolean = false,
    val fuel: Boolean = false,
    val shower: Boolean = false,
    val food: Boolean = false,
    val repair: Boolean = false,
    val scales: Boolean = false,
    val wifi: Boolean = false,
    val overnight: Boolean = false
)

data class FuelPrice(
    val diesel: Double? = null,
    val def: Double? = null,  // Diesel Exhaust Fluid
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Real-time hazard alert
 */
data class HazardAlert(
    val hazard: TruckHazard,
    val distanceAhead: Double, // in miles
    val estimatedTimeToReach: Int, // in minutes
    val recommendedAction: String,
    val alternateRoute: Route? = null
)