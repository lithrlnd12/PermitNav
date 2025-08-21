package com.permitnav.data

import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.nio.charset.StandardCharsets

class StateRulesRepository {
    
    private val storage = FirebaseStorage.getInstance()
    private val rulesCache = mutableMapOf<String, String?>()
    
    companion object {
        private const val TAG = "StateRulesRepository"
        private const val RULES_PATH = "assets/state_rules"
    }
    
    /**
     * Load rules for a specific state from Firebase Storage.
     * Returns null if the rules file doesn't exist or can't be loaded.
     * 
     * @param stateCode Two-letter state code (e.g., "IN", "OH", "CA")
     * @return JSON string of state rules, or null if not found
     */
    suspend fun loadRulesForState(stateCode: String): String? = withContext(Dispatchers.IO) {
        val normalizedState = stateCode.lowercase()
        
        // Check cache first
        if (rulesCache.containsKey(normalizedState)) {
            Log.d(TAG, "Returning cached rules for state: $normalizedState")
            return@withContext rulesCache[normalizedState]
        }
        
        try {
            val rulesRef = storage.reference.child("$RULES_PATH/$normalizedState.json")
            Log.d(TAG, "Fetching rules from Firebase: $RULES_PATH/$normalizedState.json")
            
            val maxDownloadSize = 1024L * 1024L // 1MB max for rules file
            val bytes = rulesRef.getBytes(maxDownloadSize).await()
            val rulesJson = String(bytes, StandardCharsets.UTF_8)
            
            // Cache the result
            rulesCache[normalizedState] = rulesJson
            Log.d(TAG, "✅ Successfully loaded rules for state: $normalizedState (${bytes.size} bytes)")
            
            rulesJson
            
        } catch (e: Exception) {
            Log.w(TAG, "❌ Failed to load rules for state: $normalizedState", e)
            // Cache the null result to avoid repeated failed attempts
            rulesCache[normalizedState] = null
            null
        }
    }
    
    /**
     * Clear the rules cache (useful for testing or if rules are updated)
     */
    fun clearCache() {
        rulesCache.clear()
        Log.d(TAG, "Rules cache cleared")
    }
    
    /**
     * Preload rules for multiple states (optional optimization)
     */
    suspend fun preloadStatesRules(stateCodes: List<String>) {
        stateCodes.forEach { stateCode ->
            try {
                loadRulesForState(stateCode)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload rules for state: $stateCode", e)
            }
        }
    }
}