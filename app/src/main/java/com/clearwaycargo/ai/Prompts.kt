package com.clearwaycargo.ai

/**
 * Prompts for the summarizer-only AI approach
 * The model only summarizes ComplianceCore results - it does NOT look up laws
 */
object Prompts {
    
    /**
     * System prompt for compliance summarization
     * The AI is just a dispatcher assistant that explains our analysis results
     */
    const val SYSTEM_SUMMARY = """You are Clearway Cargo's dispatcher. Speak naturally and briefly (1–3 short sentences). Use ONLY the provided comparison result and contact. Do not invent rules. If confidence is low or anything is uncertain, say so plainly and include the DOT contact. No markdown, no emojis."""
    
    /**
     * Create user prompt for summarizing compliance results
     * 
     * @param coreJson JSON string of ComplianceCore result
     * @param contactJson JSON string of DotContact (nullable)
     * @return Formatted prompt for the AI summarizer
     */
    fun userSummaryPrompt(coreJson: String, contactJson: String?): String = """
COMPARISON_RESULT:
$coreJson

DOT_CONTACT:
${contactJson ?: "null"}

TASK:
Explain compliance for the driver in 1–3 short sentences. If uncertain or restrictions likely apply, tell them what to verify and include the DOT contact (name or phone). Keep under ~45 words total.
    """.trimIndent()
}