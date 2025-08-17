package com.permitnav.ui.viewmodels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.Permit
import com.permitnav.data.models.Route
import com.permitnav.data.models.NavigationInstruction
import com.permitnav.data.repository.PermitRepository
import com.permitnav.network.HereRoutingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.*

class NavigationViewModel(
    private val context: Context
) : ViewModel() {
    
    private val database = PermitNavDatabase.getDatabase(context)
    private val permitRepository = PermitRepository(database.permitDao())
    private val hereRoutingService = HereRoutingService()
    
    // GPS Location Services
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null
    private var currentInstructionIndex = 0
    
    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()
    
    fun loadPermitAndCalculateRoute(permitId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                val permit = permitRepository.getPermitById(permitId)
                if (permit == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Permit not found"
                    )
                    return@launch
                }
                
                // For demo purposes, use placeholder origin and destination
                // In a real app, you'd get the current location and allow destination input
                val origin = com.permitnav.data.models.Location(
                    latitude = 39.7684, // Indianapolis
                    longitude = -86.1581
                )
                val destination = com.permitnav.data.models.Location(
                    latitude = 41.8781, // Chicago
                    longitude = -87.6298
                )
                
                android.util.Log.d("NavigationVM", "Calculating route for permit: ${permit.permitNumber}")
                val route = hereRoutingService.calculateTruckRoute(origin, destination, permit)
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    permit = permit,
                    route = route,
                    currentInstruction = "Ready to start navigation",
                    routeDistance = "${route.distance.toInt()} mi",
                    routeDuration = formatDuration(route.duration.toInt()),
                    activeRestrictions = permit.restrictions.size
                )
                
            } catch (e: Exception) {
                android.util.Log.e("NavigationVM", "Error calculating route", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to calculate route: ${e.message}"
                )
            }
        }
    }
    
    fun startNavigation() {
        if (!hasLocationPermission()) {
            _uiState.value = _uiState.value.copy(
                error = "Location permission required for navigation"
            )
            return
        }
        
        val route = _uiState.value.route
        val instructions = route?.turnByTurnInstructions ?: emptyList()
        
        android.util.Log.d("NavigationVM", "Starting navigation with ${instructions.size} instructions")
        instructions.take(3).forEachIndexed { index, instruction ->
            android.util.Log.d("NavigationVM", "Instruction $index: ${instruction.instruction}")
        }
        
        val firstInstruction = instructions.firstOrNull()?.instruction ?: "Follow the route"
        val nextInstruction = instructions.getOrNull(1)?.instruction ?: "Continue following route"
        
        android.util.Log.d("NavigationVM", "Current: $firstInstruction")
        android.util.Log.d("NavigationVM", "Next: $nextInstruction")
            
        _uiState.value = _uiState.value.copy(
            isNavigating = true,
            currentInstruction = firstInstruction,
            nextInstruction = "Next: $nextInstruction"
        )
        
        startLocationTracking()
        android.util.Log.d("NavigationVM", "Navigation started with GPS tracking")
    }
    
    fun stopNavigation() {
        stopLocationTracking()
        _uiState.value = _uiState.value.copy(
            isNavigating = false,
            currentInstruction = "Navigation stopped",
            nextInstruction = "Ready to navigate"
        )
        
        android.util.Log.d("NavigationVM", "Navigation stopped")
    }
    
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun startLocationTracking() {
        if (!hasLocationPermission()) return
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 2000L // Update every 2 seconds
        ).apply {
            setMinUpdateDistanceMeters(5f) // Update every 5 meters
            setMinUpdateIntervalMillis(1000L) // Minimum 1 second between updates
        }.build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateNavigationWithLocation(location)
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e("NavigationVM", "Location permission denied", e)
        }
    }
    
    private fun stopLocationTracking() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
        }
        locationCallback = null
    }
    
    private fun updateNavigationWithLocation(location: Location) {
        val route = _uiState.value.route ?: return
        val instructions = route.turnByTurnInstructions
        
        if (instructions.isEmpty()) return
        
        // Find the closest instruction to current location
        val newInstructionIndex = findClosestInstructionIndex(location, instructions)
        
        if (newInstructionIndex != currentInstructionIndex) {
            currentInstructionIndex = newInstructionIndex
            
            val currentInstruction = instructions.getOrNull(currentInstructionIndex)?.instruction 
                ?: "Continue following route"
            val nextInstruction = instructions.getOrNull(currentInstructionIndex + 1)?.instruction
                ?: "You will arrive at your destination"
                
            _uiState.value = _uiState.value.copy(
                currentInstruction = currentInstruction,
                nextInstruction = "Next: $nextInstruction"
            )
            
            android.util.Log.d("NavigationVM", "Updated to instruction $currentInstructionIndex: $currentInstruction")
        }
    }
    
    private fun findClosestInstructionIndex(currentLocation: Location, instructions: List<NavigationInstruction>): Int {
        var closestIndex = currentInstructionIndex
        var minDistance = Double.MAX_VALUE
        
        // Check a few instructions around the current one
        val startIndex = maxOf(0, currentInstructionIndex - 1)
        val endIndex = minOf(instructions.size - 1, currentInstructionIndex + 3)
        
        for (i in startIndex..endIndex) {
            val instruction = instructions[i]
            val distance = calculateDistance(
                currentLocation.latitude,
                currentLocation.longitude,
                instruction.location.latitude,
                instruction.location.longitude
            )
            
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }
        
        // Only advance if we're close to the next instruction (within 50 meters)
        if (closestIndex > currentInstructionIndex && minDistance < 0.05) { // 50 meters
            return closestIndex
        }
        
        return currentInstructionIndex
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371.0 // kilometers
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadius * c // Distance in kilometers
    }
    
    override fun onCleared() {
        super.onCleared()
        stopLocationTracking()
        hereRoutingService.close()
    }
    
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }
}

data class NavigationUiState(
    val isLoading: Boolean = false,
    val isNavigating: Boolean = false,
    val permit: Permit? = null,
    val route: Route? = null,
    val currentInstruction: String = "Loading route...",
    val nextInstruction: String = "Preparing directions...",
    val routeDistance: String = "-- mi",
    val routeDuration: String = "-- min",
    val activeRestrictions: Int = 0,
    val error: String? = null
)