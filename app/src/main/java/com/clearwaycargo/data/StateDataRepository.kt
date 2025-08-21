package com.clearwaycargo.data

import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

data class DotContact(
    val agency: String?,
    val phone: String?,
    val url: String?,
    val hours: String?
)

data class StateData(
    val rulesJson: String?,
    val contact: DotContact?
)

object StateDataRepository {
    
    private const val TAG = "StateDataRepository"
    private const val STATE_DATA_PATH = "state_data"
    
    private val storage = FirebaseStorage.getInstance()
    private val stateDataCache = mutableMapOf<String, StateData>()
    
    /**
     * Load rules and contact data for any US state
     * 
     * @param stateCode Two-letter state code (e.g., "IN", "OH", "CA")
     * @return StateData with rules JSON and DOT contact info
     */
    suspend fun load(stateCode: String): StateData = withContext(Dispatchers.IO) {
        val normalizedState = stateCode.lowercase()
        
        // Check cache first
        stateDataCache[normalizedState]?.let { cached ->
            Log.d(TAG, "Returning cached state data for: $normalizedState")
            return@withContext cached
        }
        
        val rulesJson = loadRulesJson(normalizedState)
        val contact = loadDotContact(normalizedState)
        
        val stateData = StateData(rulesJson = rulesJson, contact = contact)
        
        // Cache the result
        stateDataCache[normalizedState] = stateData
        Log.d(TAG, "✅ Loaded and cached state data for: $normalizedState")
        
        return@withContext stateData
    }
    
    /**
     * Load state rules JSON from Firebase Storage
     */
    private suspend fun loadRulesJson(stateCode: String): String? {
        return try {
            val rulesRef = storage.reference.child("$STATE_DATA_PATH/$stateCode/rules.json")
            Log.d(TAG, "Loading rules: $STATE_DATA_PATH/$stateCode/rules.json")
            
            val maxDownloadSize = 2L * 1024L * 1024L // 2MB max for rules file
            val bytes = rulesRef.getBytes(maxDownloadSize).await()
            val rulesJson = String(bytes, StandardCharsets.UTF_8)
            
            Log.d(TAG, "✅ Rules loaded for $stateCode (${bytes.size} bytes)")
            rulesJson
            
        } catch (e: Exception) {
            Log.w(TAG, "❌ Failed to load rules for state: $stateCode", e)
            null
        }
    }
    
    /**
     * Load DOT contact info - prefer contacts.json, fallback to contacts.pdf extraction
     */
    private suspend fun loadDotContact(stateCode: String): DotContact? {
        // Try JSON first (preferred)
        loadContactJson(stateCode)?.let { return it }
        
        // Fallback to PDF extraction
        return loadContactFromPdf(stateCode)
    }
    
    /**
     * Load contact info from contacts.json
     */
    private suspend fun loadContactJson(stateCode: String): DotContact? {
        return try {
            val contactRef = storage.reference.child("$STATE_DATA_PATH/$stateCode/contacts.json")
            Log.d(TAG, "Loading contacts: $STATE_DATA_PATH/$stateCode/contacts.json")
            
            val maxDownloadSize = 512L * 1024L // 512KB max for contact file
            val bytes = contactRef.getBytes(maxDownloadSize).await()
            val contactsJson = String(bytes, StandardCharsets.UTF_8)
            
            val json = JSONObject(contactsJson)
            val contact = DotContact(
                agency = json.optString("agency").takeIf { it.isNotBlank() },
                phone = json.optString("phone").takeIf { it.isNotBlank() },
                url = json.optString("url").takeIf { it.isNotBlank() },
                hours = json.optString("hours").takeIf { it.isNotBlank() }
            )
            
            Log.d(TAG, "✅ Contact JSON loaded for $stateCode")
            contact
            
        } catch (e: Exception) {
            Log.d(TAG, "No contacts.json found for $stateCode, will try PDF")
            null
        }
    }
    
    /**
     * Extract contact info from contacts.pdf using simple regex patterns
     */
    private suspend fun loadContactFromPdf(stateCode: String): DotContact? {
        return try {
            val pdfRef = storage.reference.child("$STATE_DATA_PATH/$stateCode/contacts.pdf")
            Log.d(TAG, "Loading contacts PDF: $STATE_DATA_PATH/$stateCode/contacts.pdf")
            
            val maxDownloadSize = 5L * 1024L * 1024L // 5MB max for PDF
            val bytes = pdfRef.getBytes(maxDownloadSize).await()
            
            // Simple PDF text extraction (this is basic - would need proper PDF library for complex PDFs)
            val pdfText = extractTextFromPdfBytes(bytes)
            
            val contact = extractContactFromText(pdfText)
            Log.d(TAG, "✅ Contact PDF processed for $stateCode")
            contact
            
        } catch (e: Exception) {
            Log.w(TAG, "❌ Failed to load contacts PDF for state: $stateCode", e)
            null
        }
    }
    
    /**
     * Basic PDF text extraction (simplified approach)
     * In production, use a proper PDF library like Apache PDFBox
     */
    private fun extractTextFromPdfBytes(bytes: ByteArray): String {
        // This is a very basic approach - just look for readable text in the PDF
        // For production use, integrate Apache PDFBox or similar library
        val text = String(bytes, StandardCharsets.UTF_8)
        
        // Extract printable characters and common PDF text patterns
        val cleanText = text.replace(Regex("[\\x00-\\x1f\\x7f-\\xff]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        return cleanText
    }
    
    /**
     * Extract DOT contact info from text using regex patterns
     */
    private fun extractContactFromText(text: String): DotContact {
        val phonePattern = Pattern.compile("\\b(?:\\+?1[-.]?)?\\(?([0-9]{3})\\)?[-.]?([0-9]{3})[-.]?([0-9]{4})\\b")
        val urlPattern = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE)
        val emailPattern = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b")
        
        // Extract phone number
        val phoneMatcher = phonePattern.matcher(text)
        val phone = if (phoneMatcher.find()) {
            "${phoneMatcher.group(1)}-${phoneMatcher.group(2)}-${phoneMatcher.group(3)}"
        } else null
        
        // Extract URL
        val urlMatcher = urlPattern.matcher(text)
        val url = if (urlMatcher.find()) urlMatcher.group() else null
        
        // Extract agency name (look for common DOT agency patterns)
        val agencyPatterns = listOf(
            "Department of Transportation",
            "DOT",
            "Motor Vehicle",
            "Highway Department",
            "Transportation Department"
        )
        
        val agency = agencyPatterns.firstOrNull { pattern ->
            text.contains(pattern, ignoreCase = true)
        }
        
        // Extract hours (look for hour patterns)
        val hoursPattern = Pattern.compile("(\\d{1,2}:\\d{2}\\s*[AaPp][Mm]?\\s*-\\s*\\d{1,2}:\\d{2}\\s*[AaPp][Mm]?)", Pattern.CASE_INSENSITIVE)
        val hoursMatcher = hoursPattern.matcher(text)
        val hours = if (hoursMatcher.find()) hoursMatcher.group() else null
        
        return DotContact(
            agency = agency,
            phone = phone,
            url = url,
            hours = hours
        )
    }
    
    /**
     * Clear the cache (useful for testing or data updates)
     */
    fun clearCache() {
        stateDataCache.clear()
        Log.d(TAG, "State data cache cleared")
    }
    
    /**
     * Preload data for multiple states (optional optimization)
     */
    suspend fun preloadStates(stateCodes: List<String>) {
        stateCodes.forEach { stateCode ->
            try {
                load(stateCode)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload state data for: $stateCode", e)
            }
        }
    }
}