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
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.permitnav.data.models.Permit
import com.permitnav.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPermitReview: (String, String?) -> Unit,
    onNavigateToVault: () -> Unit,
    onNavigateToNavigation: (String) -> Unit
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val recentPermits = remember { mutableStateListOf<Permit>() }
    
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        android.util.Log.d("HomeScreen", "Camera result: success=$success, imageUri=$imageUri")
        if (success) {
            imageUri?.let { uri ->
                android.util.Log.d("HomeScreen", "Navigating to permit review with URI: $uri")
                // Store the image URI in the saved state and navigate
                val permitId = "new_${System.currentTimeMillis()}"
                onNavigateToPermitReview(permitId, uri.toString())
            }
        } else {
            android.util.Log.w("HomeScreen", "Camera capture failed or was cancelled")
        }
    }
    
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val permitId = "new_${System.currentTimeMillis()}"
            onNavigateToPermitReview(permitId, it.toString())
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "PermitNav",
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
                    IconButton(onClick = { /* Settings */ }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showBottomSheet = true },
                containerColor = PrimaryOrange,
                contentColor = Color.White,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Scan Permit"
                )
                Spacer(Modifier.width(8.dp))
                Text("Scan Permit")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BackgroundLight)
        ) {
            QuickActions(
                onScanPermit = { showBottomSheet = true },
                onPlanRoute = {
                    if (recentPermits.isNotEmpty()) {
                        onNavigateToNavigation(recentPermits.first().id)
                    }
                },
                onViewVault = onNavigateToVault
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            RecentPermitsSection(
                permits = recentPermits,
                onPermitClick = { permitId -> onNavigateToPermitReview(permitId, null) }
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
                    "Choose Image Source",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Card(
                    onClick = {
                        if (cameraPermissionState.status.isGranted) {
                            val photoFile = File(
                                context.cacheDir,
                                "permit_${System.currentTimeMillis()}.jpg"
                            )
                            imageUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                photoFile
                            )
                            cameraLauncher.launch(imageUri!!)
                        } else {
                            cameraPermissionState.launchPermissionRequest()
                        }
                        showBottomSheet = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceLight)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Camera",
                            tint = PrimaryOrange,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                "Take Photo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Use camera to capture permit",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                Card(
                    onClick = {
                        galleryLauncher.launch("image/*")
                        showBottomSheet = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceLight)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Photo,
                            contentDescription = "Gallery",
                            tint = SecondaryBlue,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                "Choose from Gallery",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Select existing permit image",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun QuickActions(
    onScanPermit: () -> Unit,
    onPlanRoute: () -> Unit,
    onViewVault: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Quick Actions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickActionButton(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Scan",
                    color = PrimaryOrange,
                    onClick = onScanPermit
                )
                QuickActionButton(
                    icon = Icons.Default.Route,
                    label = "Route",
                    color = SecondaryBlue,
                    onClick = onPlanRoute
                )
                QuickActionButton(
                    icon = Icons.Default.Folder,
                    label = "Vault",
                    color = AccentGreen,
                    onClick = onViewVault
                )
            }
        }
    }
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RecentPermitsSection(
    permits: List<Permit>,
    onPermitClick: (String) -> Unit
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
                    "Recent Permits",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
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
                            onClick = { onPermitClick(permit.id) }
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
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Expires: ${dateFormat.format(permit.expirationDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                permit.dimensions.weight?.let { weight ->
                    Text(
                        "${weight.toInt()} lbs",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
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