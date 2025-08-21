package com.permitnav.ui.chat

import com.permitnav.data.models.ExtractionResult
import com.permitnav.data.models.ComplianceResult

data class ChatResponse(
    val message: String,
    val provenanceChips: List<String>,
    val needsHuman: Boolean,
    val confidence: Double
)

object Renderer {
    
    /**
     * Convert AI analysis results into human-friendly chat response
     */
    fun renderAnalysis(
        extracted: ExtractionResult?,
        compliance: ComplianceResult?
    ): ChatResponse {
        
        // Handle case where extraction failed
        if (extracted == null) {
            return ChatResponse(
                message = "I'm having trouble reading your permit. Could you try taking a clearer photo, or I can connect you with our DOT specialists for manual review.",
                provenanceChips = emptyList(),
                needsHuman = true,
                confidence = 0.0
            )
        }
        
        // Check if human intervention is needed
        if (extracted.needs_human || (compliance?.needs_human == true)) {
            return renderNeedsHuman(extracted, compliance)
        }
        
        // Render successful analysis
        return renderSuccess(extracted, compliance)
    }
    
    private fun renderNeedsHuman(
        extracted: ExtractionResult,
        compliance: ComplianceResult?
    ): ChatResponse {
        val state = extracted.permitInfo.state ?: "your state"
        val message = "Some permit details are unclear from the scan. I can connect you with $state DOT specialists who can review this manually and provide definitive guidance."
        
        val chips = buildProvenanceChips(extracted, compliance)
        val confidence = minOf(extracted.confidence, compliance?.confidence ?: 0.0)
        
        return ChatResponse(
            message = message,
            provenanceChips = chips,
            needsHuman = true,
            confidence = confidence
        )
    }
    
    private fun renderSuccess(
        extracted: ExtractionResult,
        compliance: ComplianceResult?
    ): ChatResponse {
        val permitNumber = extracted.permitInfo.permitNumber ?: "your permit"
        val state = extracted.permitInfo.state?.uppercase() ?: "the state"
        
        val summary = if (compliance?.isCompliant == true) {
            "Great news! $permitNumber appears compliant with $state regulations."
        } else {
            "I've reviewed $permitNumber against $state regulations and found some concerns."
        }
        
        val details = mutableListOf<String>()
        
        // Add violations
        compliance?.violations?.let { violations ->
            if (violations.isNotEmpty()) {
                details.add("**Violations:**")
                violations.forEach { details.add("• $it") }
            }
        }
        
        // Add warnings
        compliance?.warnings?.let { warnings ->
            if (warnings.isNotEmpty()) {
                details.add("**Warnings:**")
                warnings.forEach { details.add("• $it") }
            }
        }
        
        // Add recommendations
        compliance?.recommendations?.let { recommendations ->
            if (recommendations.isNotEmpty()) {
                details.add("**Recommendations:**")
                recommendations.forEach { details.add("• $it") }
            }
        }
        
        // Add required actions
        compliance?.requiredActions?.let { actions ->
            if (actions.isNotEmpty()) {
                details.add("**Required Actions:**")
                actions.forEach { details.add("• $it") }
            }
        }
        
        val fullMessage = if (details.isNotEmpty()) {
            summary + "\n\n" + details.joinToString("\n")
        } else {
            summary
        }
        
        val chips = buildProvenanceChips(extracted, compliance)
        val confidence = minOf(extracted.confidence, compliance?.confidence ?: extracted.confidence)
        
        return ChatResponse(
            message = fullMessage,
            provenanceChips = chips,
            needsHuman = false,
            confidence = confidence
        )
    }
    
    private fun buildProvenanceChips(
        extracted: ExtractionResult,
        compliance: ComplianceResult?
    ): List<String> {
        val chips = mutableListOf<String>()
        
        // Sources used
        val allSources = (extracted.sources_used + (compliance?.sources_used ?: emptyList())).distinct()
        if (allSources.contains("permit_text")) {
            chips.add("From: Permit PDF")
        }
        if (allSources.contains("state_rules")) {
            chips.add("+ State rules")
        }
        
        // Confidence level
        val confidence = minOf(extracted.confidence, compliance?.confidence ?: extracted.confidence)
        chips.add("Confidence: ${String.format("%.2f", confidence)}")
        
        return chips
    }
    
    /**
     * Render extraction-only results (when compliance analysis isn't available)
     */
    fun renderExtractionOnly(extracted: ExtractionResult): ChatResponse {
        val permitNumber = extracted.permitInfo.permitNumber ?: "your permit"
        val state = extracted.permitInfo.state?.uppercase() ?: "Unknown state"
        
        val message = if (extracted.needs_human) {
            "I was able to scan some details from $permitNumber, but some information is unclear. Let me connect you with $state DOT specialists for a complete review."
        } else {
            "I've extracted the key details from $permitNumber. The permit appears to be from $state. Would you like me to check compliance against state regulations?"
        }
        
        return ChatResponse(
            message = message,
            provenanceChips = listOf("From: Permit PDF", "Confidence: ${String.format("%.2f", extracted.confidence)}"),
            needsHuman = extracted.needs_human,
            confidence = extracted.confidence
        )
    }
}