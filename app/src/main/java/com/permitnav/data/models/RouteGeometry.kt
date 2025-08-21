package com.permitnav.data.models

import com.google.android.gms.maps.model.LatLng

/**
 * Enhanced route geometry data for turn-by-turn navigation
 * without HERE SDK Premium - only using HERE REST API + Google Maps
 */
data class RouteGeom(
    val pts: List<LatLng>,              // Decoded polyline points
    val cumDist: DoubleArray,           // Cumulative distances in meters
    val maneuvers: List<Maneuver>,      // Turn-by-turn maneuvers
    val totalMeters: Double             // Total route distance in meters
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RouteGeom

        if (pts != other.pts) return false
        if (!cumDist.contentEquals(other.cumDist)) return false
        if (maneuvers != other.maneuvers) return false
        if (totalMeters != other.totalMeters) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pts.hashCode()
        result = 31 * result + cumDist.contentHashCode()
        result = 31 * result + maneuvers.hashCode()
        result = 31 * result + totalMeters.hashCode()
        return result
    }
}

/**
 * Navigation maneuver with enhanced geometry data
 */
data class Maneuver(
    val idxOnPolyline: Int,             // Index in pts array where maneuver occurs
    val type: ManeuverType,             // Type of maneuver (turn, merge, etc.)
    val instruction: String,            // Human-readable instruction
    val exitNumber: String? = null,     // Highway exit number if applicable
    val bearingBefore: Double,          // Bearing approaching maneuver (0-360°)
    val bearingAfter: Double,           // Bearing after maneuver (0-360°)
    val distanceMeters: Double = 0.0,   // Distance from previous maneuver
    val location: LatLng                // Precise location of maneuver
)

/**
 * Maneuver types for turn-by-turn navigation
 */
enum class ManeuverType {
    START,
    DESTINATION,
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    TURN_SLIGHT_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_SHARP_LEFT,
    TURN_SHARP_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
    MERGE_LEFT,
    MERGE_RIGHT,
    MERGE,
    RAMP_LEFT,
    RAMP_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    ROUNDABOUT_ENTER,
    ROUNDABOUT_EXIT,
    FERRY,
    WAYPOINT;
    
    /**
     * Get icon resource for this maneuver type
     */
    fun getIconResource(): Int {
        return when (this) {
            START -> android.R.drawable.ic_media_play
            DESTINATION -> android.R.drawable.ic_menu_mylocation
            STRAIGHT -> android.R.drawable.ic_menu_directions
            TURN_LEFT -> android.R.drawable.ic_menu_directions
            TURN_RIGHT -> android.R.drawable.ic_menu_directions
            TURN_SLIGHT_LEFT -> android.R.drawable.ic_menu_directions
            TURN_SLIGHT_RIGHT -> android.R.drawable.ic_menu_directions
            TURN_SHARP_LEFT -> android.R.drawable.ic_menu_directions
            TURN_SHARP_RIGHT -> android.R.drawable.ic_menu_directions
            U_TURN_LEFT -> android.R.drawable.ic_menu_revert
            U_TURN_RIGHT -> android.R.drawable.ic_menu_revert
            MERGE_LEFT -> android.R.drawable.ic_menu_directions
            MERGE_RIGHT -> android.R.drawable.ic_menu_directions
            MERGE -> android.R.drawable.ic_menu_directions
            RAMP_LEFT -> android.R.drawable.ic_menu_directions
            RAMP_RIGHT -> android.R.drawable.ic_menu_directions
            KEEP_LEFT -> android.R.drawable.ic_menu_directions
            KEEP_RIGHT -> android.R.drawable.ic_menu_directions
            ROUNDABOUT_ENTER -> android.R.drawable.ic_menu_rotate
            ROUNDABOUT_EXIT -> android.R.drawable.ic_menu_rotate
            FERRY -> android.R.drawable.ic_menu_directions
            WAYPOINT -> android.R.drawable.ic_menu_mylocation
        }
    }
    
