package com.permitnav.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.Permit
import com.permitnav.data.repository.PermitRepository
import com.permitnav.ocr.PermitParser
import com.permitnav.ocr.TextRecognitionService
import com.permitnav.rules.ComplianceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val context: Context
) : ViewModel() {
    
    private val database = PermitNavDatabase.getDatabase(context)
    private val permitRepository = PermitRepository(database.permitDao())
    private val textRecognitionService = TextRecognitionService(context)
    private val permitParser = PermitParser()
    private val complianceEngine = ComplianceEngine(context)
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    private val _recentPermits = MutableStateFlow<List<Permit>>(emptyList())
    val recentPermits: StateFlow<List<Permit>> = _recentPermits.asStateFlow()
    
    init {
        loadRecentPermits()
    }
    
    private fun loadRecentPermits() {
        viewModelScope.launch {
            permitRepository.getAllPermits().collect { permits ->
                _recentPermits.value = permits.take(5)
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