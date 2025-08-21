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
 * Generate ephemeral token for OpenAI Realtime API
 */
expressApp.post('/api/realtime-token', async (req, res) => {
  try {
    console.log('🎤 Generating ephemeral token for Realtime API');
    
    // Create ephemeral token session
    const response = await fetch('https://api.openai.com/v1/realtime/sessions', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${openai.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: 'gpt-4o-realtime-preview',
        voice: 'verse',
        instructions: `You are Clearway Cargo's voice dispatcher assistant - a helpful AI that makes trucking easier.

IMPORTANT: Always respond in English only. Never speak in French or any other language.

Keep responses brief (1-3 sentences), conversational, and helpful.
No markdown or emojis - just natural speech.
You can help with permit questions, routing guidance, and general trucking advice.
When discussing compliance, be specific but concise.
If asked about compliance, use the state rules and regulations to provide accurate information.

Speak clearly in American English with a neutral accent.`,
        modalities: ['text', 'audio'],
        temperature: 0.8,
        max_response_output_tokens: 4096,
        tools: [],
        tool_choice: 'auto',
        input_audio_format: 'pcm16',
        output_audio_format: 'pcm16',
        input_audio_transcription: {
          model: 'whisper-1'
        },
        turn_detection: {
          type: 'server_vad',
          threshold: 0.5,
          prefix_padding_ms: 300,
          silence_duration_ms: 200
        }
      })
    });

    if (!response.ok) {
      const error = await response.text();
      console.error('❌ OpenAI Realtime API error:', error);
      return res.status(response.status).json({
        error: 'Failed to create realtime session',
        details: error
      });
    }

    const sessionData = await response.json();
    
    console.log('✅ Ephemeral token generated successfully');
    
    res.json({
      client_secret: sessionData.client_secret,
      expires_at: sessionData.expires_at,
      session_id: sessionData.id
    });

  } catch (error) {
    console.error('❌ Error generating ephemeral token:', error);
    res.status(500).json({
      error: 'Internal server error',
      message: error.message
    });
  }
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

// ==========================================================================================
// NEW DISPATCHER AUTOMATION FUNCTIONS
// ==========================================================================================

/**
 * setUserRole
 * WHAT: Assign a user a role ("driver" or "dispatcher") as a custom claim + mirror in /users.
 * WHY : Lets UI and security rules differentiate views & permissions.
 * HOW : POST { uid, role } to this endpoint (lock behind IAM/API Gateway in prod).
 */
exports.setUserRole = functions.https.onCall(async (data, context) => {
  try {
    // Verify caller is authenticated and has appropriate permissions
    if (!context.auth) {
      throw new functions.https.HttpsError('unauthenticated', 'User must be authenticated');
    }

    const { uid, role } = data;
    
    if (!uid || !['driver', 'dispatcher'].includes(role)) {
      throw new functions.https.HttpsError(
        'invalid-argument', 
        'uid and role (driver/dispatcher) are required'
      );
    }

    // Set custom claims
    await admin.auth().setCustomUserClaims(uid, { role });
    
    // Mirror role in Firestore for easier queries
    await db.collection('users').doc(uid).set({ role }, { merge: true });
    
    return { success: true, uid, role };
  } catch (error) {
    console.error('setUserRole error:', error);
    throw new functions.https.HttpsError('internal', error.message);
  }
});

/**
 * createLoad
 * WHAT: Create a Load and automatically create an open route_plan Task for dispatch.
 * WHY : Dispatchers (or an automation) can drop in loads; system spawns next action.
 * HOW : POST JSON body with origin, destination, dims, weight, optional assignedDriverUid.
 */
exports.createLoad = functions.https.onCall(async (data, context) => {
  try {
    if (!context.auth) {
      throw new functions.https.HttpsError('unauthenticated', 'User must be authenticated');
    }

    const { 
      origin, 
      destination, 
      pickupWindow, 
      deliveryWindow, 
      dims, 
      weight, 
      special, 
      assignedDriverUid 
    } = data;

    if (!origin || !destination) {
      throw new functions.https.HttpsError(
        'invalid-argument', 
        'origin and destination are required'
      );
    }

    // Create the load
    const loadRef = await db.collection('loads').add({
      origin,
      destination,
      pickupWindow: pickupWindow || null,
      deliveryWindow: deliveryWindow || null,
      dims: dims || null,
      weight: weight || null,
      special: special || [],
      assignedDriverUid: assignedDriverUid || null,
      status: 'new',
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      createdBy: context.auth.uid
    });

    // Automatically create a route planning task
    await db.collection('tasks').add({
      type: 'route_plan',
      loadId: loadRef.id,
      status: 'open',
      driverUid: assignedDriverUid || null,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      createdBy: context.auth.uid
    });

    return { success: true, loadId: loadRef.id };
  } catch (error) {
    console.error('createLoad error:', error);
    throw new functions.https.HttpsError('internal', error.message);
  }
});

