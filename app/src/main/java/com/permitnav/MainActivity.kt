package com.permitnav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.permitnav.ui.settings.SettingsScreen
import com.permitnav.ui.theme.ClearwayCargoTheme
import com.permitnav.ui.viewmodels.AuthViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : FragmentActivity() {
    
    // Firebase instances
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    
    // Permission management
    private var permissionsGranted = false
    private val requiredPermissions = mutableListOf<String>().apply {
        // Core permissions needed for app functionality
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        // Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        permissionsGranted = allGranted
        
        if (allGranted) {
            Log.d("MainActivity", "✅ All essential permissions granted")
            // DISABLED: Background dispatcher service temporarily parked
            // startDispatcherServiceIfNeeded()
        } else {
            Log.w("MainActivity", "⚠️ Some essential permissions denied")
            // Could show explanation dialog here
        }
    }
    
    // Background dispatcher service auto-start flag
    companion object {
        private var dispatchServiceStarted = false
    }
    
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
        
        // Check and request essential permissions
        checkAndRequestPermissions()
        
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
    
    override fun onStart() {
        super.onStart()
        // DISABLED: Background dispatcher service temporarily parked
        // Only start dispatcher service if permissions are granted
        // if (permissionsGranted) {
        //     startDispatcherServiceIfNeeded()
        // }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop the dispatcher service when app is fully closed
        if (dispatchServiceStarted) {
            Log.d("MainActivity", "🛑 Stopping DispatchHotwordService - app destroyed")
            try {
                val intent = Intent(this, com.clearwaycargo.voice.DispatchHotwordService::class.java)
                stopService(intent)
                dispatchServiceStarted = false
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Failed to stop DispatchHotwordService", e)
            }
        }
    }
    
    /**
     * Check if all required permissions are granted, request if not
     */
    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            Log.d("MainActivity", "✅ All essential permissions already granted")
            permissionsGranted = true
            // DISABLED: Background dispatcher service temporarily parked
            // (permissions are granted but service won't start)
        } else {
            Log.d("MainActivity", "📱 Requesting essential permissions: ${missingPermissions.joinToString(", ")}")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    /**
     * Start the dispatcher service only if permissions are granted and not already started
     */
    private fun startDispatcherServiceIfNeeded() {
        if (!dispatchServiceStarted && permissionsGranted) {
            Log.d("MainActivity", "🎤 Attempting to start DispatchHotwordService with permissions granted")
            try {
                val intent = Intent(this, com.clearwaycargo.voice.DispatchHotwordService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                dispatchServiceStarted = true
                Log.d("MainActivity", "✅ DispatchHotwordService start requested")
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Failed to start DispatchHotwordService", e)
            }
        } else if (!permissionsGranted) {
            Log.w("MainActivity", "⚠️ Cannot start DispatchHotwordService - permissions not granted")
        } else {
            Log.d("MainActivity", "⚠️ DispatchHotwordService already started")
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
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.w("Firestore", "⚠️ No authenticated user - skipping write test")
            return
        }
        
        val permit = hashMapOf(
            "driverId" to currentUser.uid,
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
    val context = LocalContext.current
    val authViewModel: AuthViewModel = viewModel()
    var showSplash by remember { mutableStateOf(true) }
    var isAuthenticated by remember { mutableStateOf(false) }
    var savedRoute by remember { mutableStateOf<String?>(null) }
    
    // Check auth state and restore saved navigation state when app starts
    LaunchedEffect(Unit) {
        isAuthenticated = authViewModel.checkAuthState()
        // Restore last navigation state from SharedPreferences
        val prefs = context.getSharedPreferences("app_navigation", Context.MODE_PRIVATE)
        val routeTemplate = prefs.getString("last_route", null)
        val saveTimestamp = prefs.getLong("save_timestamp", 0)
        val currentTime = System.currentTimeMillis()
        val maxAge = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
        
        // Only restore if saved recently (within 24 hours)
        if (routeTemplate != null && (currentTime - saveTimestamp) < maxAge) {
            Log.d("MainActivity", "🔄 Restoring recent route (${(currentTime - saveTimestamp) / 1000 / 60} minutes old)")
        } else if (routeTemplate != null) {
            Log.d("MainActivity", "⏰ Saved route too old (${(currentTime - saveTimestamp) / 1000 / 60 / 60} hours), using home instead")
            prefs.edit().clear().apply() // Clear old data
        }
        
        // Build actual route with saved parameters
        savedRoute = if (routeTemplate != null && (currentTime - saveTimestamp) < maxAge) {
            when (routeTemplate) {
            "permit_review/{permitId}" -> {
                val permitId = prefs.getString("saved_permit_id", null)
                val imageUri = prefs.getString("saved_image_uri", null)
                if (permitId != null) {
                    if (imageUri != null) {
                        "permit_review/$permitId?imageUri=$imageUri"
                    } else {
                        "permit_review/$permitId"
                    }
                } else {
                    "home" // Fallback if parameters are missing
                }
            }
            "chat/{permitId}" -> {
                val permitId = prefs.getString("saved_permit_id", null)
                if (permitId != null) "chat/$permitId" else "chat"
            }
            "voice_chat/{permitId}" -> {
                val permitId = prefs.getString("saved_permit_id", null)
                if (permitId != null) "voice_chat/$permitId" else "voice_chat"
            }
            "navigation/{permitId}" -> {
                val permitId = prefs.getString("saved_permit_id", null)
                if (permitId != null) "navigation/$permitId" else "home"
            }
            // Simple routes without parameters
            "home", "vault", "chat", "voice_chat", "multi_capture" -> routeTemplate
            else -> "home" // Default fallback
            }
        } else {
            "home" // Default to home if no valid saved route
        }
        Log.d("MainActivity", "🔄 Restored route: $routeTemplate → $savedRoute")
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
            MainNavigation(startDestination = savedRoute)
        }
    }
}

@Composable
fun MainNavigation(startDestination: String? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()
    
    // Save navigation state when route changes
    LaunchedEffect(navController) {
        navController.addOnDestinationChangedListener { _, destination, arguments ->
            val route = destination.route
            if (route != null && route != "auth") {  // Don't save auth screen
                val prefs = context.getSharedPreferences("app_navigation", Context.MODE_PRIVATE)
                
                // Save the route template and arguments separately
                prefs.edit().apply {
                    putString("last_route", route)
                    putLong("save_timestamp", System.currentTimeMillis()) // Add timestamp
                    
                    // Save arguments for complex routes
                    arguments?.let { args ->
                        args.getString("permitId")?.let { 
                            putString("saved_permit_id", it)
                        }
                        args.getString("imageUri")?.let { 
                            putString("saved_image_uri", it)
                        }
                    }
                    apply()
                }
                Log.d("MainActivity", "💾 Saved route: $route with arguments")
            }
        }
    }
    
    val initialRoute = startDestination?.takeIf { 
        it != "auth" && it.isNotBlank() 
    } ?: "home"
    
    Log.d("MainActivity", "🚀 Starting navigation with route: $initialRoute")
    
    NavHost(
        navController = navController,
        startDestination = initialRoute
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
                onNavigateToChat = { permitId ->
                    if (permitId != null) {
                        navController.navigate("chat/$permitId")
                    } else {
                        navController.navigate("chat")
                    }
                },
                onNavigateToVoiceChat = { permitId ->
                    if (permitId != null) {
                        navController.navigate("voice_chat/$permitId")
                    } else {
                        navController.navigate("voice_chat")
                    }
                },
                onNavigateToAuth = {
                    // Clear saved navigation state when logging out
                    val prefs = context.getSharedPreferences("app_navigation", Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()
                    Log.d("MainActivity", "🗑️ Cleared navigation state - user logging out")
                    
                    navController.navigate("auth") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
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
                    // Use popBackStack to go back to wherever user came from (home or permit_review)
                    navController.popBackStack()
                },
                onNavigateToAuth = {
                    // Clear saved navigation state when logging out
                    val prefs = context.getSharedPreferences("app_navigation", Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()
                    Log.d("MainActivity", "🗑️ Cleared navigation state - user logging out")
                    
                    navController.navigate("auth") {
                        popUpTo("home") { inclusive = true }
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
        
        // Voice Chat routes
        composable("voice_chat") {
            VoiceChatScreen(
                permitId = null,
                onNavigateBack = { 
                    // Navigate directly to home to ensure we don't get stuck on blue screen
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = false }
                    }
                }
            )
        }
        
        composable(
            "voice_chat/{permitId}",
            arguments = listOf(navArgument("permitId") { type = NavType.StringType })
        ) { backStackEntry ->
            val permitId = backStackEntry.arguments?.getString("permitId") ?: ""
            VoiceChatScreen(
                permitId = permitId,
                onNavigateBack = { 
                    // Navigate directly to home to ensure we don't get stuck on blue screen
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = false }
                    }
                }
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
        
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
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