package com.permitnav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.permitnav.ui.screens.*
import com.permitnav.ui.theme.PermitNavTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PermitNavTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermitNavApp()
                }
            }
        }
    }
}

@Composable
fun PermitNavApp() {
    var showSplash by remember { mutableStateOf(true) }
    
    if (showSplash) {
        SplashScreen(
            onSplashComplete = { showSplash = false }
        )
    } else {
        MainNavigation()
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
                onNavigateToVault = {
                    navController.navigate("vault")
                },
                onNavigateToNavigation = { permitId ->
                    navController.navigate("navigation/$permitId")
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
                onNavigateBack = { navController.popBackStack() }
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
    }
}

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object PermitReview : Screen("permit_review/{permitId}")
    object Navigation : Screen("navigation/{permitId}")
    object Vault : Screen("vault")
}