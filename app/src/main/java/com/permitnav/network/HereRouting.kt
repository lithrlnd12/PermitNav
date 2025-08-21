@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.permitnav.network

import com.permitnav.BuildConfig
import com.permitnav.data.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import com.permitnav.navigation.FlexiblePolylineDecoder

class HereRoutingService {
    
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }
    
    // HERE Routing API uses API key (not Access Key ID/Secret which is for SDK)
    private val apiKey = BuildConfig.HERE_API_KEY.takeIf { !it.isNullOrBlank() && it != "null" } 
        ?: "G8E5NRGjmtVxRLb_ieIwnzpexZQNkQLNbm8Z0ArfQG0"
    private val appId = BuildConfig.HERE_APP_ID.takeIf { !it.isNullOrBlank() && it != "null" } 
        ?: "SVsuPE2cHMADy38iqvLO"
    private val baseUrl = "https://router.hereapi.com/v8"
    
    suspend fun calculateTruckRoute(
        origin: Location,
        destination: Location,
        permit: Permit,
        waypoints: List<Location> = emptyList()
    ): RouteWithActions = withContext(Dispatchers.IO) {
        
        val truckParams = buildTruckParameters(permit.dimensions)
        val avoidances = buildAvoidances(permit.restrictions)
        val stateRules = loadStateRules(permit.state ?: "IN")
        
        val url = buildString {
            append("$baseUrl/routes")
            append("?apikey=$apiKey")
            append("&transportMode=truck")
            append("&origin=${origin.latitude},${origin.longitude}")
            append("&destination=${destination.latitude},${destination.longitude}")
            
            // Add waypoints with passThrough=true for required permit stops
            waypoints.forEachIndexed { index, waypoint ->
                append("&via=${waypoint.latitude},${waypoint.longitude}!passThrough=true")
            }
            
            // Return parameters for route and turn-by-turn guidance
            append("&return=polyline,actions,instructions,summary,travelSummary")
            
            append(truckParams)
            append(avoidances)
            append(stateRules)
        }
        
        try {
            android.util.Log.d("HereRouting", "Making HERE API request")
            android.util.Log.d("HereRouting", "Request URL: ${url.replace(apiKey, "***").replace(appId, "***")}")
            val httpResponse = client.get(url)
            android.util.Log.d("HereRouting", "Response status: ${httpResponse.status}")
            val responseText = httpResponse.body<String>()
            android.util.Log.d("HereRouting", "Response body: $responseText")
            
            // Try to parse as error response first
            if (httpResponse.status.value >= 400) {
                throw RoutingException("HERE API error ${httpResponse.status.value}: $responseText")
            }
            
            // Parse successful response
            val json = Json { ignoreUnknownKeys = true }
            val response = json.decodeFromString<HereRouteResponse>(responseText)
            mapHereResponseToRouteWithActions(response, permit.id, origin, destination)
        } catch (e: Exception) {
            android.util.Log.e("HereRouting", "Failed to calculate route", e)
            throw RoutingException("Failed to calculate route: ${e.message}", e)
        }
    }
    
    private fun buildTruckParameters(dimensions: TruckDimensions): String {
        return buildString {
            dimensions.height?.let { 
                // Convert to centimeters as integer (4.1m = 410cm)
                val heightInCm = (metersFromFeet(it) * 100).toInt()
                append("&truck[height]=$heightInCm")
            }
            dimensions.width?.let { 
                // Convert to centimeters as integer (3.7m = 370cm)
                val widthInCm = (metersFromFeet(it) * 100).toInt()
                append("&truck[width]=$widthInCm")
            }
            dimensions.length?.let { 
                // Convert to centimeters as integer (33.5m = 3350cm)
                val lengthInCm = (metersFromFeet(it) * 100).toInt()
                append("&truck[length]=$lengthInCm")
            }
            dimensions.weight?.let { 
                append("&truck[grossWeight]=${kgFromPounds(it).toInt()}")
            }
            dimensions.axles?.let { 
                append("&truck[axleCount]=$it")
            }
            
            // Add trailer count for tractor-trailer combinations
            append("&truck[trailerCount]=1")
        }
    }
    
    private fun buildAvoidances(restrictions: List<String>): String {
        val avoidList = mutableListOf<String>()
        
        restrictions.forEach { restriction ->
            when {
                restriction.contains("Interstate travel prohibited", ignoreCase = true) -> {
                    avoidList.add("controlledAccessHighway")
                }
                restriction.contains("tunnel", ignoreCase = true) -> {
                    avoidList.add("tunnel")
                }
                restriction.contains("ferry", ignoreCase = true) -> {
                    avoidList.add("ferry")
                }
                // Note: "bridge" is not a valid HERE API avoid feature
                // Bridge restrictions would be handled differently through truck attributes
            }
        }
        
        return if (avoidList.isNotEmpty()) {
            // Remove duplicates and join
            "&avoid[features]=${avoidList.distinct().joinToString(",")}"
        } else {
            ""
        }
    }
    
    /**
     * Load state-specific routing rules from assets/state_rules/{state}.json
     */
    private fun loadStateRules(state: String): String {
        // For MVP, return empty string
        // TODO: Implement state rule loading from assets/state_rules/{state}.json
        return ""
    }
    
    private fun mapHereResponseToRouteWithActions(
        response: HereRouteResponse,
        permitId: String,
        origin: Location,
        destination: Location
    ): RouteWithActions {
        val route = response.routes.firstOrNull() 
            ?: throw RoutingException("No route found")
        
        val section = route.sections.firstOrNull() 
            ?: throw RoutingException("No route sections found")
        
        // Get raw actions from HERE response
        val rawActions = section.actions ?: emptyList()
        android.util.Log.d("HereRouting", "HERE API returned ${rawActions.size} actions")
        
        // Validate actions
        if (rawActions.isEmpty()) {
            android.util.Log.w("HereRouting", "No actions found in HERE response - route may be invalid")
        }
        
        val mappedRoute = Route(
            id = System.currentTimeMillis().toString(),
            permitId = permitId,
            origin = origin,
            destination = destination,
            distance = section.travelSummary.length / 1609.34,
            duration = section.travelSummary.duration.toLong(), // HERE API returns duration in seconds
            polyline = section.polyline,
            turnByTurnInstructions = mapInstructions(section),
            restrictions = extractRestrictions(route),
            tollCost = section.tolls?.totalCost
        )
        
        return RouteWithActions(
            route = mappedRoute,
            rawActions = rawActions
        )
    }
    
    private fun mapInstructions(section: HereRouteSection): List<NavigationInstruction> {
        return section.actions?.mapIndexed { index, action ->
            // Calculate location based on offset into polyline
            val location = try {
                if (action.offset != null) {
                    val decodedPoints = FlexiblePolylineDecoder.decodePolyline(section.polyline)
                    if (action.offset < decodedPoints.size) {
                        val point = decodedPoints[action.offset]
                        Location(
                            latitude = point.latitude,
                            longitude = point.longitude
                        )
                    } else {
                        Location(latitude = 0.0, longitude = 0.0)
                    }
                } else {
                    Location(latitude = 0.0, longitude = 0.0)
                }
            } catch (e: Exception) {
                android.util.Log.w("HereRouting", "Failed to decode location for instruction $index", e)
                Location(latitude = 0.0, longitude = 0.0)
            }
            
            NavigationInstruction(
                instruction = action.instruction ?: "Continue",
                distance = action.length?.toDouble() ?: 0.0,
                duration = action.duration?.toLong() ?: 0L,
                maneuver = action.action ?: "continue",
                location = location,
                streetName = action.road?.name
            )
        } ?: emptyList()
    }
    
    private fun extractRestrictions(route: HereRoute): List<RouteRestriction> {
        // Since we removed truckRestrictions from spans, return empty list for now
        // TODO: Implement alternative method to get truck restrictions
        return emptyList()
    }
    
    suspend fun geocodeAddress(address: String): Location? = withContext(Dispatchers.IO) {
        val url = "https://geocode.search.hereapi.com/v1/geocode?q=$address&apikey=$apiKey"
        
        try {
            val response: GeocodeResponse = client.get(url).body()
            response.items.firstOrNull()?.let {
                Location(
                    latitude = it.position.lat,
                    longitude = it.position.lng,
                    address = it.address.label,
                    name = it.title
                )
            }
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun reverseGeocode(latitude: Double, longitude: Double): Location? = withContext(Dispatchers.IO) {
        val url = "https://revgeocode.search.hereapi.com/v1/revgeocode?at=$latitude,$longitude&apikey=$apiKey"
        
        try {
            val response: GeocodeResponse = client.get(url).body()
            response.items.firstOrNull()?.let {
                Location(
                    latitude = latitude,
                    longitude = longitude,
                    address = it.address.label,
                    name = it.title
                )
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun metersFromFeet(feet: Double): Double = feet * 0.3048
    private fun kgFromPounds(pounds: Double): Double = pounds * 0.453592
    
    fun close() {
        client.close()
    }
}

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RouteWithActions(
    val route: Route,
    val rawActions: List<RouteAction>
)

@Serializable
data class HereRouteResponse(
    val routes: List<HereRoute>
)

@Serializable
data class HereRoute(
    val id: String,
    val sections: List<HereRouteSection>
)

@Serializable
data class HereRouteSection(
    val id: String,
    val type: String,
    val polyline: String,
    val travelSummary: TravelSummary,
    val actions: List<RouteAction>? = null,
    val spans: List<RouteSpan>? = null,
    val tolls: TollInfo? = null
)

@Serializable
data class TravelSummary(
    val duration: Int,
    val length: Double,
    val baseDuration: Int? = null
)

@Serializable
data class RouteAction(
    val action: String? = null,
    val duration: Int? = null,
    val length: Int? = null,
    val instruction: String? = null,
    val offset: Int? = null,
    val road: RoadInfo? = null
)

@Serializable
data class RoadInfo(
    val name: String? = null,
    val number: String? = null
)

@Serializable
data class RouteSpan(
    val offset: Int? = null,
    val speedLimit: SpeedLimit? = null
)

@Serializable
data class SpeedLimit(
    val value: Int? = null,
    val unit: String? = null
)

@Serializable
data class TruckRestriction(
    val type: String,
    val value: Double
)

@Serializable
data class TollInfo(
    val totalCost: Double? = null,
    val currency: String? = null
)

@Serializable
data class GeocodeResponse(
    val items: List<GeocodeItem>
)

@Serializable
data class GeocodeItem(
    val title: String,
    val id: String,
    val position: Position,
    val address: Address
)

@Serializable
data class Position(
    val lat: Double,
    val lng: Double
)

@Serializable
data class Address(
    val label: String
)