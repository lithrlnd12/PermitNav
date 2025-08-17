package com.permitnav.data.database

import androidx.room.*
import com.permitnav.data.models.Permit
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface PermitDao {
    
    @Query("SELECT * FROM permits ORDER BY createdAt DESC")
    fun getAllPermits(): Flow<List<Permit>>
    
    @Query("SELECT * FROM permits WHERE isValid = 1 ORDER BY createdAt DESC")
    fun getValidPermits(): Flow<List<Permit>>
    
    @Query("SELECT * FROM permits WHERE expirationDate > :currentDate ORDER BY createdAt DESC")
    fun getActivePermits(currentDate: Date): Flow<List<Permit>>
    
    @Query("SELECT * FROM permits WHERE id = :permitId")
    suspend fun getPermitById(permitId: String): Permit?
    
    @Query("SELECT * FROM permits WHERE permitNumber = :permitNumber LIMIT 1")
    suspend fun getPermitByNumber(permitNumber: String): Permit?
    
    @Query("SELECT * FROM permits WHERE state = :state ORDER BY createdAt DESC")
    fun getPermitsByState(state: String): Flow<List<Permit>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPermit(permit: Permit)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPermits(permits: List<Permit>)
    
    @Update
    suspend fun updatePermit(permit: Permit)
    
    @Delete
    suspend fun deletePermit(permit: Permit)
    
    @Query("DELETE FROM permits WHERE id = :permitId")
    suspend fun deletePermitById(permitId: String)
    
    @Query("DELETE FROM permits WHERE expirationDate < :currentDate")
    suspend fun deleteExpiredPermits(currentDate: Date)
    
    @Query("UPDATE permits SET isValid = :isValid, validationErrors = :errors WHERE id = :permitId")
    suspend fun updatePermitValidation(permitId: String, isValid: Boolean, errors: List<String>)
    
    @Query("SELECT COUNT(*) FROM permits")
    suspend fun getPermitCount(): Int
    
    @Query("SELECT COUNT(*) FROM permits WHERE isValid = 1")
    suspend fun getValidPermitCount(): Int
}