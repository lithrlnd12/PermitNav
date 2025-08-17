package com.permitnav.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.GeoPolyline
import com.here.sdk.mapview.LineCap
import com.here.sdk.mapview.MapImage
import com.here.sdk.mapview.MapImageFactory
import com.here.sdk.mapview.MapMarker
import com.here.sdk.mapview.MapMeasure
import com.here.sdk.mapview.MapMeasureDependentRenderSize
import com.here.sdk.mapview.MapPolyline
import com.here.sdk.mapview.MapScheme
import com.here.sdk.mapview.MapView
import com.here.sdk.mapview.RenderSize
import com.permitnav.R
import com.permitnav.ui.theme.*
import com.permitnav.ui.viewmodels.NavigationViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun NavigationScreen(
    permitId: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember { NavigationViewModel(context) }
    val uiState by viewModel.uiState.collectAsState()
    
    // Location permission state
    val locationPermissionState = rememberPermissionState(
        permission = Manifest.permission.ACCESS_FINE_LOCATION
    )
    
    // Load permit and calculate route when screen loads
    LaunchedEffect(permitId) {
        android.util.Log.d("NavigationScreen", "Loading permit and calculating route for: $permitId")
        viewModel.loadPermitAndCalculateRoute(permitId)
    }
    
    // Start navigation automatically when permission is granted and route is available
    LaunchedEffect(locationPermissionState.status.isGranted, uiState.route) {
        if (locationPermissionState.status.isGranted && uiState.route != null && !uiState.isNavigating) {
            // Auto-start navigation if user has already granted permission
            // This creates a seamless experience after permission is granted
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigation") },
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
                    IconButton(onClick = { /* Settings */ }) {
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        NativeHereMapView(
                            route = uiState.route,
                            isNavigating = uiState.isNavigating
                        )
                        
                        if (uiState.isNavigating) {
                            // During navigation: Show compact navigation card at top and stop button at bottom
                            NavigationCard(
                                currentInstruction = uiState.currentInstruction,
                                nextInstruction = uiState.nextInstruction,
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
                                Text("End Navigation", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            // Before navigation: Show full preview and info cards overlaid on map
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp)
                            ) {
                                RoutePreviewCard(
                                    route = uiState.route,
                                    isLoading = uiState.isLoading
                                )
                            }
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                            ) {
                                RouteInfoCard(
                                    distance = uiState.routeDistance,
                                    duration = uiState.routeDuration,
                                    restrictions = uiState.activeRestrictions
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Button(
                                    onClick = { 
                                        if (locationPermissionState.status.isGranted) {
                                            viewModel.startNavigation()
                                        } else {
                                            locationPermissionState.launchPermissionRequest()
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SuccessGreen
                                    )
                                ) {
                                    Icon(Icons.Default.Navigation, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (locationPermissionState.status.isGranted) "Start Navigation" else "Grant Location Access",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NativeHereMapView(
    route: com.permitnav.data.models.Route?,
    isNavigating: Boolean
) {
    val context = LocalContext.current
    var isMapReady by remember { mutableStateOf(false) }
    
    // Create MapView once and remember it across recompositions
    val mapView = remember {
        android.util.Log.d("NavigationScreen", "Creating MapView (once)")
        MapView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            onCreate(null)
        }
    }
    
    // Update route when it changes, but don't recreate MapView
    LaunchedEffect(route) {
        if (isMapReady && route != null) {
            android.util.Log.d("NavigationScreen", "Route updated, adding to existing map")
            addRouteToMap(mapView, route)
        }
    }
    
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView }, // Use the remembered MapView instance
        update = { view ->
            // Only call onResume() in update, map scene loading happens once
            view.onResume()
        }
    )
    
    // Load map scene exactly once when MapView is first created
    LaunchedEffect(Unit) {
        android.util.Log.d("NavigationScreen", "Loading map scene (once)")
        
        mapView.setOnReadyListener {
            android.util.Log.d("NavigationScreen", "HERE MapView is ready to render!")
        }
        
        if (!isMapReady) {
            mapView.mapScene.loadScene(MapScheme.NORMAL_DAY) { mapError ->
                if (mapError == null) {
                    android.util.Log.d("NavigationScreen", "HERE SDK Map scene loaded successfully!")
                    isMapReady = true
                    
                    // Set initial camera position
                    val center = GeoCoordinates(39.7684, -86.1581) // Indianapolis
                    val zoom = MapMeasure(MapMeasure.Kind.DISTANCE_IN_METERS, 10000.0) // 10km
                    
                    android.util.Log.d("NavigationScreen", "Setting camera to Indianapolis: $center")
                    mapView.camera.lookAt(center, zoom)
                    
                    // Add route if available
                    if (route != null) {
                        android.util.Log.d("NavigationScreen", "Initial route available, adding to map")
                        addRouteToMap(mapView, route)
                    }
                    
                    android.util.Log.d("NavigationScreen", "Map initialization complete")
                } else {
                    android.util.Log.e("NavigationScreen", "HERE SDK Map loading FAILED: ${mapError.name}")
                    android.util.Log.e("NavigationScreen", "Map error details: $mapError")
                }
            }
        }
    }
    
    // Proper lifecycle management - dispose only when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            android.util.Log.d("NavigationScreen", "Disposing MapView (screen exit)")
            mapView.onPause()
            mapView.onDestroy()
        }
    }
}

private fun addRouteToMap(mapView: MapView, route: com.permitnav.data.models.Route) {
    try {
        // Check if MapView is properly initialized for use
        val mapScene = try {
            mapView.mapScene
        } catch (e: IllegalStateException) {
            android.util.Log.w("NavigationScreen", "MapView not yet initialized, skipping route addition")
            return
        }
        
        // Clear existing markers and polylines first
        try {
            // Note: We'll keep this simple and just add new objects
            // The HERE SDK will handle overlapping objects
        } catch (e: Exception) {
            android.util.Log.w("NavigationScreen", "Could not clear existing map objects", e)
        }
        
        // Add start and end markers using resource images
        val startCoordinates = GeoCoordinates(route.origin.latitude, route.origin.longitude)
        val endCoordinates = GeoCoordinates(route.destination.latitude, route.destination.longitude)
        
        // Create marker images using built-in Android drawables
        val context = mapView.context
        val startMarkerImage = MapImageFactory.fromResource(context.resources, android.R.drawable.ic_menu_mylocation)
        val endMarkerImage = MapImageFactory.fromResource(context.resources, android.R.drawable.ic_menu_myplaces)
        
        val startMarker = MapMarker(startCoordinates, startMarkerImage)
        val endMarker = MapMarker(endCoordinates, endMarkerImage)
        
        mapScene.addMapMarker(startMarker)
        mapScene.addMapMarker(endMarker)
        
        // Create route line using the actual route polyline if available, otherwise simple line
        val routePolyline = if (!route.polyline.isNullOrEmpty()) {
            try {
                // Decode the HERE polyline format
                android.util.Log.d("NavigationScreen", "Using actual route polyline: ${route.polyline.take(50)}...")
                val coordinates = decodeHerePolyline(route.polyline)
                if (coordinates.isNotEmpty()) {
                    coordinates
                } else {
                    android.util.Log.w("NavigationScreen", "Failed to decode polyline, using simple line")
                    listOf(startCoordinates, endCoordinates)
                }
            } catch (e: Exception) {
                android.util.Log.w("NavigationScreen", "Error decoding polyline, using simple line", e)
                listOf(startCoordinates, endCoordinates)
            }
        } else {
            android.util.Log.d("NavigationScreen", "No polyline available, using simple line")
            listOf(startCoordinates, endCoordinates)
        }
        
        val geoPolyline = GeoPolyline(routePolyline)
        val widthInPixels = 8.0
        val polylineColor = com.here.sdk.core.Color.valueOf(1.0f, 0.4f, 0.0f, 0.8f) // Orange color
        
        val routeLine = try {
            MapPolyline(
                geoPolyline, 
                MapPolyline.SolidRepresentation(
                    MapMeasureDependentRenderSize(RenderSize.Unit.PIXELS, widthInPixels),
                    polylineColor,
                    LineCap.ROUND
                )
            )
        } catch (e: MapPolyline.Representation.InstantiationException) {
            android.util.Log.e("NavigationScreen", "MapPolyline creation failed", e)
            return
        } catch (e: MapMeasureDependentRenderSize.InstantiationException) {
            android.util.Log.e("NavigationScreen", "MapMeasureDependentRenderSize creation failed", e)
            return
        }
        
        mapScene.addMapPolyline(routeLine)
        
        android.util.Log.d("NavigationScreen", "Route added to native HERE map")
    } catch (e: Exception) {
        android.util.Log.e("NavigationScreen", "Error adding route to map", e)
    }
}

private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6371.0 // kilometers
    
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    
    return earthRadius * c // Distance in kilometers
}

private fun decodeHerePolyline(polyline: String): List<GeoCoordinates> {
    return try {
        // For now, let's use a simple approach and just return start/end points
        // The HERE SDK polyline decoder might not be available in this version
        android.util.Log.d("NavigationScreen", "Polyline decoding not available, using simplified route")
        emptyList()
    } catch (e: Exception) {
        android.util.Log.e("NavigationScreen", "Failed to decode HERE polyline", e)
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
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PrimaryOrange),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        currentInstruction,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2
                    )
                    if (nextInstruction.isNotEmpty()) {
                        Text(
                            nextInstruction,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RouteInfoCard(
    distance: String = "-- mi",
    duration: String = "-- min", 
    restrictions: Int = 0
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RouteInfoItem(
                icon = Icons.Default.Timer,
                label = "Time",
                value = duration,
                color = SecondaryBlue
            )
            RouteInfoItem(
                icon = Icons.Default.Route,
                label = "Distance",
                value = distance,
                color = PrimaryOrange
            )
            RouteInfoItem(
                icon = Icons.Default.Warning,
                label = "Restrictions",
                value = "$restrictions Active",
                color = WarningYellow
            )
        }
    }
}

@Composable
fun RouteInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
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
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = null,
                    tint = PrimaryOrange,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Route Overview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            when {
                isLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = PrimaryOrange,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Calculating route...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
                route != null -> {
                    // Show ALL route instructions as preview
                    if (route.turnByTurnInstructions.isNotEmpty()) {
                        Text(
                            "Route: ${route.origin.name ?: "Start"} → ${route.destination.name ?: "Destination"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            "Complete Turn-by-Turn Instructions (${route.turnByTurnInstructions.size} steps):",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = PrimaryOrange
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Column(
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            route.turnByTurnInstructions.forEachIndexed { index, instruction ->
                                Row(
                                    modifier = Modifier.padding(vertical = 1.dp)
                                ) {
                                    Text(
                                        "${index + 1}.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PrimaryOrange,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.width(24.dp)
                                    )
                                    Text(
                                        instruction.instruction,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            "Route calculated successfully",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SuccessGreen
                        )
                    }
                }
                else -> {
                    Text(
                        "Ready to calculate route",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}