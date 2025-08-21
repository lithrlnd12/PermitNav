package com.permitnav.navigation

import com.google.android.gms.maps.model.LatLng
import kotlinx.serialization.Serializable

/**
 * Maneuver data following HERE Routes v8 structure
 * Maps to action object in HERE response
 */
@Serializable
data class Maneuver(
    val idxOnPolyline: Int,           // Index into the polyline points (action.offset)
    val type: String,                 // Maneuver type (action.action)
    val instruction: String,          // Display instruction (action.instruction)
    val exitNumber: String? = null,   // Highway exit number if applicable
    val bearingBefore: Double,        // Bearing before the maneuver
    val bearingAfter: Double,         // Bearing after the maneuver
    val distanceMeters: Int,          // Distance to travel (action.length)
    val durationSeconds: Int? = null, // Time to travel (action.duration)
    val roadName: String? = null      // Road name (action.road?.name)
)

/**
 * Complete route geometry for guidance
 * Built from HERE Routes v8 response
 */
data class RouteGeom(
    val pts: List<LatLng>,            // Decoded polyline points
    val cumDist: DoubleArray,         // Cumulative distances along route
    val maneuvers: List<Maneuver>,    // Turn-by-turn maneuvers
    val totalMeters: Double           // Total route distance in meters
) {
    /**
     * Get the nearest point index on the route to a given location
     */
    fun getNearestPointIndex(location: LatLng): Int {
        if (pts.isEmpty()) return 0
        
        var minDistance = Double.MAX_VALUE
        var nearestIndex = 0
        
        pts.forEachIndexed { index, point ->
            val distance = distanceBetween(location.latitude, location.longitude, point.latitude, point.longitude)
            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = index
            }
            
            // Debug only if distances are unreasonable
            if (index < 3 && distance > 100000) {
                android.util.Log.w("RouteGeom", "Large distance calc $index: GPS(${location.latitude}, ${location.longitude}) to Route(${point.latitude}, ${point.longitude}) = ${distance}m")
            }
        }
        
        android.util.Log.d("RouteGeom", "Nearest point: index=$nearestIndex, distance=${minDistance}m")
        return nearestIndex
    }
    
    /**
     * Get the cumulative distance at a given point index
     */
    fun getDistanceAtIndex(index: Int): Double {
        return if (index < cumDist.size) cumDist[index] else totalMeters
    }
    
    /**
     * Get remaining distance from a given point index
     */
    fun getRemainingDistance(fromIndex: Int): Double {
        val currentDistance = getDistanceAtIndex(fromIndex)
        val remaining = maxOf(0.0, totalMeters - currentDistance)
        
        // Debug logging only if remaining distance is unreasonable
        if (remaining > 1000000) { // > 1000km
            android.util.Log.w("RouteGeom", "Large remaining distance: Index: $fromIndex, Current: ${currentDistance}m, Total: ${totalMeters}m, Remaining: ${remaining}m")
        }
        
        return remaining
    }
    
    /**
     * Find the next maneuver from current position
     */
    fun getNextManeuver(fromIndex: Int): Maneuver? {
        return maneuvers.firstOrNull { it.idxOnPolyline > fromIndex }
    }
    
    /**
     * Get distance to next maneuver
     */
    fun getDistanceToNextManeuver(fromIndex: Int): Double {
        val nextManeuver = getNextManeuver(fromIndex) ?: return 0.0
        val currentDistance = getDistanceAtIndex(fromIndex)
        val maneuverDistance = getDistanceAtIndex(nextManeuver.idxOnPolyline)
        return maxOf(0.0, maneuverDistance - currentDistance)
    }
    
    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0 // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        
        return earthRadius * c
    }
}

/**
 * Guidance tick data for UI updates
 */
data class GuidanceTick(
    val snappedPoint: LatLng,
    val remainingMeters: Double,
    val nextManeuver: Maneuver?,
    val distanceToManeuver: Double,
    val isOffRoute: Boolean = false,
    val shouldReroute: Boolean = false
)