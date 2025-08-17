package com.permitnav.ocr

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class PermitParserTest {
    
    private lateinit var permitParser: PermitParser
    
    @Before
    fun setup() {
        permitParser = PermitParser()
    }
    
    @Test
    fun parseIndiana_extractsPermitNumber() {
        val ocrText = "State of Indiana Permit Number: IN-2024-001234 Issue Date: 01/15/2024"
        
        val permit = permitParser.parseIndiana(ocrText)
        
        assertEquals("IN-2024-001234", permit.permitNumber)
        assertEquals("IN", permit.state)
    }
    
    @Test
    fun parseIndiana_extractsDimensions() {
        val ocrText = """
            Indiana Oversize Permit
            Weight: 85,000 lbs
            Length: 95 ft
            Width: 12 ft
            Height: 14.5 ft
        """.trimIndent()
        
        val permit = permitParser.parseIndiana(ocrText)
        
        assertEquals(85000.0, permit.dimensions.weight, 0.1)
        assertEquals(95.0, permit.dimensions.length, 0.1)
        assertEquals(12.0, permit.dimensions.width, 0.1)
        assertEquals(14.5, permit.dimensions.height, 0.1)
    }
    
    @Test
    fun parseIndiana_extractsVehicleInfo() {
        val ocrText = """
            License Plate: ABC123
            VIN: 1HGCM82633A004352
            Make: PETERBILT
        """.trimIndent()
        
        val permit = permitParser.parseIndiana(ocrText)
        
        assertEquals("ABC123", permit.vehicleInfo.licensePlate)
        assertEquals("1HGCM82633A004352", permit.vehicleInfo.vin)
        assertEquals("PETERBILT", permit.vehicleInfo.make)
    }
    
    @Test
    fun parseIndiana_extractsRestrictions() {
        val ocrText = """
            Indiana Permit
            Travel restricted to daylight hours only
            No Sunday travel permitted
            Escort required for overwidth loads
        """.trimIndent()
        
        val permit = permitParser.parseIndiana(ocrText)
        
        assertTrue(permit.restrictions.contains("Daylight hours only"))
        assertTrue(permit.restrictions.contains("No Sunday travel"))
        assertTrue(permit.restrictions.contains("Escort required"))
    }
    
    @Test
    fun parseIndiana_handlesEmptyText() {
        val permit = permitParser.parseIndiana("")
        
        assertNotNull(permit)
        assertTrue(permit.permitNumber.startsWith("IN-"))
        assertEquals("IN", permit.state)
    }
    
    @Test
    fun parseGeneric_delegatesToIndiana() {
        val ocrText = "Permit Number: IN-2024-567890"
        
        val permit = permitParser.parseGeneric(ocrText, "IN")
        
        assertEquals("IN-2024-567890", permit.permitNumber)
        assertEquals("IN", permit.state)
    }
}