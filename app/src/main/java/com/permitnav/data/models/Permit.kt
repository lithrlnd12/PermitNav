package com.permitnav.data.models

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
@Entity(tableName = "permits")
data class Permit(
    @PrimaryKey
    val id: String,
    val state: String,
    val permitNumber: String,
    val issueDate: Date,
    val expirationDate: Date,
    val vehicleInfo: VehicleInfo,
    val dimensions: TruckDimensions,
    val routeDescription: String?,
    val origin: String?,
    val destination: String?,
    val restrictions: List<String>,
    val rawImagePath: String?, // Deprecated - kept for backwards compatibility
    val imagePaths: List<String> = emptyList(), // Multiple image paths
    val ocrText: String?, // Combined OCR text from all pages
    val ocrTexts: List<String> = emptyList(), // OCR text per page
    val aiParsedData: String? = null, // JSON response from OpenAI parsing
    val isValid: Boolean = false,
    val validationErrors: List<String> = emptyList(),
    val processingMethod: ProcessingMethod = ProcessingMethod.OCR_ONLY,
    val createdAt: Date = Date(),
    val lastModified: Date = Date()
) : Parcelable

enum class ProcessingMethod {
    OCR_ONLY,       // Google ML Kit only
    AI_ENHANCED,    // ML Kit + OpenAI
    MANUAL_ENTRY    // User entered manually
}

@Parcelize
data class VehicleInfo(
    val make: String?,
    val model: String?,
    val year: Int?,
    val licensePlate: String?,
    val vin: String?,
    val unitNumber: String?
) : Parcelable

@Parcelize
data class TruckDimensions(
    val length: Double?,
    val width: Double?,
    val height: Double?,
    val weight: Double?,
    val axles: Int?,
    val overhangFront: Double?,
    val overhangRear: Double?
) : Parcelable