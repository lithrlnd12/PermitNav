package com.permitnav.navigation

/**
 * Announcer for neural TTS integration
 * Manages announcement thresholds and triggers voice guidance
 */
class Announcer(
    private val onAnnouncement: (String) -> Unit // Callback to neural TTS system
) {
    
    companion object {
        private const val TAG = "Announcer"
        
        // Highway announcement thresholds (meters)
        private val HIGHWAY_THRESHOLDS = listOf(1200.0, 600.0, 400.0)
        
        // City/local road announcement thresholds (meters)
        private val CITY_THRESHOLDS = listOf(250.0, 150.0, 80.0)
    }
    
    private var lastAnnouncedManeuver: Maneuver? = null
    private var lastAnnouncementThreshold = Double.MAX_VALUE
    private var isHighwayMode = false
    
    /**
     * Process guidance tick and trigger announcements
     */
    fun onTick(tick: GuidanceTick) {
        val nextManeuver = tick.nextManeuver ?: return
        val distanceToManeuver = tick.distanceToManeuver
        
        // Skip if this is the same maneuver and we're further away than last announcement
        if (nextManeuver == lastAnnouncedManeuver && distanceToManeuver >= lastAnnouncementThreshold) {
            return
        }
        
        // Reset if new maneuver
        if (nextManeuver != lastAnnouncedManeuver) {
            lastAnnouncedManeuver = nextManeuver
            lastAnnouncementThreshold = Double.MAX_VALUE
        }
        
        val thresholds = if (isHighwayMode) HIGHWAY_THRESHOLDS else CITY_THRESHOLDS
        val applicableThreshold = thresholds.firstOrNull { 
            distanceToManeuver <= it && it < lastAnnouncementThreshold 
        }
        
        applicableThreshold?.let { threshold ->
            val announcement = buildAnnouncement(nextManeuver, distanceToManeuver, threshold)
            
            android.util.Log.d(TAG, "Announcing: $announcement")
            onAnnouncement(announcement)
            
            lastAnnouncementThreshold = threshold
        }
    }
    
    /**
     * Build announcement text based on distance and maneuver
     */
    private fun buildAnnouncement(
        maneuver: Maneuver,
        distanceToManeuver: Double,
        threshold: Double
    ): String {
        val instruction = cleanInstruction(maneuver.instruction)
        
        return when {
            // Far announcements (with distance)
            distanceToManeuver > 100 -> {
                val distanceText = formatDistance(distanceToManeuver)
                "In $distanceText, $instruction"
            }
            // Near announcements (immediate)
            else -> instruction
        }
    }
    
    /**
     * Clean up instruction text for better TTS pronunciation
     */
    private fun cleanInstruction(instruction: String): String {
        return instruction
            .replace("Turn right", "turn right")
            .replace("Turn left", "turn left")
            .replace("Continue straight", "continue straight")
            .replace("Take exit", "take exit")
            .replace("Merge", "merge")
            .replace("Keep right", "keep right")
            .replace("Keep left", "keep left")
            .replace("Make a U-turn", "make a u-turn")
            .replace("You have arrived", "you have arrived at your destination")
    }
    
    /**
     * Format distance for voice announcement
     */
    private fun formatDistance(meters: Double): String {
        return when {
            meters < 100 -> "${meters.toInt()} meters"
            meters < 1000 -> "${(meters / 100).toInt() * 100} meters"
            else -> {
                val km = meters / 1000.0
                if (km < 2) {
                    "1 kilometer"
                } else {
                    "${km.toInt()} kilometers"
                }
            }
        }
    }
    
    /**
     * Set highway mode for appropriate announcement thresholds
     */
    fun setHighwayMode(isHighway: Boolean) {
        isHighwayMode = isHighway
        android.util.Log.d(TAG, "Highway mode: $isHighway")
    }
    
    /**
     * Force immediate announcement (useful for testing)
     */
    fun announceNow(text: String) {
        android.util.Log.d(TAG, "Force announcing: $text")
        onAnnouncement(text)
    }
    
    /**
     * Reset announcer state (useful when starting new route)
     */
    fun reset() {
        lastAnnouncedManeuver = null
        lastAnnouncementThreshold = Double.MAX_VALUE
        android.util.Log.d(TAG, "Announcer state reset")
    }
}