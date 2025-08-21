package com.permitnav.ui.viewmodels

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.Permit
import com.permitnav.data.models.Route
import com.permitnav.data.models.NavigationInstruction
import com.permitnav.data.repository.PermitRepository
import com.permitnav.network.HereRoutingService
import com.permitnav.navigation.GuidanceEngine
import com.permitnav.navigation.Announcer
import com.permitnav.navigation.FlexiblePolylineDecoder
import com.permitnav.navigation.RouteGeom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import kotlin.math.*

class NavigationViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    private val database = PermitNavDatabase.getDatabase(getApplication())
    private val permitRepository = PermitRepository(database.permitDao())
    private val hereRoutingService = HereRoutingService()
    
    // Navigation guidance components
    private var guidanceEngine: GuidanceEngine? = null
    private var announcer: Announcer? = null
    private var routeGeometry: RouteGeom? = null
    
    // GPS Location Services
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private var locationCallback: LocationCallback? = null
    private var currentInstructionIndex = 0
    
    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()
    
    // Current GPS location
    private val _currentLocation = MutableStateFlow<Location?>(null)
    
    private fun loadPermitAndCalculateRouteWithAutoStart(permitId: String) {
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
                
                // Wait for current GPS location first
                val currentLoc = getCurrentLocationSuspend()
                val origin = if (currentLoc != null) {
                    android.util.Log.d("NavigationVM", "Using actual GPS location as origin: ${currentLoc.latitude}, ${currentLoc.longitude}")
                    com.permitnav.data.models.Location(
                        latitude = currentLoc.latitude,
                        longitude = currentLoc.longitude,
                        name = "Current Location"
                    )
                } else {
                    // Fallback to Indianapolis if no GPS available
                    android.util.Log.w("NavigationVM", "No GPS location available, using fallback")
                    com.permitnav.data.models.Location(
                        latitude = 39.7684,
                        longitude = -86.1581,
                        name = "Current Location"
                    )
                }
                
                val destination = if (!permit.destination.isNullOrEmpty()) {
                    // Try to geocode the permit destination
                    val geocodedDest = hereRoutingService.geocodeAddress(permit.destination)
                    geocodedDest ?: com.permitnav.data.models.Location(
                        latitude = 41.0772, // Fort Wayne, IN fallback
                        longitude = -85.1394,
                        name = permit.destination
                    )
                } else {
                    // Fallback to Chicago if no destination in permit
                    com.permitnav.data.models.Location(
                        latitude = 41.8781,
                        longitude = -87.6298,
                        name = "Chicago, IL"
                    )
                }
                
                android.util.Log.d("NavigationVM", "Calculating route for permit: ${permit.permitNumber}")
                val routeWithActions = hereRoutingService.calculateTruckRoute(origin, destination, permit)
                val route = routeWithActions.route
                val rawActions = routeWithActions.rawActions
                
                android.util.Log.d("NavigationVM", "HERE API returned ${rawActions.size} raw actions")
                
                // Validate we have actions
                if (rawActions.isEmpty()) {
                    android.util.Log.e("NavigationVM", "No actions in HERE response - cannot build route geometry")
                    throw Exception("No turn-by-turn actions received from HERE API")
                }
                
                // Build route geometry using raw HERE actions with proper offsets
                android.util.Log.d("NavigationVM", "Building RouteGeom with ${rawActions.size} raw HERE actions")
                routeGeometry = FlexiblePolylineDecoder.buildRouteGeom(route.polyline, rawActions)
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    permit = permit,
                    route = route,
                    currentInstruction = "Ready to start navigation",
                    routeDistance = "${route.distance.toInt()} mi",
                    routeDuration = formatDuration(route.duration.toInt()),
                    activeRestrictions = permit.restrictions.size
                )
                
                // Auto-start navigation after successful route calculation
                android.util.Log.d("NavigationVM", "Route calculated successfully, auto-starting navigation")
                startNavigation()
                
            } catch (e: Exception) {
                android.util.Log.e("NavigationVM", "Error calculating route", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to calculate route: ${e.message}"
                )
            }
        }
    }

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
                
                // Wait for current GPS location first
                val currentLoc = getCurrentLocationSuspend()
                val origin = if (currentLoc != null) {
                    android.util.Log.d("NavigationVM", "Using actual GPS location as origin: ${currentLoc.latitude}, ${currentLoc.longitude}")
                    com.permitnav.data.models.Location(
                        latitude = currentLoc.latitude,
                        longitude = currentLoc.longitude,
                        name = "Current Location"
                    )
                } else {
                    // Fallback to Indianapolis if no GPS available
                    android.util.Log.w("NavigationVM", "No GPS location available, using fallback")
                    com.permitnav.data.models.Location(
                        latitude = 39.7684,
                        longitude = -86.1581,
                        name = "Current Location"
                    )
                }
                
                val destination = if (!permit.destination.isNullOrEmpty()) {
                    // Try to geocode the permit destination
                    val geocodedDest = hereRoutingService.geocodeAddress(permit.destination)
                    geocodedDest ?: com.permitnav.data.models.Location(
                        latitude = 41.0772, // Fort Wayne, IN fallback
                        longitude = -85.1394,
                        name = permit.destination
                    )
                } else {
                    // Fallback to Chicago if no destination in permit
                    com.permitnav.data.models.Location(
                        latitude = 41.8781,
                        longitude = -87.6298,
                        name = "Chicago, IL"
                    )
                }
                
                android.util.Log.d("NavigationVM", "Calculating route for permit: ${permit.permitNumber}")
                val routeWithActions = hereRoutingService.calculateTruckRoute(origin, destination, permit)
                val route = routeWithActions.route
                val rawActions = routeWithActions.rawActions
                
                android.util.Log.d("NavigationVM", "HERE API returned ${rawActions.size} raw actions")
                
                // Validate we have actions
                if (rawActions.isEmpty()) {
                    android.util.Log.e("NavigationVM", "No actions in HERE response - cannot build route geometry")
                    throw Exception("No turn-by-turn actions received from HERE API")
                }
                
                // Build route geometry using raw HERE actions with proper offsets
                android.util.Log.d("NavigationVM", "Building RouteGeom with ${rawActions.size} raw HERE actions")
                routeGeometry = FlexiblePolylineDecoder.buildRouteGeom(route.polyline, rawActions)
                
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
        
        val routeGeom = routeGeometry
        if (routeGeom == null) {
            _uiState.value = _uiState.value.copy(
                error = "Route geometry not available"
            )
            return
        }
        
        // Initialize guidance engine
        guidanceEngine = GuidanceEngine(routeGeom)
        
        // Initialize announcer with neural TTS callback
        announcer = Announcer { announcement ->
            // TODO: Integrate with your neural TTS system
            android.util.Log.d("NavigationVM", "TTS Announcement: $announcement")
        }
        
        android.util.Log.d("NavigationVM", "Starting navigation with route geometry")
            
        _uiState.value = _uiState.value.copy(
            isNavigating = true,
            currentInstruction = "Starting navigation...",
            nextInstruction = "GPS tracking active"
        )
        
        startLocationTracking()
        android.util.Log.d("NavigationVM", "Navigation started with guidance engine")
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
    
    fun updateDestination(destinationAddress: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                // Store the new destination address
                _customDestination = destinationAddress
                
                // Update the permit with new destination and recalculate route
                _uiState.value.permit?.let { permit ->
                    val updatedPermit = permit.copy(
                        destination = destinationAddress
                    )
                    _uiState.value = _uiState.value.copy(permit = updatedPermit)
                    
                    // Recalculate route with new destination and auto-start navigation
                    loadPermitAndCalculateRouteWithAutoStart(permit.id)
                }
                
            } catch (e: Exception) {
                android.util.Log.e("NavigationVM", "Failed to update destination", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to update destination: ${e.message}"
                )
            }
        }
    }
    
    fun isDestinationValid(): Boolean {
        val route = _uiState.value.route ?: return true
        
        // Check if destination coordinates are valid in the route
        val dest = route.destination
        
        return dest.latitude != 0.0 && dest.longitude != 0.0 &&
               dest.latitude >= -90 && dest.latitude <= 90 &&
               dest.longitude >= -180 && dest.longitude <= 180
    }
    
    fun resumeNavigation() {
        // Resume navigation from current location but stay on the planned route
        if (_uiState.value.route != null) {
            android.util.Log.d("NavigationVM", "Resuming navigation from current location")
            
            // Find the nearest point on the route to current location
            _currentLocation.value?.let { location ->
                val nearestInstructionIndex = findNearestInstructionToLocation(location)
                currentInstructionIndex = nearestInstructionIndex
                
                val instructions = _uiState.value.route?.turnByTurnInstructions ?: emptyList()
                val currentInstruction = instructions.getOrNull(nearestInstructionIndex)?.instruction ?: "Continue on route"
                val nextInstruction = instructions.getOrNull(nearestInstructionIndex + 1)?.instruction ?: "Continue following route"
                
                _uiState.value = _uiState.value.copy(
                    isNavigating = true,
                    currentInstruction = currentInstruction,
                    nextInstruction = "Next: $nextInstruction"
                )
                
                startLocationTracking()
                android.util.Log.d("NavigationVM", "Navigation resumed from instruction #$nearestInstructionIndex")
            } ?: run {
                // No current location, start from beginning
                startNavigation()
            }
        }
    }
    
    private fun findNearestInstructionToLocation(location: Location): Int {
        val instructions = _uiState.value.route?.turnByTurnInstructions ?: return 0
        var nearestIndex = 0
        var minDistance = Double.MAX_VALUE
        
        instructions.forEachIndexed { index, instruction ->
            val distance = calculateDistance(
                location.latitude,
                location.longitude,
                instruction.location.latitude,
                instruction.location.longitude
            )
            
            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = index
            }
        }
        
        return nearestIndex
    }
    
    // Store custom destination if user entered one
    private var _customDestination: String? = null
    
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            getApplication(),
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
    
    private suspend fun getCurrentLocation() {
        if (!hasLocationPermission()) {
            android.util.Log.w("NavigationVM", "No location permission")
            return
        }
        
        try {
            val locationTask = fusedLocationClient.lastLocation
            locationTask.addOnSuccessListener { location ->
                if (location != null) {
                    android.util.Log.d("NavigationVM", "Got current location: ${location.latitude}, ${location.longitude}")
                    _currentLocation.value = location
                } else {
                    android.util.Log.w("NavigationVM", "Location is null, requesting fresh location")
                    // Request a fresh location if last known location is null
                    requestFreshLocation()
                }
            }.addOnFailureListener { e ->
                android.util.Log.e("NavigationVM", "Failed to get current location", e)
            }
        } catch (e: SecurityException) {
            android.util.Log.e("NavigationVM", "Location permission denied", e)
        }
    }
    
    /**
     * Suspend function that waits for GPS location
     */
    private suspend fun getCurrentLocationSuspend(): Location? {
        if (!hasLocationPermission()) {
            android.util.Log.w("NavigationVM", "No location permission")
            return null
        }
        
        return withContext(Dispatchers.IO) {
            try {
                // First try to get last known location
                val lastLocation = fusedLocationClient.lastLocation.await()
                if (lastLocation != null) {
                    android.util.Log.d("NavigationVM", "Got last known location: ${lastLocation.latitude}, ${lastLocation.longitude}")
                    _currentLocation.value = lastLocation
                    return@withContext lastLocation
                }
                
                // If no last known location, request fresh location
                android.util.Log.d("NavigationVM", "No last known location, requesting fresh GPS fix...")
                return@withContext requestFreshLocationSuspend()
                
            } catch (e: SecurityException) {
                android.util.Log.e("NavigationVM", "Location permission denied", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Request fresh GPS location and wait for result
     */
    private suspend fun requestFreshLocationSuspend(): Location? {
        return suspendCancellableCoroutine { continuation ->
            if (!hasLocationPermission()) {
                continuation.resume(null, onCancellation = null)
                return@suspendCancellableCoroutine
            }
            
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 1000L
            ).apply {
                setMaxUpdates(1) // Only get one location update
            }.build()
            
            val oneTimeCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        android.util.Log.d("NavigationVM", "Fresh GPS location obtained: ${location.latitude}, ${location.longitude}")
                        _currentLocation.value = location
                        if (continuation.isActive) {
                            continuation.resume(location, onCancellation = null)
                        }
                    } ?: run {
                        android.util.Log.w("NavigationVM", "Fresh location result was null")
                        if (continuation.isActive) {
                            continuation.resume(null, onCancellation = null)
                        }
                    }
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
            
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    oneTimeCallback,
                    Looper.getMainLooper()
                )
                
                // Set timeout for GPS acquisition
                continuation.invokeOnCancellation {
                    fusedLocationClient.removeLocationUpdates(oneTimeCallback)
                }
                
            } catch (e: SecurityException) {
                android.util.Log.e("NavigationVM", "Location permission denied for fresh location", e)
                if (continuation.isActive) {
                    continuation.resume(null, onCancellation = null)
                }
            }
        }
    }
    
    private fun requestFreshLocation() {
        if (!hasLocationPermission()) return
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L
        ).apply {
            setMaxUpdates(1) // Only get one location update
        }.build()
        
        val oneTimeCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    android.util.Log.d("NavigationVM", "Fresh location obtained: ${location.latitude}, ${location.longitude}")
                    _currentLocation.value = location
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                oneTimeCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e("NavigationVM", "Location permission denied for fresh location", e)
        }
    }
    
    private fun updateNavigationWithLocation(location: Location) {
        val engine = guidanceEngine ?: return
        val announcer = announcer ?: return
        
        // Process location through guidance engine
        val tick = engine.onLocation(location)
        
        // Update announcements
        announcer.onTick(tick)
        
        // Handle rerouting if needed
        if (tick.shouldReroute) {
            android.util.Log.w("NavigationVM", "Reroute needed - driver is off route")
            // TODO: Trigger reroute through HERE API
        }
        
        // Update UI with current guidance
        val currentInstruction = tick.nextManeuver?.instruction ?: "Continue on route"
        val distanceText = formatDistance(tick.distanceToManeuver)
        
        _uiState.value = _uiState.value.copy(
            currentInstruction = "$currentInstruction${if (distanceText.isNotEmpty()) " in $distanceText" else ""}",
            nextInstruction = "${(tick.remainingMeters * 3.28084 / 5280.0).format(1)} mi remaining"
        )
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
    
    // Helper extension function for formatting
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
    
    private fun formatDistance(meters: Double): String {
        val feet = meters * 3.28084
        return when {
            feet < 300 -> "${feet.toInt()} ft"
            feet < 5280 -> "${(feet / 100).toInt() * 100} ft" 
            else -> "${(feet / 5280.0).format(1)} mi"
        }
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