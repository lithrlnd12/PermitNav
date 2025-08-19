/**
 * PDF-Powered Chat Handler for State Permit Compliance
 * Uses official state regulation PDFs as primary information source
 */

import { initializeApp } from 'firebase/app';
import { getFirestore, doc, getDoc } from 'firebase/firestore';
import { getStorage, ref, getDownloadURL } from 'firebase/storage';
import OpenAI from 'openai';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Initialize Firebase
const firebaseConfig = {
  projectId: "permit-nav",
  storageBucket: "permit-nav.firebasestorage.app"
};

const app = initializeApp(firebaseConfig);
const db = getFirestore(app);
const storage = getStorage(app);
const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

/**
 * Main PDF-powered chat handler
 * @param {string} permitId - ID of the permit to reference
 * @param {string} userMessage - The driver's question
 * @param {Object} permitData - Optional permit metadata
 */
export async function handlePDFChat(permitId, userMessage, permitData = null) {
  try {
    console.log(`🔍 Processing chat for permit: ${permitId}`);
    console.log(`❓ Question: ${userMessage}`);

    // Step 1: Get permit metadata if not provided
    let permit = permitData;
    if (!permit && permitId) {
      const permitRef = doc(db, "permits", permitId);
      const permitSnap = await getDoc(permitRef);
      
      if (!permitSnap.exists()) {
        return { 
          error: "Permit not found",
          confidence: 0 
        };
      }
      permit = permitSnap.data();
    }

    const state = permit?.state || "IN"; // Default to Indiana
    console.log(`🏛️ State: ${state}`);

    // Step 2: Get PDF content from local storage (already processed)
    const pdfContent = await loadStatePDFContent(state);
    if (!pdfContent) {
      return {
        error: `State regulations for ${state} not available`,
        confidence: 0,
        contactInfo: getDefaultContactInfo(state)
      };
    }

    // Step 3: Extract contact information from PDF
    const contactInfo = extractContactInfo(pdfContent, state);

    // Step 4: Create structured prompt with information hierarchy
    const systemPrompt = createSystemPrompt(state, contactInfo);
    const userPrompt = createUserPrompt(userMessage, permit, pdfContent);

    // Step 5: Query OpenAI with PDF content and hierarchy instructions
    const completion = await openai.chat.completions.create({
      model: "gpt-4o-mini",
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: userPrompt }
      ],
      temperature: 0.1, // Low temperature for regulatory accuracy
      response_format: { type: "json_object" }
    });

    const response = JSON.parse(completion.choices[0].message.content);
    
    console.log(`✅ Response generated with confidence: ${response.confidence}`);
    
    return {
      answer: response.answer,
      sources: response.sources,
      confidence: response.confidence,
      contactInfo: response.confidence < 0.8 ? contactInfo : null,
      state: state,
      permitNumber: permit?.permitNumber
    };

  } catch (error) {
    console.error("❌ Error in PDF chat handler:", error);
    return {
      error: "Failed to process question",
      confidence: 0,
      contactInfo: getDefaultContactInfo(permit?.state || "IN")
    };
  }
}

/**
 * Load and return cached PDF content for a state
 */
