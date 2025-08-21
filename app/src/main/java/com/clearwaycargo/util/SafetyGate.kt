package com.clearwaycargo.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SafetyGate determines hands-free mode based on vehicle speed
 * Enables voice-first interaction when driving ≥5 mph
 */
class SafetyGate(private val context: Context) {
    
    companion object {
        private const val TAG = "SafetyGate"
        private const val HANDS_FREE_SPEED_THRESHOLD = 5.0f // mph
        private const val LOCATION_UPDATE_INTERVAL = 5000L // 5 seconds
        private const val LOCATION_FASTEST_INTERVAL = 2000L // 2 seconds
        
        // Convert m/s to mph
        private const val MS_TO_MPH = 2.23694f
    }
    
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null
    
    private val _isHandsFree = MutableStateFlow(false)
    val isHandsFree: StateFlow<Boolean> = _isHandsFree.asStateFlow()
    
    private val _currentSpeed = MutableStateFlow(0.0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()
    
    private var isMonitoring = false
    
    /**
     * Start monitoring location for speed detection
     */
    fun startMonitoring() {
        if (isMonitoring) return
        
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted, cannot monitor speed")
            return
        }
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            setMaxUpdates(Int.MAX_VALUE)
        }.build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateSpeed(location)
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                null
            )
            isMonitoring = true
            Log.d(TAG, "Started location monitoring for hands-free detection")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting location updates", e)
        }
    }
    
    /**
     * Stop monitoring location
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
        }
        
        locationCallback = null
        isMonitoring = false
        _isHandsFree.value = false
        _currentSpeed.value = 0.0f
        
        Log.d(TAG, "Stopped location monitoring")
    }
    
    /**
     * Get current hands-free status
     */
    fun isHandsFree(): Boolean = _isHandsFree.value
    
    /**
     * Get current speed in mph
     */
    fun getCurrentSpeedMph(): Float = _currentSpeed.value
    
    /**
     * Force hands-free mode (for testing or manual override)
     */
    fun setHandsFreeOverride(handsFree: Boolean) {
        _isHandsFree.value = handsFree
        Log.d(TAG, "Hands-free mode override: $handsFree")
    }
    
    /**
     * Update speed from location and determine hands-free status
     */
    private fun updateSpeed(location: Location) {
        val speedMs = if (location.hasSpeed()) location.speed else 0.0f
        val speedMph = speedMs * MS_TO_MPH
        
        _currentSpeed.value = speedMph
        
        val wasHandsFree = _isHandsFree.value
        val nowHandsFree = speedMph >= HANDS_FREE_SPEED_THRESHOLD
        
        _isHandsFree.value = nowHandsFree
        
        if (wasHandsFree != nowHandsFree) {
            Log.d(TAG, "Hands-free mode changed: $nowHandsFree (speed: ${speedMph.toInt()} mph)")
        }
    }
    
    /**
     * Check if location permission is granted
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Safety checks for risky actions during hands-free mode
     */
    object HandsFreeSafety {
        
        /**
         * Check if action requires confirmation while hands-free
         */
        fun requiresConfirmation(action: HandsFreeAction): Boolean {
            return when (action) {
                HandsFreeAction.DIAL_DOT -> true
                HandsFreeAction.START_NAVIGATION -> true
                HandsFreeAction.SEND_MESSAGE -> true
                HandsFreeAction.VIEW_PERMIT_DETAILS -> false
                HandsFreeAction.COMPLIANCE_CHECK -> false
            }
        }
        
        /**
         * Get voice confirmation prompt for action
         */
        fun getConfirmationPrompt(action: HandsFreeAction): String {
            return when (action) {
                HandsFreeAction.DIAL_DOT -> "Call the DOT office now?"
                HandsFreeAction.START_NAVIGATION -> "Start navigation to your destination?"
                HandsFreeAction.SEND_MESSAGE -> "Send this message?"
                else -> "Continue with this action?"
            }
        }
    }
    
    /**
     * Actions that may require confirmation in hands-free mode
     */
    enum class HandsFreeAction {
        DIAL_DOT,
        START_NAVIGATION,
        SEND_MESSAGE,
        VIEW_PERMIT_DETAILS,
        COMPLIANCE_CHECK
    }
}