    /**
     * Get voice-friendly instruction prefix
     */
    fun getVoicePrefix(): String {
        return when (this) {
            START -> "Start by heading"
            DESTINATION -> "You have arrived at your destination"
            STRAIGHT -> "Continue straight"
            TURN_LEFT -> "Turn left"
            TURN_RIGHT -> "Turn right"
            TURN_SLIGHT_LEFT -> "Turn slightly left"
            TURN_SLIGHT_RIGHT -> "Turn slightly right"
            TURN_SHARP_LEFT -> "Turn sharply left"
            TURN_SHARP_RIGHT -> "Turn sharply right"
            U_TURN_LEFT -> "Make a U-turn to the left"
            U_TURN_RIGHT -> "Make a U-turn to the right"
            MERGE_LEFT -> "Merge left"
            MERGE_RIGHT -> "Merge right"
            MERGE -> "Merge"
            RAMP_LEFT -> "Take the ramp on the left"
            RAMP_RIGHT -> "Take the ramp on the right"
            KEEP_LEFT -> "Keep left"
            KEEP_RIGHT -> "Keep right"
            ROUNDABOUT_ENTER -> "Enter the roundabout"
            ROUNDABOUT_EXIT -> "Exit the roundabout"
            FERRY -> "Board the ferry"
            WAYPOINT -> "Continue to waypoint"
        }
    }
}

/**
 * Navigation guidance state
 */
data class GuidanceTick(
    val snappedLocation: LatLng,        // Location snapped to route
    val remainingMeters: Double,        // Distance remaining to destination
    val nextManeuver: Maneuver?,        // Next maneuver to perform
    val distanceToManeuver: Double,     // Distance to next maneuver in meters
    val isOffRoute: Boolean,            // Whether driver is off the planned route
    val offRouteDistance: Double,       // Distance from route in meters
    val currentSpeed: Float = 0f,       // Current speed in m/s
    val eta: Long = 0L                  // Estimated time of arrival (timestamp)
)

/**
 * Route snapping result
 */
data class RouteSnapResult(
    val snappedPoint: LatLng,           // Snapped location on route
    val polylineIndex: Int,             // Index in polyline where snapped
    val distanceFromRoute: Double,      // Distance from original location in meters
    val progressMeters: Double          // Distance traveled along route
)

/**
 * Road type classification for announcement timing
 */
enum class RoadType {
    HIGHWAY,    // Interstate, freeway - longer announcement distances
    ARTERIAL,   // Major roads - medium announcement distances  
    LOCAL;      // City streets - shorter announcement distances
    
    /**
     * Get announcement thresholds for this road type
     */
    fun getAnnouncementThresholds(): AnnouncementThresholds {
        return when (this) {
            HIGHWAY -> AnnouncementThresholds(
                farDistance = 1200.0,   // 1200m = ~0.75 miles
                nearDistance = 600.0,   // 600m = ~0.37 miles  
                immediateDistance = 400.0 // 400m = ~0.25 miles
            )
            ARTERIAL -> AnnouncementThresholds(
                farDistance = 400.0,    // 400m = ~0.25 miles
                nearDistance = 200.0,   // 200m = ~0.12 miles
                immediateDistance = 100.0 // 100m = ~0.06 miles
            )
            LOCAL -> AnnouncementThresholds(
                farDistance = 250.0,    // 250m = ~0.15 miles
                nearDistance = 150.0,   // 150m = ~0.09 miles
                immediateDistance = 80.0  // 80m = ~0.05 miles
            )
        }
    }
    
    /**
     * Get off-route threshold for this road type
     */
    fun getOffRouteThreshold(): Double {
        return when (this) {
            HIGHWAY -> 90.0     // 90 meters for highways
            ARTERIAL -> 60.0    // 60 meters for arterials
            LOCAL -> 35.0       // 35 meters for local roads
        }
    }
}

/**
 * Announcement distance thresholds
 */
data class AnnouncementThresholds(
    val farDistance: Double,        // Far announcement (e.g., "In 1200 meters, turn right")
    val nearDistance: Double,       // Near announcement (e.g., "In 600 meters, turn right")  
    val immediateDistance: Double   // Immediate (e.g., "Turn right")
)