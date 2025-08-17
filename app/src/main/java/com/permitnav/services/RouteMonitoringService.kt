package com.permitnav.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.work.*
import java.util.concurrent.TimeUnit

class RouteMonitoringService : Service() {
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val permitId = intent?.getStringExtra("permit_id")
        
        if (permitId != null) {
            scheduleRouteMonitoring(permitId)
        }
        
        return START_STICKY
    }
    
    private fun scheduleRouteMonitoring(permitId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        
        val routeMonitorRequest = PeriodicWorkRequestBuilder<RouteMonitorWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(workDataOf("permit_id" to permitId))
            .build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "route_monitoring_$permitId",
            ExistingPeriodicWorkPolicy.REPLACE,
            routeMonitorRequest
        )
    }
}

class RouteMonitorWorker(
    context: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val permitId = inputData.getString("permit_id") ?: return Result.failure()
        
        try {
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}