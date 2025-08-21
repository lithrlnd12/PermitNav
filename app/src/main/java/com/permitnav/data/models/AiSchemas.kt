package com.permitnav.data.models

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class ExtractionResult(
    val permitInfo: PermitInfo,
    val vehicleInfo: AiVehicleInfo,
    val dimensions: Dimensions,
    val complianceInputsFound: List<String>,
    val confidence: Double,
    val needs_human: Boolean,
    val sources_used: List<String>
)

@Serializable
data class PermitInfo(
    val state: String?,
    val permitNumber: String?,
    val issueDate: String?, // YYYY-MM-DD format
    val expirationDate: String?, // YYYY-MM-DD format
    val permitType: String? // "annual", "single-trip", "special", or null
)

@Serializable
data class AiVehicleInfo(
    val licensePlate: String?,
    val vin: String?,
    val carrierName: String?,
    val usdot: String?
)

@Serializable
data class Dimensions(
    val length: Double?,
    val width: Double?,
    val height: Double?,
    val weight: Double?,
    val axles: Int?
)

@Serializable
data class ComplianceResult(
    val isCompliant: Boolean?,
    val violations: List<String>,
    val warnings: List<String>,
    val recommendations: List<String>,
    val requiredActions: List<String>,
    val stateSpecificNotes: List<String>,
    val confidence: Double,
    val needs_human: Boolean,
    val sources_used: List<String>
)

// Helper extension for confidence clamping
fun Double.clamp01(): Double = max(0.0, min(1.0, this))