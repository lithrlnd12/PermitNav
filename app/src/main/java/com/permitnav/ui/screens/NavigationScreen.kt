package com.permitnav.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.permitnav.services.HazardDetectionService
import com.permitnav.data.models.TruckHazard
import com.permitnav.data.models.HazardType
import com.permitnav.data.models.HazardSeverity
import com.permitnav.data.models.ManeuverType
import com.permitnav.navigation.FlexiblePolylineDecoder
import com.permitnav.R
import com.permitnav.ui.theme.*
import com.permitnav.ui.viewmodels.NavigationViewModel
import com.permitnav.ui.viewmodels.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun NavigationScreen(
    permitId: String,
    onNavigateBack: () -> Unit,
    onNavigateToChat: ((String) -> Unit)? = null,
    onNavigateToAuth: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: NavigationViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    var showChatOverlay by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val authViewModel: AuthViewModel = viewModel()
    
    // State for destination input dialog
    var showDestinationDialog by remember { mutableStateOf(false) }
    var destinationInput by remember { mutableStateOf("") }
    
    // Location permission state
    val locationPermissionState = rememberPermissionState(
        permission = Manifest.permission.ACCESS_FINE_LOCATION
    )
    
    // Load permit and calculate route when screen loads
    LaunchedEffect(permitId) {
        android.util.Log.d("NavigationScreen", "Loading permit and calculating route for: $permitId")
        
        // Request location permission first if not granted
        if (!locationPermissionState.status.isGranted) {
            locationPermissionState.launchPermissionRequest()
        }
        
        viewModel.loadPermitAndCalculateRoute(permitId)
    }
    
    // Pre-populate destination input with permit destination when available
    LaunchedEffect(uiState.permit?.destination) {
        uiState.permit?.destination?.let { permitDestination ->
            if (destinationInput.isEmpty() && permitDestination.isNotBlank()) {
                destinationInput = permitDestination
            }
        }
    }
    
    // Recalculate route with real location when permission is granted
    LaunchedEffect(locationPermissionState.status.isGranted) {
        if (locationPermissionState.status.isGranted && uiState.route != null) {
            // Recalculate route with actual GPS location
            android.util.Log.d("NavigationScreen", "Location permission granted, recalculating route with GPS")
            viewModel.loadPermitAndCalculateRoute(permitId)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Directions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryOrange,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                actions = {
                    // Chat button - opens overlay without leaving navigation
                    IconButton(onClick = { showChatOverlay = true }) {
                        Icon(
                            Icons.Default.Chat,
                            contentDescription = "AI Cargo Assistant",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Navigation Settings",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = PrimaryOrange)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Calculating route...")
                        }
                    }
                }
                uiState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier.padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Route Error",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    uiState.error ?: "Unknown error",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
                else -> {
                    // Always show the map - it should be visible in both preview and navigation modes
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.LightGray) // Gray background so we can see if map loads
                    ) {
                        // Debugging: show a simple text overlay to ensure the container is working
                        if (uiState.route == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Calculating route...\nMap will appear here",
                                    textAlign = TextAlign.Center,
                                    color = Color.DarkGray
                                )
                            }
                        }
                        
                        GoogleMapView(
                            route = uiState.route,
                            isNavigating = uiState.isNavigating,
                            hazards = emptyList() // Will be populated from HazardDetectionService
                        )
                        
                        if (uiState.isNavigating) {
                            // During navigation: Show enhanced navigation card with real maneuver info
                            EnhancedNavigationCard(
                                currentInstruction = uiState.currentInstruction,
                                nextInstruction = uiState.nextInstruction,
                                remainingDistance = uiState.routeDistance,
                                estimatedTime = uiState.routeDuration,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp)
                            )
                            
                            Button(
                                onClick = { viewModel.stopNavigation() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ErrorRed
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("End Directions", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            // Before navigation: Show full preview and info cards overlaid on map
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp)
                            ) {
                                // Small route info at top
                                RouteInfoCard(
                                    distance = uiState.routeDistance,
                                    duration = uiState.routeDuration,
                                    restrictions = uiState.activeRestrictions,
                                    restrictionsList = uiState.permit?.restrictions ?: emptyList()
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                RoutePreviewCard(
                                    route = uiState.route,
                                    isLoading = uiState.isLoading
                                )
                            }
                            
                            // Check if we have a route and previously stopped navigation
                            val hasStoppedNavigation = remember(uiState) {
                                uiState.route != null && 
                                uiState.currentInstruction == "Navigation stopped"
                            }
                            
                            // Navigation control buttons at bottom
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp),
                                horizontalArrangement = if (hasStoppedNavigation) Arrangement.spacedBy(8.dp) else Arrangement.Center
                            ) {
                                // Resume button if navigation was stopped
                                if (hasStoppedNavigation) {
                                    Button(
                                        onClick = { viewModel.resumeNavigation() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(56.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SuccessGreen
                                        )
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Resume",
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                }
                                
                                // Start/Restart Navigation button
                                Button(
                                    onClick = { 
                                        if (!locationPermissionState.status.isGranted) {
                                            locationPermissionState.launchPermissionRequest()
                                        } else {
                                            // Always show destination input dialog for user-friendly experience
                                            showDestinationDialog = true
                                        }
                                    },
                                    modifier = Modifier
                                        .then(if (hasStoppedNavigation) Modifier.weight(1f) else Modifier.fillMaxWidth())
                                        .height(56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (hasStoppedNavigation) SecondaryBlue else PrimaryOrange
                                    ),
                                    enabled = uiState.route != null
                                ) {
                                    Icon(Icons.Default.Navigation, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when {
                                            !locationPermissionState.status.isGranted -> "Grant Location"
                                            hasStoppedNavigation -> "Restart"
                                            else -> "Enter Destination"
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Settings Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SecondaryBlue
                )
            },
            text = {
                Column {
                    Text(
                        "App Version: 1.0.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Would you like to sign out of your account?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Black
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        authViewModel.signOut()
                        showSettingsDialog = false
                        onNavigateToAuth()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorRed
                    )
                ) {
                    Text("Sign Out", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSettingsDialog = false }
                ) {
                    Text("Cancel", color = SecondaryBlue)
                }
            }
        )
    }
    
    // Chat overlay - doesn't leave navigation screen
    if (showChatOverlay) {
        ChatOverlay(
            permitId = permitId,
            onDismiss = { showChatOverlay = false }
        )
    }
    
    // Destination input dialog
    if (showDestinationDialog) {
        AlertDialog(
            onDismissRequest = { showDestinationDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = PrimaryOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Enter Destination",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        "Enter your exact destination address. The route will be planned from your current GPS location to this destination while maintaining permit compliance:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = destinationInput,
                        onValueChange = { destinationInput = it },
                        label = { Text("Destination address") },
                        placeholder = { Text("e.g., 123 Main St, Columbus, OH 43215") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        supportingText = {
                            Text(
                                "Tip: Include city and state for best results",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (destinationInput.isNotBlank()) {
                            viewModel.updateDestination(destinationInput)
                            showDestinationDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryOrange
                    ),
                    enabled = destinationInput.isNotBlank()
                ) {
                    Text("Start Navigation")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDestinationDialog = false }
                ) {
                    Text("Cancel", color = SecondaryBlue)
                }
            }
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun GoogleMapView(
    route: com.permitnav.data.models.Route?,
    isNavigating: Boolean,
    hazards: List<TruckHazard> = emptyList()
) {
    // Camera position state - start with route center if available
    val cameraPositionState = rememberCameraPositionState {
        val initialPosition = route?.let {
            // Center between origin and destination
            val centerLat = (it.origin.latitude + it.destination.latitude) / 2
            val centerLng = (it.origin.longitude + it.destination.longitude) / 2
            LatLng(centerLat, centerLng)
        } ?: LatLng(39.7684, -86.1581) // Indianapolis fallback
        
        position = CameraPosition.fromLatLngZoom(initialPosition, 10f)
    }
    
    // Get location permission state for maps
    val locationPermissionState = rememberPermissionState(
        permission = Manifest.permission.ACCESS_FINE_LOCATION
    )
    
    // Update camera when route changes
    LaunchedEffect(route) {
        route?.let {
            android.util.Log.d("NavigationScreen", "Moving camera to route bounds: ${it.origin.latitude},${it.origin.longitude} -> ${it.destination.latitude},${it.destination.longitude}")
            
            try {
                // Build bounds that include all route points
                val boundsBuilder = LatLngBounds.builder()
                boundsBuilder.include(LatLng(it.origin.latitude, it.origin.longitude))
                boundsBuilder.include(LatLng(it.destination.latitude, it.destination.longitude))
                
                // Add polyline points to bounds if available
                it.polyline?.let { polylineString ->
                    val decodedPoints = decodeHerePolylineToLatLng(polylineString)
                    decodedPoints.forEach { point ->
                        boundsBuilder.include(point)
                    }
                }
                
                val bounds = boundsBuilder.build()
                cameraPositionState.move(
                    CameraUpdateFactory.newLatLngBounds(bounds, 100)
                )
            } catch (e: Exception) {
                android.util.Log.e("NavigationScreen", "Error setting camera bounds", e)
                // Fallback to showing origin with good zoom
                android.util.Log.d("NavigationScreen", "Using fallback camera position at origin")
                cameraPositionState.move(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(it.origin.latitude, it.origin.longitude), 
                        14f
                    )
                )
            }
        }
    }
    
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            mapType = MapType.NORMAL,
            isTrafficEnabled = true,
            isMyLocationEnabled = locationPermissionState.status.isGranted
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = true,
            myLocationButtonEnabled = locationPermissionState.status.isGranted,
            mapToolbarEnabled = true,
            compassEnabled = true,
            zoomGesturesEnabled = true,
            scrollGesturesEnabled = true,
            tiltGesturesEnabled = true,
            rotationGesturesEnabled = true
        ),
        onMapLoaded = {
            android.util.Log.d("NavigationScreen", "✅ Google Maps loaded successfully!")
            android.util.Log.d("NavigationScreen", "Current camera position: ${cameraPositionState.position}")
        },
        onMapClick = { latLng ->
            android.util.Log.d("NavigationScreen", "Map clicked at: ${latLng.latitude}, ${latLng.longitude}")
        }
    ) {
        // Add route if available
        route?.let { r ->
            android.util.Log.d("NavigationScreen", "Rendering route on map - Origin: ${r.origin.latitude},${r.origin.longitude}, Dest: ${r.destination.latitude},${r.destination.longitude}")
            
            // Start marker
            Marker(
                state = rememberMarkerState(
                    position = LatLng(r.origin.latitude, r.origin.longitude)
                ),
                title = "Start",
                snippet = r.origin.name ?: "Origin"
            )
            
            // End marker - use a nearby road if coordinates are off-road
            Marker(
                state = rememberMarkerState(
                    position = LatLng(r.destination.latitude, r.destination.longitude)
                ),
                title = "Destination", 
                snippet = r.destination.name ?: "Final destination coordinates"
            )
            
            // Route polyline decoded from HERE flexible polyline
            r.polyline?.let { polylineString ->
                val decodedPoints = try {
                    FlexiblePolylineDecoder.decodePolyline(polylineString)
                } catch (e: Exception) {
                    android.util.Log.e("NavigationScreen", "Failed to decode polyline", e)
                    emptyList()
                }
                
                if (decodedPoints.isNotEmpty()) {
                    Polyline(
                        points = decodedPoints,
                        color = PrimaryOrange,
                        width = 12f,
                        geodesic = true
                    )
                    android.util.Log.d("NavigationScreen", "Rendered polyline with ${decodedPoints.size} points")
                } else {
                    // Fallback: Draw straight line
                    Polyline(
                        points = listOf(
                            LatLng(r.origin.latitude, r.origin.longitude),
                            LatLng(r.destination.latitude, r.destination.longitude)
                        ),
                        color = PrimaryOrange.copy(alpha = 0.7f),
                        width = 8f,
                        pattern = listOf(Dash(10f), Gap(10f)),
                        geodesic = true
                    )
                }
            }
        }
        
        // Add hazard markers
        hazards.forEach { hazard ->
            val markerColor = when (hazard.severity) {
                HazardSeverity.CRITICAL -> BitmapDescriptorFactory.HUE_RED
                HazardSeverity.HIGH -> BitmapDescriptorFactory.HUE_ORANGE
                HazardSeverity.MEDIUM -> BitmapDescriptorFactory.HUE_YELLOW
                HazardSeverity.LOW -> BitmapDescriptorFactory.HUE_GREEN
                HazardSeverity.INFO -> BitmapDescriptorFactory.HUE_BLUE
            }
            
            val icon = when (hazard.type) {
                HazardType.LOW_CLEARANCE -> "🌉"
                HazardType.WEIGHT_RESTRICTION -> "⚖️"
                HazardType.CONSTRUCTION -> "🚧"
                HazardType.WEIGH_STATION -> "🏗️"
                HazardType.TRUCK_PARKING -> "🅿️"
                HazardType.FUEL_STOP -> "⛽"
                HazardType.HIGH_WIND -> "💨"
                HazardType.ACCIDENT -> "⚠️"
                else -> "⚠️"
            }
            
            Marker(
                state = rememberMarkerState(position = hazard.location),
                title = "$icon ${hazard.title}",
                snippet = hazard.description,
                icon = BitmapDescriptorFactory.defaultMarker(markerColor),
                onClick = { marker ->
                    marker.showInfoWindow()
                    true
                }
            )
        }
    }
}

@Composable 
fun ChatOverlay(
    permitId: String,
    onDismiss: () -> Unit
) {
    val chatViewModel: com.permitnav.ui.viewmodels.ChatViewModel = viewModel()
    val chatState by chatViewModel.uiState.collectAsState()
    var messageText by remember { mutableStateOf("") }
    
    // Initialize chat with permit context when overlay opens
    LaunchedEffect(permitId) {
        chatViewModel.loadPermitContext(permitId)
    }
    
    // Full screen overlay with chat - stays in navigation
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SecondaryBlue.copy(alpha = 0.8f))
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = SecondaryBlue)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header with X button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(SecondaryBlue.copy(alpha = 0.1f)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "AI Cargo Assistant",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close chat",
                            tint = Color.White
                        )
                    }
                }
                
                Divider()
                
                // Messages list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    // Welcome message if no messages yet
                    if (chatState.messages.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = SecondaryBlue.copy(alpha = 0.1f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.SmartToy,
                                            contentDescription = null,
                                            tint = SecondaryBlue,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "AI Assistant",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = SecondaryBlue
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Hi! I'm your AI cargo assistant. I can help with questions about permit $permitId, routing restrictions, escort requirements, and more. What would you like to know?",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                    
                    // Chat messages
                    items(chatState.messages) { message ->
                        ChatMessageItem(
                            message = message,
                            isFromUser = message.isFromUser
                        )
                    }
                    
                    // Typing indicator
                    if (chatState.isLoading) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.Gray.copy(alpha = 0.1f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = SecondaryBlue
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "AI is thinking...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                Divider()
                
                // Message input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(SecondaryBlue),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Ask about this permit...",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        },
                        enabled = !chatState.isLoading,
                        maxLines = 3,
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            if (messageText.trim().isNotEmpty()) {
                                chatViewModel.sendMessage(messageText.trim())
                                messageText = ""
                            }
                        },
                        enabled = messageText.trim().isNotEmpty() && !chatState.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryOrange
                        )
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: com.permitnav.data.models.ChatMessage,
    isFromUser: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isFromUser) {
            Spacer(modifier = Modifier.width(0.dp))
        }
        
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isFromUser) PrimaryOrange else Color.White.copy(alpha = 0.1f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (!isFromUser) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = SecondaryBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "AI Cargo Assistant",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFromUser) Color.White else Color.White
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFromUser) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.7f)
                )
            }
        }
        
        if (isFromUser) {
            Spacer(modifier = Modifier.width(0.dp))
        }
    }
}