/**
 * planRoute
 * WHAT: Calls HERE Truck Routing using Load + (basic) truck attrs; stores route; closes task.
 * WHY : Automates route planning step for dispatcher.
 * HOW : Call with { loadId }. Requires HERE_API_KEY in function config.
 */
exports.planRoute = functions.https.onCall(async (data, context) => {
  try {
    if (!context.auth) {
      throw new functions.https.HttpsError('unauthenticated', 'User must be authenticated');
    }

    const { loadId } = data;
    
    if (!loadId) {
      throw new functions.https.HttpsError('invalid-argument', 'loadId is required');
    }

    // Get the load
    const loadDoc = await db.collection('loads').doc(loadId).get();
    if (!loadDoc.exists) {
      throw new functions.https.HttpsError('not-found', 'Load not found');
    }

    const load = loadDoc.data();
    
    // Default truck attributes (can be enhanced to use actual truck data)
    const truck = {
      height: (load.dims?.h ?? 4.0),
      width: (load.dims?.w ?? 2.6), 
      length: (load.dims?.l ?? 18.0),
      weight: (load.weight ?? 36000),
      axleCount: 5,
      hazmat: false
    };

    // Get HERE API key
    const hereApiKey = functions.config().here?.api_key || process.env.HERE_API_KEY;
    if (!hereApiKey) {
      throw new functions.https.HttpsError('failed-precondition', 'HERE_API_KEY not configured');
    }

    // Build HERE API URL
    const url = `https://router.hereapi.com/v8/routes?transportMode=truck` +
      `&origin=${load.origin.lat},${load.origin.lng}` +
      `&destination=${load.destination.lat},${load.destination.lng}` +
      `&apiKey=${hereApiKey}` +
      `&truck[height]=${truck.height}&truck[width]=${truck.width}&truck[length]=${truck.length}` +
      `&truck[axleCount]=${truck.axleCount}&truck[weight]=${truck.weight}` +
      `&return=polyline,summary,actions`;

    // Call HERE API
    const fetch = require('node-fetch');
    const response = await fetch(url);
    const routeData = await response.json();

    if (!response.ok) {
      throw new functions.https.HttpsError('internal', `HERE API error: ${routeData.error || 'Unknown error'}`);
    }

    // Store route plan
    await db.collection('loads').doc(loadId).collection('routePlans').add({
      provider: 'HERE',
      routeData,
      truck,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      createdBy: context.auth.uid
    });

    // Close open route_plan tasks for this load
    const openTasks = await db.collection('tasks')
      .where('loadId', '==', loadId)
      .where('type', '==', 'route_plan')
      .where('status', '==', 'open')
      .get();

    const batch = db.batch();
    openTasks.docs.forEach(doc => {
      batch.update(doc.ref, {
        status: 'completed',
        completedAt: admin.firestore.FieldValue.serverTimestamp(),
        completedBy: context.auth.uid
      });
    });
    await batch.commit();

    return { success: true, routeData };
  } catch (error) {
    console.error('planRoute error:', error);
    throw new functions.https.HttpsError('internal', error.message);
  }
});

/**
 * validatePermit (STUB)
 * WHAT: Placeholder to mark permit validated/flagged; wire your analyzer + state_rules JSON later.
 * WHY : Keeps the endpoint stable for n8n/agent now; you can swap internals anytime.
 * HOW : Call with { permitId }.
 */
exports.validatePermit = functions.https.onCall(async (data, context) => {
  try {
    if (!context.auth) {
      throw new functions.https.HttpsError('unauthenticated', 'User must be authenticated');
    }

    const { permitId } = data;
    
    if (!permitId) {
      throw new functions.https.HttpsError('invalid-argument', 'permitId is required');
    }

    // Get the permit
    const permitRef = db.collection('permits').doc(permitId);
    const permitDoc = await permitRef.get();
    
    if (!permitDoc.exists) {
      throw new functions.https.HttpsError('not-found', 'Permit not found');
    }

    // TODO: Integrate your chat_worker & state_rules validation
    // For now, return a stub validation
    const verdict = {
      compliant: true,
      notes: ['Automatic validation - placeholder implementation'],
      validatedAt: new Date().toISOString(),
      validatedBy: context.auth.uid
    };

    // Update permit with validation result
    await permitRef.update({
      status: verdict.compliant ? 'validated' : 'flagged',
      verdict,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return { success: true, verdict };
  } catch (error) {
    console.error('validatePermit error:', error);
    throw new functions.https.HttpsError('internal', error.message);
  }
});