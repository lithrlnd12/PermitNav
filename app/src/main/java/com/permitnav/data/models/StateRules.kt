package com.permitnav.data.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)

@Serializable
data class StateRules(
    val state: String,
    val stateCode: String,
    val maxDimensions: MaxDimensions,
    val permitTypes: List<PermitType>,
    val restrictions: List<StateRestriction>,
    val requiredFields: List<String>,
    val routeRequirements: RouteRequirements,
    val timeRestrictions: List<TimeRestriction>,
    val escortRequirements: EscortRequirements
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class MaxDimensions(
    val maxWeight: Double,
    val maxHeight: Double,
    val maxWidth: Double,
    val maxLength: Double,
    val maxOverhangFront: Double,
    val maxOverhangRear: Double,
    val maxAxleWeight: Double,
    val maxAxles: Int
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class PermitType(
    val code: String,
    val name: String,
    val description: String,
    val maxDuration: Int,
    val allowedDimensions: MaxDimensions
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class StateRestriction(
    val type: String,
    val description: String,
    val condition: String?,
    val value: String?
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class RouteRequirements(
    val requiresSpecificRoute: Boolean,
    val requiresStateApprovedRoute: Boolean,
    val allowedHighways: List<String>,
    val prohibitedHighways: List<String>,
    val bridgeRestrictions: List<BridgeRestriction>
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class BridgeRestriction(
    val bridgeId: String,
    val name: String,
    val maxWeight: Double,
    val location: String
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class TimeRestriction(
    val description: String,
    val startTime: String,
    val endTime: String,
    val daysOfWeek: List<String>,
    val applicableCondition: String?
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class EscortRequirements(
    val frontEscortRequired: EscortCondition,
    val rearEscortRequired: EscortCondition,
    val policeEscortRequired: EscortCondition
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class EscortCondition(
    val required: Boolean,
    val widthThreshold: Double?,
    val lengthThreshold: Double?,
    val weightThreshold: Double?,
    val conditions: List<String>
)