async function loadStatePDFContent(state) {
  try {
    // Map state codes to PDF filenames
    const stateFileMap = {
      'AL': 'Alabama_OSOW_Permit_Process.pdf',
      'AK': 'Alaska_OSOW_Permit_Process.pdf', 
      'AZ': 'Arizona_OSOW_Permit_Process.pdf',
      'AR': 'Arkansas_OSOW_Permit_Binder.pdf',
      'CA': 'California_OSOW_Permit_Binder.pdf',
      'CO': 'Colorado_OSOW_Permit_Binder.pdf',
      'CT': 'Connecticut_OSOW_Permit_Binder.pdf',
      'DE': 'Delaware_OSOW_Permit_Binder.pdf',
      'FL': 'Florida_OSOW_Permit_Binder.pdf',
      'GA': 'Georgia_OSOW_Permit_Binder.pdf',
      'HI': 'Hawaii_OSOW_Permit_Binder.pdf',
      'ID': 'Idaho_OSOW_Permit_Binder.pdf',
      'IL': 'Permit_Nav_Illinois_OSOW_Binder.pdf',
      'IN': 'Permit_Nav_Indiana_OSOW_Binder.pdf',
      'IA': 'Permit_Nav_Iowa_OSOW_Binder.pdf',
      'KS': 'Permit_Nav_Kansas_OSOW_Binder.pdf',
      'KY': 'Permit_Nav_Kentucky_OSOW_Binder.pdf',
      'LA': 'Permit_Nav_Louisiana_OSOW_Binder.pdf',
      'ME': 'Permit_Nav_Maine_OSOW_Binder.pdf',
      'MD': 'Permit_Nav_Maryland_OSOW_Binder.pdf',
      'MA': 'Permit_Nav_Massachusetts_OSOW_Binder.pdf',
      'MI': 'Permit_Nav_Michigan_OSOW_Binder.pdf',
      'MN': 'Permit_Nav_Minnesota_OSOW_Binder.pdf',
      'MS': 'Permit_Nav_Mississippi_OSOW_Binder.pdf',
      'MO': 'Permit_Nav_Missouri_OSOW_Binder.pdf',
      'MT': 'Permit_Nav_Montana_OSOW_Binder.pdf',
      'NE': 'Permit_Nav_Nebraska_OSOW_Binder.pdf',
      'NV': 'Permit_Nav_Nevada_OSOW_Binder.pdf',
      'NH': 'Permit_Nav_New_Hampshire_OSOW_Binder.pdf',
      'NJ': 'Permit_Nav_New_Jersey_OSOW_Binder.pdf',
      'NM': 'Permit_Nav_New_Mexico_OSOW_Binder.pdf',
      'NY': 'Permit_Nav_New_York_OSOW_Binder.pdf',
      'NC': 'Permit_Nav_North_Carolina_OSOW_Binder.pdf',
      'ND': 'Permit_Nav_North_Dakota_OSOW_Binder.pdf',
      'OH': 'Permit_Nav_Ohio_OSOW_Binder.pdf',
      'OK': 'Permit_Nav_Oklahoma_OSOW_Binder.pdf',
      'OR': 'Oregon_OSOW_Permit_Process.pdf',
      'PA': 'Permit_Nav_Pennsylvania_OSOW_Binder.pdf',
      'RI': 'Permit_Nav_Rhode_Island_OSOW_Binder.pdf',
      'SC': 'Permit_Nav_South_Carolina_OSOW_Binder.pdf',
      'SD': 'Permit_Nav_South_Dakota_OSOW_Binder.pdf',
      'TN': 'Permit_Nav_Tennessee_OSOW_Binder.pdf',
      'TX': 'Permit_Nav_Texas_OSOW_Binder.pdf',
      'UT': 'Permit_Nav_Utah_OSOW_Binder.pdf',
      'VT': 'Permit_Nav_Vermont_OSOW_Binder.pdf',
      'VA': 'Permit_Nav_Virginia_OSOW_Binder.pdf',
      'WA': 'Permit_Nav_Washington_OSOW_Binder.pdf',
      'WV': 'Permit_Nav_West_Virginia_OSOW_Binder.pdf',
      'WI': 'Permit_Nav_Wisconsin_OSOW_Binder.pdf',
      'WY': 'Permit_Nav_Wyoming_OSOW_Binder.pdf'
    };

    const filename = stateFileMap[state.toUpperCase()];
    if (!filename) {
      console.warn(`⚠️ No PDF mapping found for state: ${state}`);
      return null;
    }

    // Check if we have a cached text version
    const textFile = path.join(__dirname, 'pdf_cache', `${state.toUpperCase()}_content.txt`);
    
    if (fs.existsSync(textFile)) {
      console.log(`📄 Loading cached PDF content for ${state}`);
      return fs.readFileSync(textFile, 'utf8');
    }

    console.log(`⚠️ No cached content found for ${state}. Need to process PDF first.`);
    return null;

  } catch (error) {
    console.error(`❌ Error loading PDF content for ${state}:`, error);
    return null;
  }
}

/**
 * Create system prompt with information hierarchy
 */
