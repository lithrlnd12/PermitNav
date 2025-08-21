package com.permitnav.data.models

import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class AiSchemasTest {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    @Test
    fun testExtractionResultParsing_withValidData() {
        val jsonString = """
        {
            "permitInfo": {
                "state": "IN",
                "permitNumber": "IN-2024-001234",
                "issueDate": "2024-08-15",
                "expirationDate": "2024-09-15",
                "permitType": "single-trip"
            },
            "vehicleInfo": {
                "licensePlate": "ABC123",
                "vin": "1HGBH41JXMN109186",
                "carrierName": "Clearway Cargo LLC",
                "usdot": "123456"
            },
            "dimensions": {
                "length": 75.5,
                "width": 12.0,
                "height": 14.5,
                "weight": 85000.0,
                "axles": 5
            },
            "complianceInputsFound": ["permit_number", "dimensions", "dates"],
            "confidence": 0.92,
            "needs_human": false,
            "sources_used": ["permit_text", "state_rules"]
        }
        """.trimIndent()
        
        val result = json.decodeFromString<ExtractionResult>(jsonString)
        
        assertEquals("IN", result.permitInfo.state)
        assertEquals("IN-2024-001234", result.permitInfo.permitNumber)
        assertEquals("2024-08-15", result.permitInfo.issueDate)
        assertEquals("single-trip", result.permitInfo.permitType)
        assertEquals("ABC123", result.vehicleInfo.licensePlate)
        assertEquals(75.5, result.dimensions.length!!, 0.01)
        assertEquals(5, result.dimensions.axles)
        assertEquals(0.92, result.confidence, 0.01)
        assertFalse(result.needs_human)
        assertEquals(3, result.complianceInputsFound.size)
    }
    
    @Test
    fun testExtractionResultParsing_withNulls() {
        val jsonString = """
        {
            "permitInfo": {
                "state": "OH",
                "permitNumber": null,
                "issueDate": null,
                "expirationDate": "2024-12-31",
                "permitType": null
            },
            "vehicleInfo": {
                "licensePlate": null,
                "vin": null,
                "carrierName": "Unknown Carrier",
                "usdot": null
            },
            "dimensions": {
                "length": null,
                "width": 12.5,
                "height": null,
                "weight": null,
                "axles": null
            },
            "complianceInputsFound": [],
            "confidence": 0.3,
            "needs_human": true,
            "sources_used": ["permit_text"]
        }
        """.trimIndent()
        
        val result = json.decodeFromString<ExtractionResult>(jsonString)
        
        assertEquals("OH", result.permitInfo.state)
        assertNull(result.permitInfo.permitNumber)
        assertNull(result.permitInfo.issueDate)
        assertEquals("2024-12-31", result.permitInfo.expirationDate)
        assertNull(result.vehicleInfo.licensePlate)
        assertEquals("Unknown Carrier", result.vehicleInfo.carrierName)
        assertNull(result.dimensions.length)
        assertEquals(12.5, result.dimensions.width!!, 0.01)
        assertNull(result.dimensions.axles)
        assertEquals(0.3, result.confidence, 0.01)
        assertTrue(result.needs_human)
        assertTrue(result.complianceInputsFound.isEmpty())
    }
    
    @Test
    fun testComplianceResultParsing() {
        val jsonString = """
        {
            "isCompliant": false,
            "violations": ["Exceeds maximum width for route", "Missing escort vehicle"],
            "warnings": ["Close to weight limit"],
            "recommendations": ["Add front escort", "Consider alternate route"],
            "requiredActions": ["Contact DOT for escort approval"],
            "stateSpecificNotes": ["Ohio requires specific escort certification"],
            "confidence": 0.85,
            "needs_human": false,
            "sources_used": ["extracted", "state_rules"]
        }
        """.trimIndent()
        
        val result = json.decodeFromString<ComplianceResult>(jsonString)
        
        assertEquals(false, result.isCompliant)
        assertEquals(2, result.violations.size)
        assertEquals("Exceeds maximum width for route", result.violations[0])
        assertEquals(1, result.warnings.size)
        assertEquals(2, result.recommendations.size)
        assertEquals(1, result.requiredActions.size)
        assertEquals(0.85, result.confidence, 0.01)
        assertFalse(result.needs_human)
    }
    
    @Test
    fun testComplianceResultParsing_withNullCompliance() {
        val jsonString = """
        {
            "isCompliant": null,
            "violations": [],
            "warnings": [],
            "recommendations": ["Manual review required"],
            "requiredActions": [],
            "stateSpecificNotes": [],
            "confidence": 0.4,
            "needs_human": true,
            "sources_used": ["extracted"]
        }
        """.trimIndent()
        
        val result = json.decodeFromString<ComplianceResult>(jsonString)
        
        assertNull(result.isCompliant)
        assertTrue(result.violations.isEmpty())
        assertEquals(1, result.recommendations.size)
        assertEquals("Manual review required", result.recommendations[0])
        assertEquals(0.4, result.confidence, 0.01)
        assertTrue(result.needs_human)
    }
    
    @Test
    fun testConfidenceClamp() {
        assertEquals(0.0, (-0.5).clamp01(), 0.01)
        assertEquals(0.0, 0.0.clamp01(), 0.01)
        assertEquals(0.5, 0.5.clamp01(), 0.01)
        assertEquals(1.0, 1.0.clamp01(), 0.01)
        assertEquals(1.0, 1.5.clamp01(), 0.01)
    }
    
    @Test
    fun testSerialization_roundTrip() {
        val original = ExtractionResult(
            permitInfo = PermitInfo(
                state = "TX",
                permitNumber = "TX-2024-567890",
                issueDate = "2024-08-01",
                expirationDate = "2024-09-01",
                permitType = "annual"
            ),
            vehicleInfo = AiVehicleInfo(
                licensePlate = "DEF456",
                vin = "2HGBH41JXMN109187",
                carrierName = "Test Trucking",
                usdot = "789012"
            ),
            dimensions = Dimensions(
                length = 80.0,
                width = 13.5,
                height = 15.0,
                weight = 95000.0,
                axles = 6
            ),
            complianceInputsFound = listOf("all_fields"),
            confidence = 0.95,
            needs_human = false,
            sources_used = listOf("permit_text", "state_rules")
        )
        
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<ExtractionResult>(serialized)
        
        assertEquals(original.permitInfo.state, deserialized.permitInfo.state)
        assertEquals(original.vehicleInfo.carrierName, deserialized.vehicleInfo.carrierName)
        assertEquals(original.dimensions.weight, deserialized.dimensions.weight)
        assertEquals(original.confidence, deserialized.confidence, 0.01)
        assertEquals(original.needs_human, deserialized.needs_human)
    }
    
    @Test
    fun testEmptyArrayHandling() {
        val jsonString = """
        {
            "permitInfo": {
                "state": "CA",
                "permitNumber": "CA-2024-999",
                "issueDate": "2024-08-01",
                "expirationDate": "2024-09-01",
                "permitType": "special"
            },
            "vehicleInfo": {
                "licensePlate": "GHI789",
                "vin": null,
                "carrierName": null,
                "usdot": null
            },
            "dimensions": {
                "length": 60.0,
                "width": 8.5,
                "height": 13.5,
                "weight": 75000.0,
                "axles": 4
            },
            "complianceInputsFound": [],
            "confidence": 0.7,
            "needs_human": false,
            "sources_used": []
        }
        """.trimIndent()
        
        val result = json.decodeFromString<ExtractionResult>(jsonString)
        
        assertTrue(result.complianceInputsFound.isEmpty())
        assertTrue(result.sources_used.isEmpty())
        assertEquals(0.7, result.confidence, 0.01)
    }
}