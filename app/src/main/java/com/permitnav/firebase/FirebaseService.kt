package com.permitnav.firebase

import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.permitnav.data.models.Permit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Firebase service for permit management
 * Handles Firestore database operations and Storage file uploads
 */
class FirebaseService {
    
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    
    companion object {
        private const val TAG = "FirebaseService"
        private const val PERMITS_COLLECTION = "permits"
        private const val STORAGE_PATH_PERMITS = "permits"
    }
    
    /**
     * Get current authenticated user ID
     * Returns null if not authenticated
     */
    private fun getCurrentUserId(): String? {
        return auth.currentUser?.uid
    }
    
    /**
     * Save permit to Firestore
     */
    suspend fun savePermit(permit: Permit): Result<String> = withContext(Dispatchers.IO) {
        try {
            val permitData = hashMapOf(
                "permitNumber" to permit.permitNumber,
                "state" to permit.state,
                "driverId" to (getCurrentUserId() ?: "anonymous"),
                "status" to if (permit.isValid) "validated" else "pending",
                "dimensions" to mapOf(
                    "length" to permit.dimensions.length,
                    "width" to permit.dimensions.width,
                    "height" to permit.dimensions.height,
                    "weight" to permit.dimensions.weight,
                    "axles" to permit.dimensions.axles
                ),
                "vehicleInfo" to mapOf(
                    "licensePlate" to permit.vehicleInfo.licensePlate,
                    "vin" to permit.vehicleInfo.vin,
                    "make" to permit.vehicleInfo.make,
                    "model" to permit.vehicleInfo.model
                ),
                "issueDate" to permit.issueDate,
                "expirationDate" to permit.expirationDate,
                "restrictions" to permit.restrictions,
                "origin" to permit.origin,
                "destination" to permit.destination,
                "routeDescription" to permit.routeDescription,
                "ocrText" to permit.ocrText,
                "processingMethod" to permit.processingMethod.name,
                "validationErrors" to permit.validationErrors,
                "createdAt" to FieldValue.serverTimestamp(),
                "lastModified" to FieldValue.serverTimestamp()
            )
            
            val documentRef = db.collection(PERMITS_COLLECTION).add(permitData).await()
            Log.d(TAG, "✅ Permit saved to Firestore with ID: ${documentRef.id}")
            Result.success(documentRef.id)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save permit to Firestore", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get all permits for current driver
     */
    suspend fun getPermitsForDriver(driverId: String? = null): Result<List<Map<String, Any>>> = withContext(Dispatchers.IO) {
        try {
            val userId = driverId ?: getCurrentUserId()
            if (userId == null) {
                return@withContext Result.failure(Exception("User not authenticated"))
            }
            
            val querySnapshot = db.collection(PERMITS_COLLECTION)
                .whereEqualTo("driverId", userId)
                .get()
                .await()
            
            val permits = querySnapshot.documents.map { document ->
                val data = document.data?.toMutableMap() ?: mutableMapOf()
                data["id"] = document.id
                data.toMap()
            }
            
            Log.d(TAG, "✅ Retrieved ${permits.size} permits for driver $userId")
            Result.success(permits)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get permits for driver", e)
            Result.failure(e)
        }
    }
    
    /**
     * Upload permit file to Firebase Storage
     */
    suspend fun uploadPermitFile(
        fileUri: Uri,
        permitId: String,
        driverId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val userId = driverId ?: getCurrentUserId()
            if (userId == null) {
                return@withContext Result.failure(Exception("User not authenticated"))
            }
            
            val storageRef = storage.reference.child("$STORAGE_PATH_PERMITS/$userId/$permitId.pdf")
            
            Log.d(TAG, "📤 Uploading file to: $STORAGE_PATH_PERMITS/$userId/$permitId.pdf")
            
            // Upload file
            val uploadTask = storageRef.putFile(fileUri).await()
            Log.d(TAG, "✅ File uploaded successfully! Size: ${uploadTask.totalByteCount} bytes")
            
            // Get download URL
            val downloadUrl = storageRef.downloadUrl.await()
            Log.d(TAG, "🔗 File download URL: $downloadUrl")
            
            Result.success(downloadUrl.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to upload file", e)
            Result.failure(e)
        }
    }
    
    /**
     * Upload multiple permit images
     */
    suspend fun uploadPermitImages(
        imageUris: List<Uri>,
        permitId: String,
        driverId: String? = null
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val userId = driverId ?: getCurrentUserId()
            if (userId == null) {
                return@withContext Result.failure(Exception("User not authenticated"))
            }
            
            val uploadResults = mutableListOf<String>()
            
            imageUris.forEachIndexed { index, uri ->
                val storageRef = storage.reference.child("$STORAGE_PATH_PERMITS/$userId/$permitId/page_$index.jpg")
                
                val uploadTask = storageRef.putFile(uri).await()
                val downloadUrl = storageRef.downloadUrl.await()
                
                uploadResults.add(downloadUrl.toString())
                Log.d(TAG, "✅ Uploaded image $index: $downloadUrl")
            }
            
            Log.d(TAG, "✅ All ${imageUris.size} images uploaded successfully")
            Result.success(uploadResults)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to upload images", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update permit status
     */
    suspend fun updatePermitStatus(permitId: String, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            db.collection(PERMITS_COLLECTION)
                .document(permitId)
                .update(
                    mapOf(
                        "status" to status,
                        "lastModified" to FieldValue.serverTimestamp()
                    )
                ).await()
            
            Log.d(TAG, "✅ Updated permit $permitId status to: $status")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update permit status", e)
            Result.failure(e)
        }
    }
    
    /**
     * Delete permit and associated files
     */
    suspend fun deletePermit(permitId: String, driverId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = driverId ?: getCurrentUserId()
            if (userId == null) {
                return@withContext Result.failure(Exception("User not authenticated"))
            }
            
            // Delete from Firestore
            db.collection(PERMITS_COLLECTION).document(permitId).delete().await()
            
            // Delete files from Storage
            val storageRef = storage.reference.child("$STORAGE_PATH_PERMITS/$userId/$permitId")
            try {
                storageRef.delete().await()
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete storage files (may not exist): ${e.message}")
            }
            
            Log.d(TAG, "✅ Deleted permit $permitId")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to delete permit", e)
            Result.failure(e)
        }
    }
    
    /**
     * Search permits by state
     */
    suspend fun searchPermitsByState(state: String, driverId: String? = null): Result<List<Map<String, Any>>> = withContext(Dispatchers.IO) {
        try {
            val userId = driverId ?: getCurrentUserId()
            if (userId == null) {
                return@withContext Result.failure(Exception("User not authenticated"))
            }
            
            val querySnapshot = db.collection(PERMITS_COLLECTION)
                .whereEqualTo("driverId", userId)
                .whereEqualTo("state", state)
                .get()
                .await()
            
            val permits = querySnapshot.documents.map { document ->
                val data = document.data?.toMutableMap() ?: mutableMapOf()
                data["id"] = document.id
                data.toMap()
            }
            
            Log.d(TAG, "✅ Found ${permits.size} permits for state $state and user $userId")
            Result.success(permits)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to search permits by state", e)
            Result.failure(e)
        }
    }
}