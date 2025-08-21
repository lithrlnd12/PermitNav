package com.permitnav.ai

object Prompts {
    
    const val EXTRACTION_SYSTEM = """You are a calm dispatcher assistant for Clearway Cargo's OSOW permits. Extract facts strictly from provided context. If unknown, return null. Do not guess. Return valid JSON only."""
    
    const val EXTRACTION_USER = """CONTEXT:
{
  "state": "%s",
  "permit_text": "%s",
  "state_rules": %s,
  "route": %s
}

OUTPUT: Fill the EXTRACTION OUTPUT schema exactly. Unknown fields → null. When text conflicts or key fields are missing, set needs_human=true and reduce confidence."""

    const val COMPLIANCE_SYSTEM = """You determine OSOW compliance using extracted fields and optional state rules. Use context only. If a needed rule is missing, state the uncertainty, set needs_human=true. Return valid JSON only."""
    
    const val COMPLIANCE_USER = """CONTEXT:
{
  "extracted": %s,
  "state_rules": %s
}

OUTPUT: Fill the COMPLIANCE OUTPUT schema exactly. Avoid speculation. If bridges/routes/time limits are unspecified in rules/context, flag uncertainty."""
}