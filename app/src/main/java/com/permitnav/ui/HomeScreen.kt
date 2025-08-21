package com.permitnav.ui.screens

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.permitnav.data.models.Permit
import com.permitnav.ui.theme.*
import com.permitnav.ui.viewmodels.AuthViewModel
import com.permitnav.ui.viewmodels.HomeViewModel
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPermitReview: (String, String?) -> Unit,
    onNavigateToMultiCapture: () -> Unit,
    onNavigateToVault: () -> Unit,
    onNavigateToNavigation: (String) -> Unit,
    onNavigateToChat: (String?) -> Unit = {},
    onNavigateToVoiceChat: (String?) -> Unit = {},
    onNavigateToAuth: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showCaptureOptions by remember { mutableStateOf(false) }
    var showPermitDialog by remember { mutableStateOf(false) }
    var selectedPermit by remember { mutableStateOf<Permit?>(null) }
    val recentPermits = remember { mutableStateListOf<Permit>() }
    val authViewModel: AuthViewModel = viewModel()
    val homeViewModel: HomeViewModel = viewModel()
    
    // Load permits from database when screen starts
    val permits by homeViewModel.permits.collectAsState()
    
    LaunchedEffect(permits) {
        recentPermits.clear()
        recentPermits.addAll(permits)
    }
    
    // Background dispatcher auto-starts via MainActivity - no action needed here
    
    // Single photo launcher (legacy - kept for quick single page permits)
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        android.util.Log.d("HomeScreen", "Camera result: success=$success, imageUri=$imageUri")
        if (success) {
            imageUri?.let { uri ->
                android.util.Log.d("HomeScreen", "Navigating to permit review with URI: $uri")
                val permitId = "new_${System.currentTimeMillis()}"
                onNavigateToPermitReview(permitId, uri.toString())
            }
        } else {
            android.util.Log.w("HomeScreen", "Camera capture failed or was cancelled")
        }
    }
    
    // Gallery launcher for single image (legacy)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val permitId = "new_${System.currentTimeMillis()}"
            onNavigateToPermitReview(permitId, it.toString())
        }
    }
    
    // Multi-image gallery launcher (new)
    val multiGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val permitId = "new_${System.currentTimeMillis()}"
            // Convert multiple URIs to comma-separated string for navigation
            val urisString = uris.joinToString(",") { it.toString() }
            onNavigateToPermitReview(permitId, urisString)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Clearway Cargo",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryOrange,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onNavigateToVault) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = "Permit Vault",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                }
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(SecondaryBlue)
        ) {
            // Clean, simple action cards
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Primary Action: Scan Permit
                Card(
                    onClick = { showBottomSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = PrimaryOrange.copy(alpha = 0.1f)),
                    border = BorderStroke(2.dp, PrimaryOrange),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(PrimaryOrange),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Scan Permit",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        Column {
                            Text(
                                "Scan New Permit",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryOrange
                            )
                            Text(
                                "Add your permit photos",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                // Text Chat with AI Dispatch
                Card(
                    onClick = { onNavigateToChat(null) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = PrimaryOrange.copy(alpha = 0.1f)),
                    border = BorderStroke(2.dp, PrimaryOrange),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(PrimaryOrange),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Chat,
                                contentDescription = "Text Chat",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        Column {
                            Text(
                                "Text with AI Dispatch",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryOrange
                            )
                            Text(
                                "Get compliance help & routing",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                // Voice Chat with AI Dispatch
                Card(
                    onClick = { onNavigateToVoiceChat(selectedPermit?.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = PrimaryOrange.copy(alpha = 0.1f)),
                    border = BorderStroke(2.dp, PrimaryOrange),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(PrimaryOrange),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Voice Chat",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        Column {
                            Text(
                                "Voice with AI Dispatch",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryOrange
                            )
                            Text(
                                "Natural hands-free assistance",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                // REMOVED: Background Dispatcher box temporarily parked
                // (Feature disabled until hotword detection is refined)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            RecentPermitsSection(
                permits = recentPermits,
                onPermitClick = { permit -> 
                    selectedPermit = permit
                    showPermitDialog = true
                }
            )
        }
    }
    
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "Add Permit Photos",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    "Choose one of these two simple options:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
                
                // Option 1: Take Picture(s) - handles camera workflow
                Card(
                    onClick = {
                        showBottomSheet = false
                        onNavigateToMultiCapture()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.1f)),
                    border = BorderStroke(2.dp, SuccessGreen)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(SuccessGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = "Take pictures",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Take Picture(s)",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            Text(
                                "Use your camera to capture permit pages",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                // Multi-select from gallery (NEW)
                Card(
                    onClick = {
                        multiGalleryLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                        showBottomSheet = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = SecondaryBlue.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(SecondaryBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Collections,
                                contentDescription = "Multi-select",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Upload from Gallery",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = SecondaryBlue
                            )
                            Text(
                                "Choose existing photos from your device",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // Permit Selection Dialog
    if (showPermitDialog && selectedPermit != null) {
        AlertDialog(
            onDismissRequest = { 
                showPermitDialog = false 
                selectedPermit = null
            },
            title = {
                Text(
                    "Select Action",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SecondaryBlue
                )
            },
            text = {
                Column {
                    Text(
                        "Permit: ${selectedPermit!!.permitNumber}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "State: ${selectedPermit!!.state.uppercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This permit is already validated. Choose an option:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Action buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedPermit?.let { permit ->
                                    onNavigateToNavigation(permit.id)
                                }
                                showPermitDialog = false
                                selectedPermit = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SuccessGreen
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Plan Route", color = Color.White)
                        }
                        
                        Button(
                            onClick = {
                                // Go to chat with permit context
                                onNavigateToChat(selectedPermit?.id)
                                showPermitDialog = false
                                selectedPermit = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SecondaryBlue
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Chat", color = Color.White)
                        }
                    }
                    
                    // Complete permit button
                    Button(
                        onClick = {
                            selectedPermit?.let { permit ->
                                homeViewModel.deletePermit(permit)
                            }
                            showPermitDialog = false
                            selectedPermit = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ErrorRed
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Mark Complete & Remove", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showPermitDialog = false
                        selectedPermit = null
                    }
                ) {
                    Text("Cancel", color = SecondaryBlue)
                }
            }
        )
    }
}


@Composable
fun RecentPermitsSection(
    permits: List<Permit>,
    onPermitClick: (Permit) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Your Permits",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondaryBlue
                )
                if (permits.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = WarningYellow.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, WarningYellow),
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text(
                            "No Permits",
                            style = MaterialTheme.typography.labelSmall,
                            color = WarningYellow,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            
            if (permits.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.Gray.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No permits scanned yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                        Text(
                            "Scan your first permit to get started",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(permits.take(5)) { permit ->
                        PermitListItem(
                            permit = permit,
                            onClick = { onPermitClick(permit) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermitListItem(
    permit: Permit,
    onClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (permit.isValid) 
                SuccessGreen.copy(alpha = 0.05f) 
            else 
                ErrorRed.copy(alpha = 0.05f)
        ),
        border = BorderStroke(
            1.dp,
            if (permit.isValid) SuccessGreen.copy(alpha = 0.3f) else ErrorRed.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    permit.permitNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = SecondaryBlue
                )
                Text(
                    "Expires: ${dateFormat.format(permit.expirationDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryBlue,
                    fontWeight = FontWeight.Medium
                )
                permit.dimensions.weight?.let { weight ->
                    Text(
                        "${java.text.NumberFormat.getInstance().format(weight.toInt())} lbs",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondaryBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Icon(
                if (permit.isValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (permit.isValid) "Valid" else "Invalid",
                tint = if (permit.isValid) SuccessGreen else ErrorRed,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

