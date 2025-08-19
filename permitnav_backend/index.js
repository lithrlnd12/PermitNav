/**
 * Firebase Cloud Function for PDF-Powered Chat
 * Provides REST endpoints for state permit compliance chat
 */

const functions = require('firebase-functions');
const admin = require('firebase-admin');
const express = require('express');
const cors = require('cors');
const OpenAI = require('openai');
const pdfParse = require('pdf-parse');

// Initialize Firebase Admin with explicit bucket name
admin.initializeApp({
  storageBucket: 'permit-nav.firebasestorage.app'
});
const db = admin.firestore();
const storage = admin.storage();

// Initialize OpenAI
const openai = new OpenAI({ 
  apiKey: functions.config().openai.key || process.env.OPENAI_API_KEY 
});

// Create Express app
const expressApp = express();
expressApp.use(cors());
expressApp.use(express.json({ limit: '10mb' }));

// Request logging
expressApp.use((req, res, next) => {
  console.log(`${new Date().toISOString()} - ${req.method} ${req.path}`);
  next();
});

/**
 * Health check endpoint
 */
expressApp.get('/health', (req, res) => {
  res.json({
    status: 'healthy',
    service: 'PDF Chat API (Cloud Function)',
    timestamp: new Date().toISOString(),
    version: '1.0.0'
  });
});

/**
 * Get available states
 */
expressApp.get('/api/states', (req, res) => {
  const states = [
    'AL', 'AK', 'AZ', 'AR', 'CA', 'CO', 'CT', 'DE', 'FL', 'GA',
    'HI', 'ID', 'IL', 'IN', 'IA', 'KS', 'KY', 'LA', 'ME', 'MD',
    'MA', 'MI', 'MN', 'MS', 'MO', 'MT', 'NE', 'NV', 'NH', 'NJ',
    'NM', 'NY', 'NC', 'ND', 'OH', 'OK', 'OR', 'PA', 'RI', 'SC',
    'SD', 'TN', 'TX', 'UT', 'VT', 'VA', 'WA', 'WV', 'WI', 'WY'
  ];
  
  res.json(states);
});

/**
 * Main chat endpoint - PDF-powered compliance responses
 */
expressApp.post('/api/chat', async (req, res) => {
  try {
    const { permitId, stateKey, permitText, userQuestion, conversationId } = req.body;
    
    // Validation
    if (!userQuestion || userQuestion.trim().length === 0) {
      return res.status(400).json({
        error: 'User question is required',
        code: 'MISSING_QUESTION'
      });
    }
    
    if (!permitId && !stateKey) {
      return res.status(400).json({
        error: 'Either permitId or stateKey must be provided',
        code: 'MISSING_CONTEXT'
      });
    }

    console.log(`💬 Chat request: ${userQuestion.substring(0, 100)}...`);
    console.log(`📋 Permit ID: ${permitId || 'N/A'}`);
    console.log(`🏛️ State: ${stateKey || 'From permit'}`);

    // Get regulation content from Firestore (fast!)
    const regulationData = await loadStateRegulationContent(stateKey || 'IN', userQuestion);
    const regulationContent = regulationData.content;
    const contactInfo = regulationData.contactInfo;

    // Create natural conversational prompt
    const hasPermitContext = permitText && permitText.includes('PERMIT');
    const systemPrompt = createSystemPrompt(stateKey || 'IN', contactInfo, hasPermitContext);
    const userPrompt = createUserPrompt(userQuestion, permitText, regulationContent, hasPermitContext);

    // Query OpenAI with PDF content and hierarchy instructions
    const completion = await openai.chat.completions.create({
      model: 'gpt-4o-mini',
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt }
      ],
      temperature: 0.1,
      response_format: { type: 'json_object' }
    });

    const response = JSON.parse(completion.choices[0].message.content);
    
    console.log(`✅ Response generated with confidence: ${response.confidence}`);
    
    // Transform response to match Android app expectations
    const complianceResponse = {
      is_compliant: response.confidence > 0.7,
      violations: response.violations || [],
      escorts: response.escorts || 'Contact state DOT for escort requirements',
      travel_restrictions: response.travel_restrictions || 'Check state regulations',
      notes: response.notes || 'General guidance based on available information',
      contact_info: response.confidence < 0.8 ? {
        department: contactInfo.department,
        phone: contactInfo.phones[0] || 'Contact state DOT',
        email: contactInfo.emails[0] || null,
        website: contactInfo.websites[0] || null,
        office_hours: 'Standard business hours'
      } : null,
      confidence: response.confidence || 0.7,
      sources: response.sources || ['State regulation PDFs'],
      state: stateKey,
      permitNumber: permitId
    };

    console.log(`✅ Response sent (confidence: ${response.confidence})`);
    res.json(complianceResponse);

  } catch (error) {
    console.error('❌ Chat API error:', error);
    res.status(500).json({
      error: 'Internal server error',
      message: error.message,
      confidence: 0
    });
  }
});

