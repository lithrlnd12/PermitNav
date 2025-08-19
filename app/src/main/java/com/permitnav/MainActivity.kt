package com.permitnav

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.permitnav.ui.screens.*
import com.permitnav.ui.theme.ClearwayCargoTheme
import com.permitnav.ui.viewmodels.AuthViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    
    // Firebase instances
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()
        
        Log.d("Firebase", "🔥 Firebase initialized successfully")
        
        // Test Firebase functionality
        testFirebaseOperations()
        
        setContent {
            ClearwayCargoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermitNavApp()
                }
            }
        }
    }
    
    /**
     * Test Firebase operations on app startup
     */
    private fun testFirebaseOperations() {
        Log.d("Firebase", "🧪 Testing Firebase operations...")
        
        // Test writing and reading permits
        writePermit()
        
        // Delay reading to allow write to complete
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            readPermits()
        }, 1000)
        
        // Example: Test file upload (uncomment when you have a real file URI)
        // val exampleUri = Uri.parse("android.resource://com.permitnav/drawable/permitnav_logo")
        // uploadPermitFile(exampleUri, "driver123", "test_permit_1")
    }
    
    /**
     * Write a permit document into Firestore
     */
    private fun writePermit() {
        val permit = hashMapOf(
            "driverId" to "driver123",
            "state" to "IN",
            "status" to "validated",
            "permitNumber" to "IN-2024-001234",
            "dimensions" to mapOf(
                "length" to 75.0,
                "width" to 12.0,
                "height" to 14.5,
                "weight" to 85000.0
            ),
            "createdAt" to FieldValue.serverTimestamp()
        )
        
        db.collection("permits")
            .add(permit)
            .addOnSuccessListener { documentReference ->
                Log.d("Firestore", "✅ Permit saved with ID: ${documentReference.id}")
                Log.d("Firestore", "📄 Permit data: $permit")
            }
            .addOnFailureListener { e ->
                Log.e("Firestore", "❌ Error writing permit", e)
            }
    }
    
    /**
     * Read all permits from Firestore
     */
    private fun readPermits() {
        Log.d("Firestore", "📖 Reading permits from Firestore...")
        
        db.collection("permits")
            .get()
            .addOnSuccessListener { result ->
                Log.d("Firestore", "✅ Found ${result.size()} permits")
                for (document in result) {
                    Log.d("Firestore", "📋 Permit ${document.id}: ${document.data}")
                }
                if (result.isEmpty) {
                    Log.d("Firestore", "📭 No permits found in database")
                }
            }
            .addOnFailureListener { exception ->
                Log.e("Firestore", "❌ Error reading permits", exception)
            }
    }
    
    /**
     * Upload a file into Storage
     */
    private fun uploadPermitFile(fileUri: Uri, driverId: String = "driver123", permitId: String = "permit1") {
        val storageRef = storage.reference.child("permits/$driverId/$permitId.pdf")
        
        Log.d("Storage", "📤 Uploading file to: permits/$driverId/$permitId.pdf")
        
        storageRef.putFile(fileUri)
            .addOnSuccessListener { taskSnapshot ->
                Log.d("Storage", "✅ File uploaded successfully!")
                Log.d("Storage", "📊 Upload size: ${taskSnapshot.totalByteCount} bytes")
                
                // Get the download URL
                getDownloadUrl(storageRef)
            }
            .addOnFailureListener { exception ->
                Log.e("Storage", "❌ File upload failed", exception)
            }
            .addOnProgressListener { taskSnapshot ->
                val progress = (100.0 * taskSnapshot.bytesTransferred) / taskSnapshot.totalByteCount
                Log.d("Storage", "📈 Upload progress: ${progress.toInt()}%")
            }
    }
    
    /**
     * Get the download URL of a file from Storage
     */
    private fun getDownloadUrl(storageRef: com.google.firebase.storage.StorageReference) {
        storageRef.downloadUrl
            .addOnSuccessListener { uri ->
                Log.d("Storage", "✅ File download URL: $uri")
                Log.d("Storage", "🔗 File can be accessed at: $uri")
            }
            .addOnFailureListener { exception ->
                Log.e("Storage", "❌ Failed to get download URL", exception)
            }
    }
    
    /**
     * Public method to upload permit files (can be called from other parts of the app)
     */
    fun uploadPermit(fileUri: Uri, driverId: String, permitId: String) {
        uploadPermitFile(fileUri, driverId, permitId)
    }
    
    /**
     * Public method to query permits for a specific driver
     */
    fun getPermitsForDriver(driverId: String) {
        Log.d("Firestore", "🔍 Querying permits for driver: $driverId")
        
        db.collection("permits")
            .whereEqualTo("driverId", driverId)
            .get()
            .addOnSuccessListener { result ->
                Log.d("Firestore", "✅ Found ${result.size()} permits for driver $driverId")
                for (document in result) {
                    Log.d("Firestore", "📋 Driver permit: ${document.data}")
                }
            }
            .addOnFailureListener { exception ->
                Log.e("Firestore", "❌ Error querying permits for driver $driverId", exception)
            }
    }
}