// HERE polyline decoder for Google Maps
private fun decodeHerePolylineToLatLng(polyline: String): List<LatLng> {
    return try {
        // HERE uses Flexible Polyline encoding
        // For MVP, we'll implement a basic decoder
        // Production should use HERE Flexible Polyline library
        val points = mutableListOf<LatLng>()
        
        if (polyline.isEmpty()) return points
        
        var index = 0
        var lat = 0
        var lng = 0
        
        while (index < polyline.length) {
            var shift = 0
            var result = 0
            
            // Decode latitude
            do {
                if (index >= polyline.length) break
                val b = polyline[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < polyline.length)
            
            val deltaLat = if ((result and 1) != 0) -(result shr 1) else (result shr 1)
            lat += deltaLat
            
            shift = 0
            result = 0
            
            // Decode longitude
            do {
                if (index >= polyline.length) break
                val b = polyline[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < polyline.length)
            
            val deltaLng = if ((result and 1) != 0) -(result shr 1) else (result shr 1)
            lng += deltaLng
            
            points.add(LatLng(lat / 1E5, lng / 1E5))
        }
        
        android.util.Log.d("NavigationScreen", "Decoded ${points.size} polyline points")
        points
    } catch (e: Exception) {
        android.util.Log.e("NavigationScreen", "Failed to decode HERE polyline", e)
        // Fallback: return empty list, will show straight line between markers
        emptyList()
    }
}


@Composable
fun NavigationCard(
    currentInstruction: String,
    nextInstruction: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrimaryOrange),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        currentInstruction,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        lineHeight = MaterialTheme.typography.headlineSmall.lineHeight
                    )
                    if (nextInstruction.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Then: $nextInstruction",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedNavigationCard(
    currentInstruction: String,
    nextInstruction: String,
    remainingDistance: String,
    estimatedTime: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Main navigation instruction
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrimaryOrange),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        getManeuverIcon(currentInstruction),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        currentInstruction,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        color = Color.Black
                    )
                }
            }
            
            // Distance and time info
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Route,
                        contentDescription = null,
                        tint = SecondaryBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        remainingDistance,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondaryBlue
                    )
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        estimatedTime,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SuccessGreen
                    )
                }
            }
            
            // Next instruction preview
            if (nextInstruction.isNotEmpty() && nextInstruction != "Continue following route") {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color.Gray.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SecondaryBlue.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            getManeuverIcon(nextInstruction),
                            contentDescription = null,
                            tint = SecondaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Text(
                        "Then: ${nextInstruction.removePrefix("Next: ")}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = SecondaryBlue,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Get appropriate icon for maneuver based on instruction text
 */
private fun getManeuverIcon(instruction: String): androidx.compose.ui.graphics.vector.ImageVector {
    val lowerInstruction = instruction.lowercase()
    
    return when {
        lowerInstruction.contains("turn right") || lowerInstruction.contains("right turn") -> Icons.Default.TurnRight
        lowerInstruction.contains("turn left") || lowerInstruction.contains("left turn") -> Icons.Default.TurnLeft
        lowerInstruction.contains("straight") || lowerInstruction.contains("continue") -> Icons.Default.Straight
        lowerInstruction.contains("merge") -> Icons.Default.Merge
        lowerInstruction.contains("exit") || lowerInstruction.contains("ramp") -> Icons.Default.ExitToApp
        lowerInstruction.contains("roundabout") -> Icons.Default.RotateRight
        lowerInstruction.contains("u-turn") || lowerInstruction.contains("uturn") -> Icons.Default.UTurnLeft
        lowerInstruction.contains("keep right") -> Icons.Default.TrendingUp
        lowerInstruction.contains("keep left") -> Icons.Default.TrendingDown
        lowerInstruction.contains("destination") || lowerInstruction.contains("arrive") -> Icons.Default.Flag
        lowerInstruction.contains("start") || lowerInstruction.contains("depart") -> Icons.Default.PlayArrow
        else -> Icons.Default.Navigation
    }
}

@Composable
fun RouteInfoCard(
    distance: String = "-- mi",
    duration: String = "-- min", 
    restrictions: Int = 0,
    restrictionsList: List<String> = emptyList()
) {
    var showRestrictionsDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RouteInfoItem(
                icon = Icons.Default.Timer,
                label = "Duration",
                value = duration,
                color = SecondaryBlue
            )
            
            Divider(
                modifier = Modifier
                    .height(40.dp)
                    .width(1.dp),
                color = Color.Gray.copy(alpha = 0.3f)
            )
            
            RouteInfoItem(
                icon = Icons.Default.Route,
                label = "Distance",
                value = distance,
                color = PrimaryOrange
            )
            
            if (restrictions > 0) {
                Divider(
                    modifier = Modifier
                        .height(60.dp)
                        .width(1.dp),
                    color = Color.Gray.copy(alpha = 0.3f)
                )
                
                RouteInfoItem(
                    icon = Icons.Default.Warning,
                    label = "Restrictions",
                    value = "$restrictions",
                    color = WarningYellow,
                    onClick = { showRestrictionsDialog = true }
                )
            }
        }
    }
    
    // Restrictions Dialog
    if (showRestrictionsDialog) {
        AlertDialog(
            onDismissRequest = { showRestrictionsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = WarningYellow,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Route Restrictions ($restrictions)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                LazyColumn {
                    if (restrictionsList.isEmpty()) {
                        item {
                            Text(
                                "No specific restrictions found for this route.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextSecondary
                            )
                        }
                    } else {
                        items(restrictionsList) { restriction ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = WarningYellow.copy(alpha = 0.1f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = WarningYellow,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        restriction,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showRestrictionsDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryOrange
                    )
                ) {
                    Text("Got it")
                }
            }
        )
    }
}

@Composable
fun RouteInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier.clickable { onClick() }
    } else {
        Modifier
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Black.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RoutePreviewCard(
    route: com.permitnav.data.models.Route?,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            when {
                isLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = PrimaryOrange,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            "Calculating route...",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                route != null -> {
                    // Compact route header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Route,
                            contentDescription = null,
                            tint = PrimaryOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${route.origin.name ?: "Start"} → ${route.destination.name ?: "End"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryOrange,
                                maxLines = 1
                            )
                            Text(
                                "${route.turnByTurnInstructions.size} turns",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    
                    if (route.turnByTurnInstructions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = Color.Gray.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            "Turn-by-Turn Instructions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SecondaryBlue
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 500.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(route.turnByTurnInstructions.size) { index ->
                                val instruction = route.turnByTurnInstructions[index]
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (index == 0) SuccessGreen.copy(alpha = 0.1f) 
                                                        else Color.Gray.copy(alpha = 0.05f)
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (index == 0) SuccessGreen else PrimaryOrange
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "${index + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            instruction.instruction,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (index == 0) SuccessGreen else Color.Black,
                                            modifier = Modifier.weight(1f),
                                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        "Ready to calculate route",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}