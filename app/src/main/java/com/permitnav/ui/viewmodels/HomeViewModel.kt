package com.permitnav.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.Permit
import com.permitnav.data.repository.PermitRepository
import com.permitnav.firebase.FirebaseService
import com.permitnav.ocr.PermitParser
import com.permitnav.ocr.TextRecognitionService
import com.permitnav.rules.ComplianceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    private val database = PermitNavDatabase.getDatabase(getApplication())
    private val permitRepository = PermitRepository(database.permitDao())
    private val firebaseService = FirebaseService()
    private val textRecognitionService = TextRecognitionService(getApplication())
    private val permitParser = PermitParser()
    private val complianceEngine = ComplianceEngine(getApplication())
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    private val _permits = MutableStateFlow<List<Permit>>(emptyList())
    val permits: StateFlow<List<Permit>> = _permits.asStateFlow()
    
    init {
        loadRecentPermits()
    }
    
    private fun loadRecentPermits() {
        viewModelScope.launch {
            permitRepository.getAllPermits().collect { permits ->
                _permits.value = permits.take(5)
            }
        }
    }
    
    fun deletePermit(permit: Permit) {
        viewModelScope.launch {
            try {
                android.util.Log.d("HomeViewModel", "🗑️ Deleting permit: ${permit.permitNumber}")
                
                // Delete from local database first
                permitRepository.deletePermit(permit)
                android.util.Log.d("HomeViewModel", "✅ Deleted from local database")
                
                // Delete from Firebase (cloud storage)
                val firebaseResult = firebaseService.deletePermit(permit.id)
                if (firebaseResult.isSuccess) {
                    android.util.Log.d("HomeViewModel", "✅ Deleted from Firebase: ${permit.id}")
                } else {
                    android.util.Log.e("HomeViewModel", "❌ Firebase deletion failed: ${firebaseResult.exceptionOrNull()}")
                }
                
                // Refresh the list regardless of Firebase result
                loadRecentPermits()
                
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "❌ Error deleting permit", e)
                // Still refresh the list in case local deletion worked
                loadRecentPermits()
            }
        }
    }
    
    fun processPermitImage(imageUri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)
            
            try {
                val extractedText = textRecognitionService.processImage(imageUri)
                val permit = permitParser.parseIndiana(extractedText)
                val complianceResult = complianceEngine.validatePermit(permit)
                
                val validatedPermit = permit.copy(
                    isValid = complianceResult.isCompliant,
                    validationErrors = complianceResult.violations
                )
                
                permitRepository.insertPermit(validatedPermit)
                
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastProcessedPermit = validatedPermit
                )
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = e.message
                )
            }
        }
    }
    
    /**
     * CLEANUP: Delete ALL test permits from Firebase and local database
     * This preserves state rules but clears all permit data to save costs
     */
    fun deleteAllTestPermits() {
        viewModelScope.launch {
            try {
                android.util.Log.d("HomeViewModel", "🧹 Starting cleanup of ALL test permits...")
                
                // Get all permits from local database - get first emission from flow
                val allPermits = permitRepository.getAllPermits().first()
                android.util.Log.d("HomeViewModel", "📋 Found ${allPermits.size} permits to delete")
                
                // Delete each permit from both local and Firebase
                allPermits.forEach { permit ->
                    try {
                        android.util.Log.d("HomeViewModel", "🗑️ Deleting: ${permit.permitNumber}")
                        
                        // Delete from Firebase first
                        val firebaseResult = firebaseService.deletePermit(permit.id)
                        if (firebaseResult.isSuccess) {
                            android.util.Log.d("HomeViewModel", "✅ Firebase deleted: ${permit.id}")
                        } else {
                            android.util.Log.w("HomeViewModel", "⚠️ Firebase deletion failed: ${firebaseResult.exceptionOrNull()}")
                        }
                        
                        // Delete from local database
                        permitRepository.deletePermit(permit)
                        android.util.Log.d("HomeViewModel", "✅ Local deleted: ${permit.permitNumber}")
                        
                    } catch (e: Exception) {
                        android.util.Log.e("HomeViewModel", "❌ Failed to delete permit: ${permit.permitNumber}", e)
                    }
                }
                
                // Clear the permits state
                _uiState.value = _uiState.value.copy(
                    error = null
                )
                
                // Refresh the list to show empty state
                loadRecentPermits()
                
                android.util.Log.d("HomeViewModel", "🎉 Cleanup complete! All test permits deleted.")
                
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "❌ Cleanup failed", e)
                _uiState.value = _uiState.value.copy(
                    error = "Cleanup failed: ${e.message}"
                )
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    override fun onCleared() {
        super.onCleared()
        textRecognitionService.close()
    }
}

data class HomeUiState(
    val isProcessing: Boolean = false,
    val error: String? = null,
    val lastProcessedPermit: Permit? = null
)