@Composable
fun PermitNavApp() {
    val authViewModel: AuthViewModel = viewModel()
    var showSplash by remember { mutableStateOf(true) }
    var isAuthenticated by remember { mutableStateOf(false) }
    
    // Check auth state when app starts
    LaunchedEffect(Unit) {
        isAuthenticated = authViewModel.checkAuthState()
    }
    
    // Observe auth state changes
    val authState by authViewModel.uiState.collectAsState()
    LaunchedEffect(authState.isAuthenticated) {
        isAuthenticated = authState.isAuthenticated
    }
    
    when {
        showSplash -> {
            SplashScreen(
                onSplashComplete = { showSplash = false }
            )
        }
        !isAuthenticated -> {
            AuthScreen(
                onAuthSuccess = { isAuthenticated = true }
            )
        }
        else -> {
            MainNavigation()
        }
    }
}

@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToPermitReview = { permitId, imageUri ->
                    android.util.Log.d("MainActivity", "Navigating to permit review - permitId: $permitId, imageUri: $imageUri")
                    if (imageUri != null) {
                        // Encode the URI to safely pass it as a route parameter
                        val encodedUri = java.net.URLEncoder.encode(imageUri, "UTF-8")
                        navController.navigate("permit_review/$permitId?imageUri=$encodedUri")
                    } else {
                        navController.navigate("permit_review/$permitId")
                    }
                },
                onNavigateToMultiCapture = {
                    navController.navigate("multi_capture")
                },
                onNavigateToVault = {
                    navController.navigate("vault")
                },
                onNavigateToNavigation = { permitId ->
                    navController.navigate("navigation/$permitId")
                },
                onNavigateToChat = {
                    navController.navigate("chat")
                }
            )
        }
        
        composable(
            "permit_review/{permitId}?imageUri={imageUri}",
            arguments = listOf(
                navArgument("permitId") { type = NavType.StringType },
                navArgument("imageUri") { 
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val permitId = backStackEntry.arguments?.getString("permitId") ?: ""
            val encodedImageUri = backStackEntry.arguments?.getString("imageUri")
            val imageUri = encodedImageUri?.let { 
                java.net.URLDecoder.decode(it, "UTF-8") 
            }
            android.util.Log.d("MainActivity", "PermitReviewScreen - permitId: $permitId, imageUri: $imageUri")
            PermitReviewScreen(
                permitId = permitId,
                imageUri = imageUri,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToNavigation = {
                    navController.navigate("navigation/$permitId")
                }
            )
        }
        
        composable("navigation/{permitId}") { backStackEntry ->
            val permitId = backStackEntry.arguments?.getString("permitId") ?: ""
            NavigationScreen(
                permitId = permitId,
                onNavigateBack = { 
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = false }
                    }
                }
            )
        }
        
        composable("vault") {
            VaultScreen(
                onNavigateBack = { navController.popBackStack() },
                onSelectPermit = { permitId ->
                    navController.navigate("permit_review/$permitId")
                }
            )
        }
        
        composable("chat") {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(
            "chat/{permitId}",
            arguments = listOf(navArgument("permitId") { type = NavType.StringType })
        ) { backStackEntry ->
            val permitId = backStackEntry.arguments?.getString("permitId") ?: ""
            ChatScreen(
                permitId = permitId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable("multi_capture") {
            MultiImageCaptureScreen(
                onImagesSelected = { imageUris ->
                    val permitId = "multi_${System.currentTimeMillis()}"
                    val urisString = imageUris.joinToString(",") { it.toString() }
                    val encodedUri = java.net.URLEncoder.encode(urisString, "UTF-8")
                    navController.navigate("permit_review/$permitId?imageUri=$encodedUri")
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }
    }
}

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object PermitReview : Screen("permit_review/{permitId}")
    object Navigation : Screen("navigation/{permitId}")
    object Vault : Screen("vault")
}