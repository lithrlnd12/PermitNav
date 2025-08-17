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
    ): Route = withContext(Dispatchers.IO) {
        
        val truckParams = buildTruckParameters(permit.dimensions)
        val avoidances = buildAvoidances(permit.restrictions)
        
        val url = buildString {
            append("$baseUrl/routes")
            append("?apikey=$apiKey")
            append("&transportMode=truck")
            append("&origin=${origin.latitude},${origin.longitude}")
            append("&destination=${destination.latitude},${destination.longitude}")
            
            waypoints.forEachIndexed { index, waypoint ->
                append("&via=${waypoint.latitude},${waypoint.longitude}")
            }
            
            append("&return=polyline,actions,instructions,summary,travelSummary")
            
            append(truckParams)
            append(avoidances)
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
            mapHereResponseToRoute(response, permit.id, origin, destination)
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
        
        if (restrictions.any { it.contains("Interstate travel prohibited", ignoreCase = true) }) {
            avoidList.add("controlledAccessHighway")
        }
        
        return if (avoidList.isNotEmpty()) {
            "&avoid[features]=${avoidList.joinToString(",")}"
        } else {
            ""
        }
    }
    
    private fun mapHereResponseToRoute(
        response: HereRouteResponse,
        permitId: String,
        origin: Location,
        destination: Location
    ): Route {
        val route = response.routes.firstOrNull() 
            ?: throw RoutingException("No route found")
        
        val section = route.sections.firstOrNull() 
            ?: throw RoutingException("No route sections found")
        
        return Route(
            id = System.currentTimeMillis().toString(),
            permitId = permitId,
            origin = origin,
            destination = destination,
            distance = section.travelSummary.length / 1609.34,
            duration = section.travelSummary.duration * 1000L,
            polyline = section.polyline,
            turnByTurnInstructions = mapInstructions(section),
            restrictions = extractRestrictions(route),
            tollCost = section.tolls?.totalCost
        )
    }
    
    private fun mapInstructions(section: HereRouteSection): List<NavigationInstruction> {
        return section.actions?.map { action ->
            NavigationInstruction(
                instruction = action.instruction ?: "Continue",
                distance = action.length?.toDouble() ?: 0.0,
                duration = action.duration?.toLong() ?: 0L,
                maneuver = action.action ?: "continue",
                location = Location(
                    latitude = 0.0,
                    longitude = 0.0
                ),
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