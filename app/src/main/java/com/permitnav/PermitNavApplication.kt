package com.permitnav

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException

class PermitNavApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        initializeHERESDK()
        initializeWorkManager()
    }
    
    private fun initializeHERESDK() {
        // Use credentials from credentials.properties via BuildConfig
        val accessKeyID = BuildConfig.HERE_ACCESS_KEY_ID.takeIf { !it.isNullOrBlank() && it != "null" } 
            ?: throw RuntimeException("HERE_ACCESS_KEY_ID not found in credentials.properties")
        val accessKeySecret = BuildConfig.HERE_ACCESS_KEY_SECRET.takeIf { !it.isNullOrBlank() && it != "null" }
            ?: throw RuntimeException("HERE_ACCESS_KEY_SECRET not found in credentials.properties")
        
        android.util.Log.d("PermitNavApp", "Initializing HERE SDK with credentials from credentials.properties:")
        android.util.Log.d("PermitNavApp", "Access Key ID: ${accessKeyID.take(8)}...")
        android.util.Log.d("PermitNavApp", "Access Key Secret: ${accessKeySecret.take(8)}...")
        
        // Create authentication mode and SDK options
        val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
        val options = SDKOptions(authenticationMode)
        
        try {
            SDKNativeEngine.makeSharedInstance(this, options)
            android.util.Log.d("PermitNavApp", "HERE SDK initialized successfully with credentials.properties")
        } catch (e: InstantiationErrorException) {
            android.util.Log.e("PermitNavApp", "HERE SDK initialization failed: ${e.error.name}")
            android.util.Log.e("PermitNavApp", "Exception details: ${e.message}")
            
            // Log potential authentication issues
            android.util.Log.e("PermitNavApp", "Check HERE project settings:")
            android.util.Log.e("PermitNavApp", "1. Base Plan active")
            android.util.Log.e("PermitNavApp", "2. HERE SDK for Android (Explore Edition) enabled")
            android.util.Log.e("PermitNavApp", "3. Access Key ID/Secret from Access Manager (not API key)")
            android.util.Log.e("PermitNavApp", "4. Device time set to automatic")
            android.util.Log.e("PermitNavApp", "5. credentials.properties file exists with valid keys")
            
            throw RuntimeException("Initialization of HERE SDK failed: ${e.error.name}")
        }
    }
    
    private fun initializeWorkManager() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
            
        WorkManager.initialize(this, config)
    }
    
    override fun onTerminate() {
        // Free HERE SDK resources
        SDKNativeEngine.getSharedInstance()?.dispose()
        SDKNativeEngine.setSharedInstance(null)
        super.onTerminate()
    }
}