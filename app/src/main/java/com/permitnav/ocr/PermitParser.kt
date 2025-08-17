package com.permitnav.ocr

import com.permitnav.data.models.Permit
import com.permitnav.data.models.TruckDimensions
import com.permitnav.data.models.VehicleInfo
import java.text.SimpleDateFormat
import java.util.*

class PermitParser {
    
    private val dateFormats = listOf(
        SimpleDateFormat("MM/dd/yyyy", Locale.US),
        SimpleDateFormat("MM-dd-yyyy", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
        SimpleDateFormat("MMM dd, yyyy", Locale.US)
    )
    
    fun parseIndiana(text: String): Permit {
        val cleanedText = text.replace("\n", " ").replace(Regex("\\s+"), " ")
        val fields = extractIndianaFields(cleanedText)
        
        return Permit(
            id = UUID.randomUUID().toString(),
            state = "IN",
            permitNumber = fields["permitNumber"] ?: generatePermitNumber(),
            issueDate = parseDate(fields["issueDate"]) ?: Date(),
            expirationDate = parseDate(fields["expirationDate"]) ?: calculateExpirationDate(),
            vehicleInfo = extractVehicleInfo(fields),
            dimensions = extractDimensions(fields),
            routeDescription = fields["route"],
            restrictions = extractRestrictions(cleanedText),
            rawImagePath = null,
            ocrText = text,
            isValid = false,
            validationErrors = emptyList()
        )
    }
    
    private fun extractIndianaFields(text: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        
        val permitNumberPattern = Regex("(?:Permit\\s*#?|Permit\\s*Number:?)\\s*([A-Z0-9-]+)", RegexOption.IGNORE_CASE)
        permitNumberPattern.find(text)?.let { 
            fields["permitNumber"] = it.groupValues[1]
        }
        
        val issueDatePattern = Regex("(?:Issue\\s*Date:?|Issued:?)\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})", RegexOption.IGNORE_CASE)
        issueDatePattern.find(text)?.let {
            fields["issueDate"] = it.groupValues[1]
        }
        
        val expirationPattern = Regex("(?:Expir(?:es?|ation):?|Valid\\s*(?:Through|Until):?)\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})", RegexOption.IGNORE_CASE)
        expirationPattern.find(text)?.let {
            fields["expirationDate"] = it.groupValues[1]
        }
        
        val platePattern = Regex("(?:License\\s*Plate:?|Plate\\s*#?:?)\\s*([A-Z0-9-]+)", RegexOption.IGNORE_CASE)
        platePattern.find(text)?.let {
            fields["licensePlate"] = it.groupValues[1]
        }
        
        val vinPattern = Regex("(?:VIN:?|Vehicle\\s*ID:?)\\s*([A-Z0-9]{17})", RegexOption.IGNORE_CASE)
        vinPattern.find(text)?.let {
            fields["vin"] = it.groupValues[1]
        }
        
        val weightPattern = Regex("(?:Gross\\s*Weight:?|GVW:?|Weight:?)\\s*(\\d+(?:,\\d{3})?(?:\\.\\d+)?)\\s*(?:lbs?|pounds?)?", RegexOption.IGNORE_CASE)
        weightPattern.find(text)?.let {
            fields["weight"] = it.groupValues[1].replace(",", "")
        }
        
        val lengthPattern = Regex("(?:Overall\\s*Length:?|Length:?)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:ft|feet|')?", RegexOption.IGNORE_CASE)
        lengthPattern.find(text)?.let {
            fields["length"] = it.groupValues[1]
        }
        
        val widthPattern = Regex("(?:Overall\\s*Width:?|Width:?)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:ft|feet|')?", RegexOption.IGNORE_CASE)
        widthPattern.find(text)?.let {
            fields["width"] = it.groupValues[1]
        }
        
        val heightPattern = Regex("(?:Overall\\s*Height:?|Height:?)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:ft|feet|')?", RegexOption.IGNORE_CASE)
        heightPattern.find(text)?.let {
            fields["height"] = it.groupValues[1]
        }
        
        val axlesPattern = Regex("(?:Number\\s*of\\s*Axles:?|Axles:?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
        axlesPattern.find(text)?.let {
            fields["axles"] = it.groupValues[1]
        }
        
        val routePattern = Regex("(?:Route:?|From.*?To|Origin.*?Destination)\\s*([^.]+)", RegexOption.IGNORE_CASE)
        routePattern.find(text)?.let {
            fields["route"] = it.groupValues[1].trim()
        }
        
        val makePattern = Regex("(?:Make:?)\\s*([A-Za-z]+)", RegexOption.IGNORE_CASE)
        makePattern.find(text)?.let {
            fields["make"] = it.groupValues[1]
        }
        
        val unitPattern = Regex("(?:Unit\\s*#?:?|Unit\\s*Number:?)\\s*([A-Z0-9-]+)", RegexOption.IGNORE_CASE)
        unitPattern.find(text)?.let {
            fields["unitNumber"] = it.groupValues[1]
        }
        
        return fields
    }
    
    private fun extractVehicleInfo(fields: Map<String, String>): VehicleInfo {
        return VehicleInfo(
            make = fields["make"],
            model = fields["model"],
            year = fields["year"]?.toIntOrNull(),
            licensePlate = fields["licensePlate"],
            vin = fields["vin"],
            unitNumber = fields["unitNumber"]
        )
    }
    
    private fun extractDimensions(fields: Map<String, String>): TruckDimensions {
        return TruckDimensions(
            length = fields["length"]?.toDoubleOrNull(),
            width = fields["width"]?.toDoubleOrNull(),
            height = fields["height"]?.toDoubleOrNull(),
            weight = fields["weight"]?.toDoubleOrNull(),
            axles = fields["axles"]?.toIntOrNull(),
            overhangFront = fields["overhangFront"]?.toDoubleOrNull(),
            overhangRear = fields["overhangRear"]?.toDoubleOrNull()
        )
    }
    
    private fun extractRestrictions(text: String): List<String> {
        val restrictions = mutableListOf<String>()
        
        if (text.contains("daylight", ignoreCase = true)) {
            restrictions.add("Daylight hours only")
        }
        if (text.contains("no sunday", ignoreCase = true) || text.contains("except sunday", ignoreCase = true)) {
            restrictions.add("No Sunday travel")
        }
        if (text.contains("escort", ignoreCase = true)) {
            restrictions.add("Escort required")
        }
        if (text.contains("interstate", ignoreCase = true) && text.contains("prohibited", ignoreCase = true)) {
            restrictions.add("Interstate travel prohibited")
        }
        if (text.contains("weather permit", ignoreCase = true)) {
            restrictions.add("Weather permitting")
        }
        if (text.contains("holiday", ignoreCase = true)) {
            restrictions.add("No holiday travel")
        }
        
        return restrictions
    }
    
    private fun parseDate(dateString: String?): Date? {
        dateString ?: return null
        
        for (format in dateFormats) {
            try {
                return format.parse(dateString)
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }
    
    private fun calculateExpirationDate(): Date {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 5)
        return calendar.time
    }
    
    private fun generatePermitNumber(): String {
        return "IN-${System.currentTimeMillis()}"
    }
    
    fun parseGeneric(text: String, state: String): Permit {
        return when (state.uppercase()) {
            "IN", "INDIANA" -> parseIndiana(text)
            else -> parseIndiana(text)
        }
    }
}