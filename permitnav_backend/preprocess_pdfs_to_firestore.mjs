/**
 * Pre-process all state PDFs into Firestore for lightning-fast chat responses
 * Extracts text and stores in searchable chunks
 */

import admin from 'firebase-admin';
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const pdfParse = require('pdf-parse');

// Initialize Firebase Admin (uses default credentials)
admin.initializeApp({
  storageBucket: 'permit-nav.firebasestorage.app'
});

const db = admin.firestore();
const storage = admin.storage();

// State PDF mapping
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

async function preprocessPDFToFirestore(state, filename) {
  try {
    console.log(`🔄 Processing ${state}: ${filename}`);
    
    // Download PDF from Storage
    const bucket = storage.bucket();
    const file = bucket.file(`state_rules/${filename}`);
    const [buffer] = await file.download();
    
    // Parse PDF text
    const data = await pdfParse(buffer);
    const fullText = data.text;
    
    console.log(`📄 Extracted ${fullText.length} characters from ${state} PDF`);
    
    // Extract contact information
    const contactInfo = extractContactInfo(fullText, state);
    
    // Break into searchable chunks (2000 chars each for fast queries)
    const chunks = chunkText(fullText, 2000);
    
    // Store in Firestore
    const stateDoc = {
      state: state,
      filename: filename,
      fullText: fullText,
      contactInfo: contactInfo,
      chunks: chunks,
      processedAt: new Date().toISOString(),
      chunkCount: chunks.length
    };
    
    await db.collection('state_regulations').doc(state).set(stateDoc);
    
    console.log(`✅ ${state} processed: ${chunks.length} chunks stored in Firestore`);
    
  } catch (error) {
    console.error(`❌ Error processing ${state}:`, error);
  }
}

function chunkText(text, maxChars) {
  const chunks = [];
  const paragraphs = text.split('\n\n');
  let currentChunk = '';
  
  for (const paragraph of paragraphs) {
    if (currentChunk.length + paragraph.length > maxChars) {
      if (currentChunk.trim()) {
        chunks.push(currentChunk.trim());
      }
      currentChunk = paragraph;
    } else {
      currentChunk += '\n\n' + paragraph;
    }
  }
  
  if (currentChunk.trim()) {
    chunks.push(currentChunk.trim());
  }
  
  return chunks;
}

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

async function preprocessAllPDFs() {
  console.log('🚀 Starting PDF preprocessing to Firestore...');
  
  const states = Object.keys(stateFileMap);
  console.log(`📋 Processing ${states.length} state PDFs`);
  
  // Process 3 at a time to avoid overwhelming Firebase
  for (let i = 0; i < states.length; i += 3) {
    const batch = states.slice(i, i + 3);
    
    await Promise.all(
      batch.map(state => preprocessPDFToFirestore(state, stateFileMap[state]))
    );
    
    console.log(`✅ Completed batch ${Math.floor(i / 3) + 1}/${Math.ceil(states.length / 3)}`);
    
    // Small delay between batches
    if (i + 3 < states.length) {
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  
  console.log('🎉 All PDFs preprocessed to Firestore!');
  console.log('💬 Chat responses will now be lightning fast!');
}

// Run the preprocessing
preprocessAllPDFs().catch(console.error);