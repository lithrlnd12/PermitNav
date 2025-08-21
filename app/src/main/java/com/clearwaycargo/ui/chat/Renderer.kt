package com.clearwaycargo.ui.chat

import com.clearwaycargo.ai.ComplianceCore
import com.clearwaycargo.data.DotContact

/**
 * Renderer for voice and text chat responses
 * Keeps responses short and natural for dispatcher-style communication
 */
object Renderer {
    
    /**
     * Convert summary to voice-optimized line
     * @param summary The AI-generated summary text
     * @return Voice-safe string (~45 words max, ~220 characters)
     */
    fun toVoiceLine(summary: String): String {
        return summary.take(220).trim()
    }
    
    /**
     * Convert summary to text line for chat bubble
     * @param summary The AI-generated summary text
     * @return Text for chat UI (same content as voice, UI handles wrapping)
     */
    fun toTextLine(summary: String): String {
        return summary.trim()
    }
    
    /**
     * Create a fallback response when AI summarizer fails
     * Uses deterministic logic based on ComplianceCore
     */
    fun createFallbackResponse(core: ComplianceCore, contact: DotContact?): String {
        return when (core.verdict) {
            "compliant" -> "Compliant for this route based on current rules."
            "not_compliant" -> buildString {
                append("Not compliant for this route")
                if (core.reasons.isNotEmpty()) {
                    append(" - ${core.reasons.first()}")
                }
                append(".")
                if (contact?.phone != null) {
                    append(" Contact ${contact.agency ?: "DOT"} at ${contact.phone}.")
                }
            }
            "uncertain" -> buildString {
                append("I'm not fully confident")
                if (core.reasons.isNotEmpty()) {
                    append(" - ${core.reasons.first()}")
                }
                append(".")
                if (contact?.phone != null) {
                    append(" Contact ${contact.agency ?: "state DOT"} at ${contact.phone}.")
                } else {
                    append(" Contact the state DOT.")
                }
            }
            else -> "Unable to determine compliance. Contact the state DOT for verification."
        }
    }
    
    /**
     * Determine if DOT contact should be included in response
     */
    fun shouldIncludeDotContact(core: ComplianceCore): Boolean {
        return core.verdict != "compliant" || 
               core.needsHuman || 
               core.confidence < 0.7
    }
    
    /**
     * Create a short contact chip text for UI
     */
    fun createContactChip(contact: DotContact?): String? {
        return when {
            contact?.phone != null -> "Call DOT"
            contact?.url != null -> "Visit DOT Site"
            else -> null
        }
    }
    
    /**
     * Create escort hints text for display
     */
    fun createEscortHints(escortHints: List<String>): String? {
        return if (escortHints.isNotEmpty()) {
            "Escorts: ${escortHints.joinToString(", ")}"
        } else null
    }
    
    /**
     * Format confidence for display
     */
    fun formatConfidence(confidence: Double): String {
        return "${(confidence * 100).toInt()}%"
    }
}