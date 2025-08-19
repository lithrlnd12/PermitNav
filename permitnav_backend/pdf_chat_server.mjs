/**
 * PDF-Powered Chat API Server
 * Provides REST endpoints for state permit compliance chat
 */

import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import { handlePDFChat } from './pdf_chat_handler.mjs';

// Load environment variables
dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(express.json({ limit: '10mb' }));

// Request logging
app.use((req, res, next) => {
  console.log(`${new Date().toISOString()} - ${req.method} ${req.path}`);
  next();
});

/**
 * Health check endpoint
 */
app.get('/health', (req, res) => {
  res.json({
    status: 'healthy',
    service: 'PDF Chat API',
    timestamp: new Date().toISOString(),
    version: '1.0.0'
  });
});

/**
 * Get available states
 */
app.get('/api/states', (req, res) => {
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
 * POST /api/chat
 * 
 * Request body:
 * {
 *   "permitId": "string (optional)",
 *   "stateKey": "string (required if no permitId)",
 *   "permitText": "string (optional permit details)",
 *   "userQuestion": "string (required)",
 *   "conversationId": "string (optional)"
 * }
 */
app.post('/api/chat', async (req, res) => {
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

    // Create permit data from provided information
    let permitData = null;
    if (permitText || stateKey) {
      permitData = {
        state: stateKey,
        permitNumber: permitId || 'Unknown',
        // Parse permitText if provided (simplified parsing)
        dimensions: parsePermitDimensions(permitText),
        restrictions: parsePermitRestrictions(permitText)
      };
    }

    // Call PDF chat handler
    const result = await handlePDFChat(permitId, userQuestion, permitData);
    
    if (result.error) {
      console.error(`❌ Chat error: ${result.error}`);
      return res.status(500).json({
        error: result.error,
        confidence: result.confidence || 0,
        contactInfo: result.contactInfo
      });
    }

    // Transform response to match Android app expectations
    const response = {
      is_compliant: result.confidence > 0.7, // Conservative compliance threshold
      violations: extractViolations(result.answer),
      escorts: extractEscortInfo(result.answer),
      travel_restrictions: extractTravelRestrictions(result.answer),
      notes: result.answer,
      contact_info: result.contactInfo ? {
        department: result.contactInfo.department,
        phone: result.contactInfo.phones[0] || 'Contact info not available',
        email: result.contactInfo.emails[0] || null,
        website: result.contactInfo.websites[0] || null,
        office_hours: 'Standard business hours'
      } : null,
      confidence: result.confidence,
      sources: result.sources || [],
      state: result.state,
      permitNumber: result.permitNumber
    };

    console.log(`✅ Response sent (confidence: ${result.confidence})`);
    res.json(response);

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
 * Test endpoint for PDF content verification
 */
app.get('/api/test-pdf/:state', async (req, res) => {
  try {
    const { state } = req.params;
    
    // Test if we can load PDF content for this state
    const { loadStatePDFContent } = await import('./pdf_chat_handler.mjs');
    const content = await loadStatePDFContent(state.toUpperCase());
    
    if (!content) {
      return res.status(404).json({
        error: `No PDF content available for state: ${state}`,
        suggestion: 'Run: node process_state_pdfs.mjs test ' + state.toUpperCase()
      });
    }
    
    res.json({
      state: state.toUpperCase(),
      contentLength: content.length,
      preview: content.substring(0, 500),
      available: true,
      lastProcessed: new Date().toISOString()
    });
    
  } catch (error) {
    res.status(500).json({
      error: 'Failed to test PDF content',
      message: error.message
    });
  }
});

/**
 * Utility functions for parsing permit text and extracting response components
 */

function parsePermitDimensions(permitText) {
  if (!permitText) return {};
  
  const dimensions = {};
  
  // Simple regex patterns for common dimension formats
  const widthMatch = permitText.match(/width[:\s]*(\d+(?:\.\d+)?)/i);
  const heightMatch = permitText.match(/height[:\s]*(\d+(?:\.\d+)?)/i);
  const lengthMatch = permitText.match(/length[:\s]*(\d+(?:\.\d+)?)/i);
  const weightMatch = permitText.match(/weight[:\s]*(\d+(?:,\d+)?)/i);
  
  if (widthMatch) dimensions.width = parseFloat(widthMatch[1]);
  if (heightMatch) dimensions.height = parseFloat(heightMatch[1]);
  if (lengthMatch) dimensions.length = parseFloat(lengthMatch[1]);
  if (weightMatch) dimensions.weight = parseInt(weightMatch[1].replace(',', ''));
  
  return dimensions;
}

function parsePermitRestrictions(permitText) {
  if (!permitText) return [];
  
  const restrictions = [];
  
  // Look for common restriction keywords
  if (permitText.toLowerCase().includes('escort')) {
    restrictions.push('Escort required');
  }
  if (permitText.toLowerCase().includes('daylight') || permitText.toLowerCase().includes('sunrise')) {
    restrictions.push('Daylight hours only');
  }
  if (permitText.toLowerCase().includes('weekend') || permitText.toLowerCase().includes('holiday')) {
    restrictions.push('No weekend/holiday travel');
  }
  
  return restrictions;
}

function extractViolations(answer) {
  // Simple extraction - look for violation-related content
  const violationKeywords = ['violation', 'exceed', 'over limit', 'not compliant', 'prohibited'];
  const violations = [];
  
  const sentences = answer.split(/[.!?]+/);
  for (const sentence of sentences) {
    if (violationKeywords.some(keyword => sentence.toLowerCase().includes(keyword))) {
      violations.push(sentence.trim());
    }
  }
  
  return violations;
}

function extractEscortInfo(answer) {
  // Look for escort-related information
  const escortSentences = answer.split(/[.!?]+/).filter(sentence => 
    sentence.toLowerCase().includes('escort')
  );
  
  return escortSentences.join('. ').trim() || 'No escort information found';
}

function extractTravelRestrictions(answer) {
  // Look for travel restriction information
  const restrictionKeywords = ['hours', 'daylight', 'weekend', 'holiday', 'time', 'route'];
  const restrictionSentences = answer.split(/[.!?]+/).filter(sentence =>
    restrictionKeywords.some(keyword => sentence.toLowerCase().includes(keyword))
  );
  
  return restrictionSentences.join('. ').trim() || 'No travel restrictions specified';
}

// Error handling middleware
app.use((error, req, res, next) => {
  console.error('❌ Unhandled error:', error);
  res.status(500).json({
    error: 'Internal server error',
    message: process.env.NODE_ENV === 'development' ? error.message : 'Something went wrong'
  });
});

// 404 handler
app.use((req, res) => {
  res.status(404).json({
    error: 'Endpoint not found',
    path: req.path,
    method: req.method
  });
});

// Start server
app.listen(PORT, () => {
  console.log(`🚀 PDF Chat API Server running on port ${PORT}`);
  console.log(`📖 API Documentation:`);
  console.log(`   GET  /health              - Health check`);
  console.log(`   GET  /api/states          - Available states`);
  console.log(`   POST /api/chat            - PDF-powered chat`);
  console.log(`   GET  /api/test-pdf/:state - Test PDF content`);
  console.log(`\n💡 Environment: ${process.env.NODE_ENV || 'development'}`);
  console.log(`🔑 OpenAI API Key: ${process.env.OPENAI_API_KEY ? 'Configured' : 'Missing'}`);
});

export default app;