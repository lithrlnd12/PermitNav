package com.permitnav.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class Route(
    val id: String,
    val permitId: String,
    val origin: Location,
    val destination: Location,
    val waypoints: List<Location> = emptyList(),
    val distance: Double,
    val duration: Long,
    val restrictions: List<RouteRestriction> = emptyList(),
    val turnByTurnInstructions: List<NavigationInstruction> = emptyList(),
    val polyline: String,
    val tollCost: Double? = null,
    val avoidances: List<String> = emptyList()
) : Parcelable

@Parcelize
@Serializable
data class Location(
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val name: String? = null
) : Parcelable

@Parcelize
@Serializable
data class RouteRestriction(
    val type: RestrictionType,
    val value: String,
    val location: Location? = null
) : Parcelable

@Serializable
enum class RestrictionType {
    HEIGHT_LIMIT,
    WEIGHT_LIMIT,
    WIDTH_LIMIT,
    LENGTH_LIMIT,
    HAZMAT_PROHIBITED,
    TIME_RESTRICTION,
    NO_TRUCKS,
    BRIDGE_RESTRICTION,
    TUNNEL_RESTRICTION
}

@Parcelize
@Serializable
data class NavigationInstruction(
    val instruction: String,
    val distance: Double,
    val duration: Long,
    val maneuver: String,
    val location: Location,
    val streetName: String? = null,
    val exitNumber: String? = null
) : Parcelable