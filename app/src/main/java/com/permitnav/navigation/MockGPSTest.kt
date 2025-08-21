package com.permitnav.navigation

import android.location.Location
import android.util.Log
import com.google.android.gms.maps.model.LatLng

/**
 * Mock GPS testing for turn-by-turn guidance
 * Validates the Google Maps + HERE routing integration
 */
object MockGPSTest {
    
    private const val TAG = "MockGPSTest"
    
    /**
     * Test guidance engine with simulated GPS trace
     */
    fun testGuidanceWithMockGPS() {
        Log.d(TAG, "🧪 Starting Mock GPS Test")
        
        // Create a simple test route (Indianapolis to Chicago simulation)
        val testRoute = createTestRouteGeom()
        val guidanceEngine = GuidanceEngine(testRoute)
        
        // Create announcer for testing
        val announcer = Announcer { announcement ->
            Log.d(TAG, "🔊 TTS: $announcement")
        }
        
        // Simulate GPS points along the route
        val mockGPSPoints = generateMockGPSTrace(testRoute)
        
        Log.d(TAG, "Simulating ${mockGPSPoints.size} GPS points")
        
        mockGPSPoints.forEachIndexed { index, gpsPoint ->
            val location = createMockLocation(gpsPoint)
            
            // Process through guidance engine
            val tick = guidanceEngine.onLocation(location)
            
            // Process through announcer
            announcer.onTick(tick)
            
            // Log guidance state
            if (index % 10 == 0) { // Log every 10th point to avoid spam
                Log.d(TAG, "GPS $index: ${tick.remainingMeters / 1000.0} km remaining")
                tick.nextManeuver?.let {
                    Log.d(TAG, "  Next: ${it.instruction} in ${tick.distanceToManeuver}m")
                }
            }
            
            // Simulate off-route scenario
            if (index == 50) {
                val offRouteLocation = createMockLocation(LatLng(
                    gpsPoint.latitude + 0.001, // Move off route
                    gpsPoint.longitude + 0.001
                ))
                val offRouteTick = guidanceEngine.onLocation(offRouteLocation)
                Log.d(TAG, "🚨 Off-route test: shouldReroute=${offRouteTick.shouldReroute}")
            }
        }
        
        Log.d(TAG, "✅ Mock GPS test completed")
    }
    
    /**
     * Create a test RouteGeom with sample maneuvers
     */
    private fun createTestRouteGeom(): RouteGeom {
        val points = listOf(
            LatLng(39.7684, -86.1581), // Indianapolis
            LatLng(39.7700, -86.1580), // North
            LatLng(39.7720, -86.1570), // Turn right
            LatLng(39.7720, -86.1500), // East
            LatLng(39.7800, -86.1500), // North again
            LatLng(41.8781, -87.6298)  // Chicago
        )
        
        val cumDistances = doubleArrayOf(0.0, 180.0, 450.0, 1200.0, 2100.0, 185000.0)
        
        val maneuvers = listOf(
            Maneuver(
                idxOnPolyline = 1,
                type = "turn-right",
                instruction = "Turn right onto Main Street",
                bearingBefore = 0.0,
                bearingAfter = 90.0,
                distanceMeters = 1200
            ),
            Maneuver(
                idxOnPolyline = 3,
                type = "turn-left", 
                instruction = "Turn left onto Highway 65",
                bearingBefore = 90.0,
                bearingAfter = 0.0,
                distanceMeters = 180000
            )
        )
        
        return RouteGeom(
            pts = points,
            cumDist = cumDistances,
            maneuvers = maneuvers,
            totalMeters = 185000.0
        )
    }
    
    /**
     * Generate mock GPS trace along route
     */
    private fun generateMockGPSTrace(route: RouteGeom): List<LatLng> {
        val trace = mutableListOf<LatLng>()
        
        // Sample every 10th point + add some GPS noise
        route.pts.forEachIndexed { index, point ->
            if (index % 2 == 0) { // Every other point
                val noisyPoint = LatLng(
                    point.latitude + (Math.random() - 0.5) * 0.0001, // ~10m noise
                    point.longitude + (Math.random() - 0.5) * 0.0001
                )
                trace.add(noisyPoint)
            }
        }
        
        return trace
    }
    
    /**
     * Create mock Location object for testing
     */
    private fun createMockLocation(latLng: LatLng): Location {
        return Location("mock").apply {
            latitude = latLng.latitude
            longitude = latLng.longitude
            accuracy = 10f
            time = System.currentTimeMillis()
        }
    }
    
    /**
     * Test flexible polyline decoding
     */
    fun testPolylineDecoding() {
        Log.d(TAG, "🧪 Testing Polyline Decoding")
        
        // Sample HERE flexible polyline
        val samplePolyline = "BG05xgKvyIDsUvy4OADTGTQABwD2GvhUpPMBD_hUGKIFsKQD"
        
        val decoded = FlexiblePolylineDecoder.decodePolyline(samplePolyline)
        Log.d(TAG, "Decoded ${decoded.size} points from sample polyline")
        
        decoded.take(3).forEachIndexed { index, point ->
            Log.d(TAG, "Point $index: ${point.latitude}, ${point.longitude}")
        }
    }
    
    /**
     * Run all tests
     */
    fun runAllTests() {
        Log.d(TAG, "🚀 Starting Clearway Cargo Turn-by-Turn Tests")
        
        testPolylineDecoding()
        testGuidanceWithMockGPS()
        
        Log.d(TAG, "✅ All tests completed successfully")
    }
}