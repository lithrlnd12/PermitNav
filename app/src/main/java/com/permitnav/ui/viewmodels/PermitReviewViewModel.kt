package com.permitnav.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.Permit
import com.permitnav.data.repository.PermitRepository
import com.permitnav.ocr.PermitParser
import com.permitnav.ocr.TextRecognitionService
import com.permitnav.rules.ComplianceEngine
import com.permitnav.rules.ComplianceResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PermitReviewViewModel(
    private val context: Context
) : ViewModel() {
    
    private var currentPermitId: String? = null
    
    private val database = PermitNavDatabase.getDatabase(context)
    private val permitRepository = PermitRepository(database.permitDao())
    private val textRecognitionService = TextRecognitionService(context)
    private val permitParser = PermitParser()
    private val complianceEngine = ComplianceEngine(context)
    
    private val _uiState = MutableStateFlow(PermitReviewUiState())
    val uiState: StateFlow<PermitReviewUiState> = _uiState.asStateFlow()
    
    fun processPermitImage(imageUri: Uri, permitId: String? = null) {
        currentPermitId = permitId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            
            try {
                // Step 1: Extract text using OCR
                android.util.Log.d("PermitReview", "Starting OCR processing for URI: $imageUri")
                val extractedText = textRecognitionService.processImage(imageUri)
                android.util.Log.d("PermitReview", "OCR extracted text: '$extractedText'")
                
                if (extractedText.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        extractedText = "",
                        error = "No text found in image. Please ensure the permit is clearly visible and well-lit."
                    )
                    return@launch
                }
                
                // Step 2: Parse the text into permit data
                android.util.Log.d("PermitReview", "Parsing extracted text")
                val permit = permitParser.parseIndiana(extractedText)
                android.util.Log.d("PermitReview", "Parsed permit: ${permit.permitNumber}")
                
                // Step 3: Validate the permit
                val complianceResult = complianceEngine.validatePermit(permit)
                
                // Step 4: Update permit with validation results
                val validatedPermit = permit.copy(
                    id = currentPermitId ?: permit.id, // Use the passed permitId if available
                    isValid = complianceResult.isCompliant,
                    validationErrors = complianceResult.violations,
                    rawImagePath = imageUri.toString(),
                    ocrText = extractedText
                )
                
                // Save to database
                permitRepository.insertPermit(validatedPermit)
                
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    extractedText = extractedText,
                    permit = validatedPermit,
                    complianceResult = complianceResult
                )
                
            } catch (e: Exception) {
                android.util.Log.e("PermitReview", "Error processing permit", e)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Failed to process permit: ${e.message}"
                )
            }
        }
    }
    
    fun updateExtractedText(newText: String) {
        viewModelScope.launch {
            try {
                // Re-parse with updated text
                val permit = permitParser.parseIndiana(newText)
                val complianceResult = complianceEngine.validatePermit(permit)
                
                val validatedPermit = permit.copy(
                    isValid = complianceResult.isCompliant,
                    validationErrors = complianceResult.violations,
                    ocrText = newText
                )
                
                _uiState.value = _uiState.value.copy(
                    extractedText = newText,
                    permit = validatedPermit,
                    complianceResult = complianceResult
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to parse updated text: ${e.message}"
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

data class PermitReviewUiState(
    val isProcessing: Boolean = false,
    val extractedText: String = "",
    val permit: Permit? = null,
    val complianceResult: ComplianceResult? = null,
    val error: String? = null
)