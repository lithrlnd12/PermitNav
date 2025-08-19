package com.permitnav.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.permitnav.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MultiImageCaptureScreen(
    onImagesSelected: (List<Uri>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // States
    var capturedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isCapturing by remember { mutableStateOf(true) }
    var showPreview by remember { mutableStateOf(false) }
    var currentPreviewImage by remember { mutableStateOf<Uri?>(null) }
    
    // Camera permission
    val cameraPermissionState = rememberPermissionState(
        permission = Manifest.permission.CAMERA
    )
    
    // Gallery picker launcher - supports multiple selection
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                capturedImages = capturedImages + uris
                showPreview = true
            }
        }
    )
    
    // Camera setup
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (isCapturing) "Capture Permit Pages" 
                        else "Review ${capturedImages.size} Images"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    if (capturedImages.isNotEmpty()) {
                        Badge(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text("${capturedImages.size}")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryOrange,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            if (!isCapturing || capturedImages.isNotEmpty()) {
                BottomAppBar(
                    containerColor = Color.White,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (isCapturing) {
                            OutlinedButton(
                                onClick = { 
                                    isCapturing = false
                                    showPreview = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Preview, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Review")
                            }
                            Spacer(Modifier.width(16.dp))
                        }
                        
                        Button(
                            onClick = { 
                                if (capturedImages.isNotEmpty()) {
                                    onImagesSelected(capturedImages)
                                }
                            },
                            enabled = capturedImages.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SuccessGreen
                            )
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Process (${capturedImages.size})")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                !cameraPermissionState.status.isGranted -> {
                    // Camera permission request
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = PrimaryOrange
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Camera Permission Required",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "To capture permit images, please grant camera access",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() }
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
                
                showPreview && !isCapturing -> {
                    // Image review screen
                    ImageReviewScreen(
                        images = capturedImages,
                        onAddMore = {
                            isCapturing = true
                            showPreview = false
                        },
                        onRemoveImage = { uri ->
                            capturedImages = capturedImages.filter { it != uri }
                            if (capturedImages.isEmpty()) {
                                isCapturing = true
                                showPreview = false
                            }
                        },
                        onUploadFromGallery = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
                
                else -> {
                    // Camera capture view
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }.also { previewView ->
                                    val cameraProvider = cameraProviderFuture.get()
                                    
                                    preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    
                                    imageCapture = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                        .build()
                                    
                                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                    
                                    try {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            cameraSelector,
                                            preview,
                                            imageCapture
                                        )
                                    } catch (e: Exception) {
                                        android.util.Log.e("MultiImageCapture", "Camera binding failed", e)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        // Capture overlay
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // Captured images preview strip
                            if (capturedImages.isNotEmpty()) {
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(capturedImages) { uri ->
                                        AsyncImage(
                                            model = uri,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(60.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                            
                            // Control buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Gallery button
                                IconButton(
                                    onClick = {
                                        galleryLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.8f))
                                ) {
                                    Icon(
                                        Icons.Default.PhotoLibrary,
                                        contentDescription = "Gallery",
                                        tint = PrimaryOrange
                                    )
                                }
                                
                                // Capture button
                                IconButton(
                                    onClick = {
                                        val photoFile = File(
                                            context.externalCacheDir,
                                            "permit_${System.currentTimeMillis()}.jpg"
                                        )
                                        
                                        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                                        
                                        imageCapture?.takePicture(
                                            outputOptions,
                                            cameraExecutor,
                                            object : ImageCapture.OnImageSavedCallback {
                                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                                    val uri = Uri.fromFile(photoFile)
                                                    scope.launch {
                                                        capturedImages = capturedImages + uri
                                                        // Show quick feedback
                                                        android.util.Log.d("MultiImageCapture", "Image saved: $uri")
                                                    }
                                                }
                                                
                                                override fun onError(exception: ImageCaptureException) {
                                                    android.util.Log.e("MultiImageCapture", "Photo capture failed", exception)
                                                }
                                            }
                                        )
                                    },
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .border(4.dp, PrimaryOrange, CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.Camera,
                                        contentDescription = "Capture",
                                        tint = PrimaryOrange,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                
                                // Page counter
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(PrimaryOrange),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${capturedImages.size + 1}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            // Instructions
                            Text(
                                "Capture page ${capturedImages.size + 1} of your permit",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                textAlign = TextAlign.Center,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageReviewScreen(
    images: List<Uri>,
    onAddMore: () -> Unit,
    onRemoveImage: (Uri) -> Unit,
    onUploadFromGallery: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Review Captured Images",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            "${images.size} pages captured",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Image grid with remove option
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(images) { index, uri ->
                Box {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Page ${index + 1}",
                        modifier = Modifier
                            .width(200.dp)
                            .height(260.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                    
                    // Remove button
                    IconButton(
                        onClick = { onRemoveImage(uri) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(ErrorRed)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    // Page number badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(PrimaryOrange)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "Page ${index + 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            
            // Add more button
            item {
                Card(
                    modifier = Modifier
                        .width(200.dp)
                        .height(260.dp)
                        .clickable { onAddMore() },
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Gray.copy(alpha = 0.1f)
                    ),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add more",
                            modifier = Modifier.size(48.dp),
                            tint = PrimaryOrange
                        )
                        Text(
                            "Add Page",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PrimaryOrange
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Additional options
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.Blue.copy(alpha = 0.05f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUploadFromGallery() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = SecondaryBlue
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Upload from Gallery",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Select existing permit photos or PDFs",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}