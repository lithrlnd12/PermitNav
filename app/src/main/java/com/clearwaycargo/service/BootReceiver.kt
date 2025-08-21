package com.clearwaycargo.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot receiver that automatically starts the background dispatcher service
 * when the device boots up, ensuring always-on hands-free functionality
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "📱 Device boot completed - starting background dispatcher")
            
            try {
                // Start the background dispatcher service
                val serviceIntent = Intent(context, DispatchForegroundService::class.java)
                context.startForegroundService(serviceIntent)
                
                Log.d(TAG, "✅ Background dispatcher service started after boot")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start background dispatcher after boot", e)
            }
        }
    }
}