package com.permitnav.navigation

import android.location.Location
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import com.google.maps.android.SphericalUtil

/**
 * GuidanceEngine for GPS tracking and route progress
 * Follows MVP specifications for Google Maps + HERE routing integration
 */
class GuidanceEngine(
    private val routeGeom: RouteGeom
) {
    
    companion object {
        private const val TAG = "GuidanceEngine"
        
        // Off-route thresholds (meters)
        private const val LOCAL_ROAD_THRESHOLD = 35.0
        private const val HIGHWAY_THRESHOLD = 90.0
        
        // Off-route strike threshold
        private const val OFF_ROUTE_STRIKE_LIMIT = 3
    }
    
    private var lastKnownRouteIndex = 0
    private var offRouteStrikes = 0
    private var isHighwayRoute = false // TODO: Determine from route data
    
    /**
     * Process GPS location update and return guidance tick
     */
    fun onLocation(location: Location): GuidanceTick {
        val currentLatLng = LatLng(location.latitude, location.longitude)
        
        android.util.Log.d(TAG, "Processing location: ${location.latitude}, ${location.longitude}")
        
        // Snap location to route using PolyUtil
        val snappedPoint = snapToRoute(currentLatLng)
        val routeIndex = routeGeom.getNearestPointIndex(snappedPoint)
        
        // Calculate distance from GPS to snapped point
        val distanceToRoute = SphericalUtil.computeDistanceBetween(currentLatLng, snappedPoint)
        
        // Debug logging for large distances (indicates coordinate issues)
        if (distanceToRoute > 10000) { // > 10km indicates problem
            android.util.Log.w(TAG, "Large distance - GPS: ${currentLatLng.latitude}, ${currentLatLng.longitude}")
            android.util.Log.w(TAG, "Large distance - Snapped: ${snappedPoint.latitude}, ${snappedPoint.longitude}")
            android.util.Log.w(TAG, "Large distance calculation: ${distanceToRoute}m")
        }
        
        // Determine if off-route
        val offRouteThreshold = if (isHighwayRoute) HIGHWAY_THRESHOLD else LOCAL_ROAD_THRESHOLD
        val isCurrentlyOffRoute = distanceToRoute > offRouteThreshold
        
        android.util.Log.d(TAG, "Distance to route: ${distanceToRoute}m, threshold: ${offRouteThreshold}m")
        
        // Update off-route strike counter
        if (isCurrentlyOffRoute) {
            offRouteStrikes++
            android.util.Log.w(TAG, "Off-route strike #$offRouteStrikes")
        } else {
            offRouteStrikes = 0 // Reset on successful snap
            lastKnownRouteIndex = routeIndex
        }
        
        // Check if reroute is needed
        val shouldReroute = offRouteStrikes >= OFF_ROUTE_STRIKE_LIMIT
        
        if (shouldReroute) {
            android.util.Log.w(TAG, "Reroute needed after $offRouteStrikes strikes")
        }
        
        // Calculate remaining distance and next maneuver
        val workingIndex = if (isCurrentlyOffRoute) lastKnownRouteIndex else routeIndex
        val remainingMeters = routeGeom.getRemainingDistance(workingIndex)
        val nextManeuver = routeGeom.getNextManeuver(workingIndex)
        val distanceToManeuver = routeGeom.getDistanceToNextManeuver(workingIndex)
        
        android.util.Log.d(TAG, "Route progress: ${remainingMeters / 1000.0} km remaining")
        nextManeuver?.let {
            android.util.Log.d(TAG, "Next maneuver: ${it.instruction} in ${distanceToManeuver}m")
        }
        
        return GuidanceTick(
            snappedPoint = snappedPoint,
            remainingMeters = remainingMeters,
            nextManeuver = nextManeuver,
            distanceToManeuver = distanceToManeuver,
            isOffRoute = isCurrentlyOffRoute,
            shouldReroute = shouldReroute
        )
    }
    
    /**
     * Snap GPS location to the closest point on route polyline
     */
    private fun snapToRoute(location: LatLng): LatLng {
        if (routeGeom.pts.isEmpty()) return location
        
        return try {
            // Use PolyUtil to check if location is on path and find closest point
            if (PolyUtil.isLocationOnPath(location, routeGeom.pts, true, 100.0)) {
                // If on path, find the nearest point manually
                val nearestIndex = routeGeom.getNearestPointIndex(location)
                routeGeom.pts.getOrNull(nearestIndex) ?: location
            } else {
                // Find closest point manually
                val nearestIndex = routeGeom.getNearestPointIndex(location)
                routeGeom.pts.getOrNull(nearestIndex) ?: location
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error snapping to route", e)
            // Fallback: find nearest point manually
            val nearestIndex = routeGeom.getNearestPointIndex(location)
            routeGeom.pts.getOrNull(nearestIndex) ?: location
        }
    }
    
    /**
     * Reset off-route strikes (useful when rerouting)
     */
    fun resetOffRouteState() {
        offRouteStrikes = 0
        android.util.Log.d(TAG, "Off-route state reset")
    }
    
    /**
     * Update highway mode based on route characteristics
     */
    fun setHighwayMode(isHighway: Boolean) {
        isHighwayRoute = isHighway
        android.util.Log.d(TAG, "Highway mode: $isHighway")
    }
    
    /**
     * Get current off-route strike count
     */
    fun getOffRouteStrikes(): Int = offRouteStrikes
}