/**
 * Load state regulation content from Firestore (lightning fast!)
 */
async function loadStateRegulationContent(state, userQuestion = '') {
  try {
    console.log(`⚡ Loading regulations for ${state} from Firestore`);
    
    // Get state regulations from Firestore
    const stateDoc = await db.collection('state_regulations').doc(state.toUpperCase()).get();
    
    if (!stateDoc.exists) {
      console.warn(`⚠️ No regulations found for state: ${state}`);
      return {
        content: `No specific regulations available for ${state}. Please contact the state DOT directly.`,
        contactInfo: {
          state: state.toUpperCase(),
          department: `${state.toUpperCase()} Department of Transportation`,
          phones: [],
          emails: [],
          websites: []
        }
      };
    }
    
    const stateData = stateDoc.data();
    console.log(`📋 Found ${stateData.chunkCount} regulation chunks for ${state}`);
    
    // For specific questions, search relevant chunks
    // For general questions, use summary from first few chunks
    let relevantContent = '';
    
    if (userQuestion && userQuestion.length > 10) {
      // Use first 3 chunks for focused content (6000 chars max)
      relevantContent = stateData.chunks.slice(0, 3).join('\n\n');
    } else {
      // Use first 2 chunks for general questions (4000 chars max)
      relevantContent = stateData.chunks.slice(0, 2).join('\n\n');
    }
    
    console.log(`✅ Loaded ${relevantContent.length} characters for ${state}`);
    
    return {
      content: relevantContent,
      contactInfo: stateData.contactInfo || {
        state: state.toUpperCase(),
        department: `${state.toUpperCase()} Department of Transportation`,
        phones: [],
        emails: [],
        websites: []
      }
    };
    
  } catch (error) {
    console.error(`❌ Error loading regulations for ${state}:`, error);
    return {
      content: `Unable to load specific regulations for ${state}. Please contact the state DOT for current requirements.`,
      contactInfo: {
        state: state.toUpperCase(),
        department: `${state.toUpperCase()} Department of Transportation`,
        phones: [],
        emails: [],
        websites: []
      }
    };
  }
}

/**
 * Create system prompt with information hierarchy
 */
function createSystemPrompt(state, contactInfo, hasPermitContext) {
  return `You are a virtual dispatcher helping truck drivers with permit questions in ${state.toUpperCase()}.

Your job: Answer permit questions using state regulations, just like talking to a real dispatcher.

Rules:
- Use the state regulation content provided first
- If you can't find the answer there, search web for DOT contacts
- Talk like a person, not a robot - natural conversation
- Stay focused on permit/trucking topics only
- When unsure, give DOT contact info

${hasPermitContext ? 
'The driver has a specific permit - help them understand it.' : 
'General trucking question - give helpful guidance.'}

Always respond with JSON:
{
  "is_compliant": true/false,
  "violations": [],
  "escorts": "escort requirements",
  "travel_restrictions": "time/route limits", 
  "notes": "your helpful response",
  "confidence": 0.8,
  "sources": ["State regulations"]
}

DOT Contact: ${contactInfo.phones[0] || `${state.toUpperCase()} DOT`}`;
}

/**
 * Create user prompt with permit context and PDF content
 */
function createUserPrompt(userQuestion, permitText, regulationContent, hasPermitContext) {
  let prompt = `Question: ${userQuestion}\n\n`;

  if (permitText && permitText.trim() !== '') {
    prompt += `Permit info:\n${permitText}\n\n`;
  }

  prompt += `State regulations:\n${regulationContent}\n\n`;
  
  prompt += hasPermitContext ? 
    `Help me understand what this means for my permit.` :
    `What do I need to know?`;

  return prompt;
}

/**
 * Extract contact information from PDF content
 */
function extractContactInfo(pdfContent, state) {
  const phonePattern = /(\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4})/g;
  const emailPattern = /([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/g;
  const websitePattern = /(https?:\/\/[^\s]+|www\.[^\s]+)/g;

  const phones = [...new Set(pdfContent.match(phonePattern) || [])];
  const emails = [...new Set(pdfContent.match(emailPattern) || [])];
  const websites = [...new Set(pdfContent.match(websitePattern) || [])];

  return {
    state: state.toUpperCase(),
    department: `${state.toUpperCase()} Department of Transportation`,
    phones: phones.slice(0, 3),
    emails: emails.slice(0, 3),
    websites: websites.slice(0, 2),
    lastUpdated: new Date().toISOString()
  };
}

// Export the Express app as a Firebase Cloud Function with extended timeout
exports.pdfchat = functions
  .runWith({
    timeoutSeconds: 300,  // 5 minutes
    memory: '1GB'
  })
  .https.onRequest(expressApp);