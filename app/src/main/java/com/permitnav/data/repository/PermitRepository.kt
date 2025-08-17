package com.permitnav.data.repository

import com.permitnav.data.database.PermitDao
import com.permitnav.data.models.Permit
import kotlinx.coroutines.flow.Flow
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermitRepository @Inject constructor(
    private val permitDao: PermitDao
) {
    
    fun getAllPermits(): Flow<List<Permit>> = permitDao.getAllPermits()
    
    fun getValidPermits(): Flow<List<Permit>> = permitDao.getValidPermits()
    
    fun getActivePermits(): Flow<List<Permit>> = permitDao.getActivePermits(Date())
    
    suspend fun getPermitById(permitId: String): Permit? = permitDao.getPermitById(permitId)
    
    suspend fun getPermitByNumber(permitNumber: String): Permit? = permitDao.getPermitByNumber(permitNumber)
    
    fun getPermitsByState(state: String): Flow<List<Permit>> = permitDao.getPermitsByState(state)
    
    suspend fun insertPermit(permit: Permit) = permitDao.insertPermit(permit)
    
    suspend fun updatePermit(permit: Permit) = permitDao.updatePermit(permit)
    
    suspend fun deletePermit(permit: Permit) = permitDao.deletePermit(permit)
    
    suspend fun deleteExpiredPermits() = permitDao.deleteExpiredPermits(Date())
    
    suspend fun updatePermitValidation(permitId: String, isValid: Boolean, errors: List<String>) {
        permitDao.updatePermitValidation(permitId, isValid, errors)
    }
    
    suspend fun getPermitCount(): Int = permitDao.getPermitCount()
    
    suspend fun getValidPermitCount(): Int = permitDao.getValidPermitCount()
}