function createSystemPrompt(state, contactInfo) {
  return `You are a professional trucking permit compliance assistant for ${state.toUpperCase()} state.

INFORMATION HIERARCHY (CRITICAL - Follow this order):
1. FIRST: Use information from the provided state regulation PDF (highest priority)
2. SECOND: Use your knowledge base about trucking regulations
3. THIRD: Use general web knowledge if relevant
4. FALLBACK: If confidence is low, provide official contact information

RESPONSE FORMAT:
Always respond with JSON containing:
{
  "answer": "Your detailed compliance response",
  "sources": ["PDF Section X.X", "State regulation Y", etc.],
  "confidence": 0.0-1.0 (float indicating your confidence in the answer),
  "reasoning": "Brief explanation of information sources used"
}

CONFIDENCE GUIDELINES:
- 0.9-1.0: Answer directly from PDF content
- 0.7-0.8: Answer from PDF + your knowledge 
- 0.5-0.6: Answer mostly from your knowledge
- 0.0-0.4: Uncertain - recommend contacting officials

COMPLIANCE RULES:
- Always prioritize official PDF regulations over general knowledge
- Cite specific PDF sections when possible
- Be conservative with interpretations
- Recommend official contact for complex cases
- Include relevant permit dimensions/restrictions in analysis

Official Contact Information:
${JSON.stringify(contactInfo, null, 2)}`;
}

/**
 * Create user prompt with permit context and PDF content
 */
function createUserPrompt(userMessage, permit, pdfContent) {
  let prompt = `QUESTION: ${userMessage}\n\n`;

  if (permit) {
    prompt += `PERMIT CONTEXT:\n`;
    prompt += `- Permit Number: ${permit.permitNumber}\n`;
    prompt += `- State: ${permit.state}\n`;
    if (permit.dimensions) {
      prompt += `- Dimensions: ${permit.dimensions.width}'W x ${permit.dimensions.height}'H x ${permit.dimensions.length}'L\n`;
      prompt += `- Weight: ${permit.dimensions.weight} lbs\n`;
    }
    if (permit.restrictions && permit.restrictions.length > 0) {
      prompt += `- Restrictions: ${permit.restrictions.join(', ')}\n`;
    }
    prompt += `\n`;
  }

  // Include relevant PDF content (truncated for token limits)
  const maxPdfLength = 8000; // Adjust based on model limits
  const truncatedContent = pdfContent.length > maxPdfLength 
    ? pdfContent.substring(0, maxPdfLength) + "\n\n[PDF content truncated...]"
    : pdfContent;

  prompt += `STATE REGULATION PDF CONTENT:\n${truncatedContent}\n\n`;
  prompt += `Please analyze this question using the information hierarchy and provide a compliance response.`;

  return prompt;
}

/**
 * Extract contact information from PDF content
 */
function extractContactInfo(pdfContent, state) {
  // Simple regex patterns to find contact info
  const phonePattern = /(\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4})/g;
  const emailPattern = /([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/g;
  const websitePattern = /(https?:\/\/[^\s]+|www\.[^\s]+)/g;

  const phones = [...new Set(pdfContent.match(phonePattern) || [])];
  const emails = [...new Set(pdfContent.match(emailPattern) || [])];
  const websites = [...new Set(pdfContent.match(websitePattern) || [])];

  return {
    state: state.toUpperCase(),
    department: `${state.toUpperCase()} Department of Transportation`,
    phones: phones.slice(0, 3), // Limit to most relevant
    emails: emails.slice(0, 3),
    websites: websites.slice(0, 2),
    lastUpdated: new Date().toISOString()
  };
}

/**
 * Get default contact info when PDF parsing fails
 */
function getDefaultContactInfo(state) {
  const stateNames = {
    'IN': 'Indiana',
    'IL': 'Illinois', 
    'OH': 'Ohio',
    'MI': 'Michigan',
    'KY': 'Kentucky'
    // Add more as needed
  };

  return {
    state: state.toUpperCase(),
    department: `${stateNames[state.toUpperCase()] || state.toUpperCase()} Department of Transportation`,
    message: "Contact information not available. Please search online for current contact details.",
    phones: [],
    emails: [],
    websites: []
  };
}

// Export for testing
export { loadStatePDFContent, extractContactInfo };