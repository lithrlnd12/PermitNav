package com.permitnav.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.permitnav.data.database.PermitNavDatabase
import com.permitnav.data.models.Permit
import com.permitnav.data.repository.PermitRepository
import com.permitnav.ocr.PermitParser
import com.permitnav.ocr.TextRecognitionService
import com.permitnav.rules.ComplianceEngine
import com.permitnav.rules.ComplianceResult
import com.permitnav.ai.OpenAIService
import com.permitnav.firebase.FirebaseService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PermitReviewViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    private var currentPermitId: String? = null
    
    private val database = PermitNavDatabase.getDatabase(getApplication())
    private val permitRepository = PermitRepository(database.permitDao())
    private val textRecognitionService = TextRecognitionService(getApplication())
    private val permitParser = PermitParser()
    private val complianceEngine = ComplianceEngine(getApplication())
    private val openAIService = OpenAIService()
    private val firebaseService = FirebaseService()
    
    private val _uiState = MutableStateFlow(PermitReviewUiState())
    val uiState: StateFlow<PermitReviewUiState> = _uiState.asStateFlow()
    
    fun processPermitImage(imageUri: Uri, permitId: String? = null) {
        currentPermitId = permitId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            
            try {
                // Check if this is a multi-image URI (comma-separated)
                val uriString = imageUri.toString()
                val imageUris = if (uriString.contains(",")) {
                    // Multi-image case: split by comma and create Uri objects
                    uriString.split(",").map { Uri.parse(it.trim()) }
                } else {
                    // Single image case
                    listOf(imageUri)
                }
                
                android.util.Log.d("PermitReview", "Processing ${imageUris.size} images")
                
                // Step 1: Extract text using OCR from all images
                val allExtractedTexts = mutableListOf<String>()
                val imagePaths = mutableListOf<String>()
                
                for (uri in imageUris) {
                    android.util.Log.d("PermitReview", "Starting OCR processing for URI: $uri")
                    val extractedText = textRecognitionService.processImage(uri)
                    android.util.Log.d("PermitReview", "OCR extracted text from image: '$extractedText'")
                    
                    if (extractedText.isNotBlank()) {
                        allExtractedTexts.add(extractedText)
                    }
                    imagePaths.add(uri.toString())
                }
                
                if (allExtractedTexts.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        extractedText = "",
                        error = "No text found in any images. Please ensure the permit is clearly visible and well-lit."
                    )
                    return@launch
                }
                
                // Combine all extracted text
                val combinedExtractedText = allExtractedTexts.joinToString("\n\n--- PAGE BREAK ---\n\n")
                android.util.Log.d("PermitReview", "Combined OCR text: '$combinedExtractedText'")
                
                // Step 2: Determine state and use appropriate parsing method
                val detectedState = detectPermitState(combinedExtractedText)
                android.util.Log.d("PermitReview", "Detected permit state: $detectedState")
                
                // Step 3: Try national AI analysis first, fallback to local parsing
                var permit: Permit
                var complianceResult: ComplianceResult
                
                try {
                    android.util.Log.d("PermitReview", "Attempting national AI analysis")
                    val nationalAnalysis = openAIService.analyzePermitNational(
                        ocrTexts = allExtractedTexts,
                        state = detectedState
                    )
                    
                    if (nationalAnalysis.success && nationalAnalysis.analysis != null) {
                        android.util.Log.d("PermitReview", "AI analysis successful")
                        permit = nationalAnalysis.analysis
                        complianceResult = ComplianceResult(
                            isCompliant = permit.isValid,
                            violations = permit.validationErrors,
                            warnings = emptyList(),
                            suggestions = if (permit.isValid) emptyList() else listOf("Review permit details with AI analysis")
                        )
                    } else {
                        android.util.Log.d("PermitReview", "AI analysis failed, using local parsing")
                        permit = permitParser.parseIndiana(combinedExtractedText)
                        complianceResult = complianceEngine.validatePermit(permit)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PermitReview", "AI analysis error, falling back to local parsing", e)
                    permit = permitParser.parseIndiana(combinedExtractedText)
                    complianceResult = complianceEngine.validatePermit(permit)
                }
                
                android.util.Log.d("PermitReview", "Final parsed permit: ${permit.permitNumber}")
                
                // Step 4: Update permit with validation results
                val validatedPermit = permit.copy(
                    id = currentPermitId ?: permit.id, // Use the passed permitId if available
                    isValid = complianceResult.isCompliant,
                    validationErrors = complianceResult.violations,
                    imagePaths = imagePaths,
                    ocrTexts = allExtractedTexts,
                    rawImagePath = imagePaths.firstOrNull(), // Backward compatibility
                    ocrText = combinedExtractedText
                )
                
                // Save to local database
                permitRepository.insertPermit(validatedPermit)
                
                // Also save to Firebase (cloud backup)
                viewModelScope.launch {
                    try {
                        val result = firebaseService.savePermit(validatedPermit)
                        if (result.isSuccess) {
                            android.util.Log.d("PermitReview", "✅ Permit saved to Firebase: ${result.getOrNull()}")
                        } else {
                            android.util.Log.e("PermitReview", "❌ Failed to save to Firebase: ${result.exceptionOrNull()}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PermitReview", "❌ Firebase save error", e)
                    }
                }
                
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    extractedText = combinedExtractedText,
                    permit = validatedPermit,
                    complianceResult = complianceResult,
                    imageCount = imageUris.size
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
    
    /**
     * Detect the permit state from OCR text
     * This helps route to the correct analysis method
     */
    private fun detectPermitState(ocrText: String): String {
        val text = ocrText.uppercase()
        
        // Common patterns for state detection
        val statePatterns = mapOf(
            "INDIANA" to "IN",
            "ILLINOIS" to "IL", 
            "OHIO" to "OH",
            "MICHIGAN" to "MI",
            "KENTUCKY" to "KY",
            "CALIFORNIA" to "CA",
            "TEXAS" to "TX",
            "FLORIDA" to "FL",
            "NEW YORK" to "NY",
            "PENNSYLVANIA" to "PA"
            // Add more states as needed
        )
        
        // Look for state names in the text
        for ((stateName, stateCode) in statePatterns) {
            if (text.contains(stateName) || text.contains("STATE OF $stateName")) {
                return stateCode
            }
        }
        
        // Look for state codes in permit numbers (e.g., "IN-2024-001234")
        val permitNumberPattern = Regex("([A-Z]{2})-?\\d{4}-?\\d+")
        val match = permitNumberPattern.find(text)
        if (match != null) {
            return match.groupValues[1]
        }
        
        // Default to Indiana (our initial implementation)
        return "IN"
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
    val error: String? = null,
    val imageCount: Int = 0
)