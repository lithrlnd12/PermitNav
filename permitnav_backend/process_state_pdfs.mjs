/**
 * PDF Processing Script
 * Extracts text from state regulation PDFs and caches for chat system
 */

import fs from 'fs';
import path from 'path';
import pdfParse from 'pdf-parse';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PDF_DIR = path.join(__dirname, 'state_rules');
const CACHE_DIR = path.join(__dirname, 'pdf_cache');

// State mapping (same as in chat handler)
const STATE_FILE_MAP = {
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

/**
 * Process a single PDF and extract text content
 */
async function processPDF(state, filename) {
  try {
    console.log(`📄 Processing ${state}: ${filename}`);
    
    const pdfPath = path.join(PDF_DIR, filename);
    
    if (!fs.existsSync(pdfPath)) {
      console.error(`❌ PDF not found: ${pdfPath}`);
      return false;
    }

    // Read PDF file
    const pdfBuffer = fs.readFileSync(pdfPath);
    
    // Extract text using pdf-parse
    const data = await pdfParse(pdfBuffer);
    
    // Clean up text content
    let cleanText = data.text
      .replace(/\s+/g, ' ')              // Normalize whitespace
      .replace(/\n\s*\n/g, '\n\n')       // Normalize line breaks
      .trim();

    // Add metadata header
    const metadata = `STATE: ${state}
FILENAME: ${filename}
PAGES: ${data.numpages}
EXTRACTED: ${new Date().toISOString()}
CONTENT_LENGTH: ${cleanText.length} characters

=== REGULATION CONTENT ===

`;

    const fullContent = metadata + cleanText;

    // Save to cache
    const cacheFile = path.join(CACHE_DIR, `${state}_content.txt`);
    fs.writeFileSync(cacheFile, fullContent, 'utf8');
    
    console.log(`✅ ${state}: Extracted ${cleanText.length} characters, ${data.numpages} pages`);
    return true;
    
  } catch (error) {
    console.error(`❌ Error processing ${state}:`, error.message);
    return false;
  }
}

/**
 * Process all state PDFs
 */
async function processAllPDFs() {
  console.log('🚀 Starting PDF processing for all states...');
  
  // Create cache directory if it doesn't exist
  if (!fs.existsSync(CACHE_DIR)) {
    fs.mkdirSync(CACHE_DIR, { recursive: true });
    console.log(`📁 Created cache directory: ${CACHE_DIR}`);
  }

  let processed = 0;
  let failed = 0;
  
  for (const [state, filename] of Object.entries(STATE_FILE_MAP)) {
    const success = await processPDF(state, filename);
    if (success) {
      processed++;
    } else {
      failed++;
    }
    
    // Small delay to prevent overwhelming the system
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  
  console.log(`\n📊 Processing Complete:`);
  console.log(`✅ Successfully processed: ${processed} states`);
  console.log(`❌ Failed: ${failed} states`);
  
  // List cache files
  console.log(`\n📁 Cache files created:`);
  const cacheFiles = fs.readdirSync(CACHE_DIR);
  cacheFiles.forEach(file => {
    const filePath = path.join(CACHE_DIR, file);
    const stats = fs.statSync(filePath);
    const sizeKB = Math.round(stats.size / 1024);
    console.log(`  - ${file} (${sizeKB} KB)`);
  });
}

/**
 * Process specific states only
 */
async function processSpecificStates(states) {
  console.log(`🎯 Processing specific states: ${states.join(', ')}`);
  
  if (!fs.existsSync(CACHE_DIR)) {
    fs.mkdirSync(CACHE_DIR, { recursive: true });
  }
  
  for (const state of states) {
    const filename = STATE_FILE_MAP[state.toUpperCase()];
    if (filename) {
      await processPDF(state.toUpperCase(), filename);
    } else {
      console.error(`❌ No PDF mapping found for state: ${state}`);
    }
  }
}

/**
 * Test extraction for a single state
 */
async function testExtraction(state) {
  console.log(`🧪 Testing extraction for ${state}...`);
  
  const filename = STATE_FILE_MAP[state.toUpperCase()];
  if (!filename) {
    console.error(`❌ No PDF mapping for ${state}`);
    return;
  }
  
  const success = await processPDF(state.toUpperCase(), filename);
  
  if (success) {
    const cacheFile = path.join(CACHE_DIR, `${state.toUpperCase()}_content.txt`);
    const content = fs.readFileSync(cacheFile, 'utf8');
    
    console.log(`\n📄 Content Preview (first 500 chars):`);
    console.log('-'.repeat(50));
    console.log(content.substring(0, 500) + '...');
    console.log('-'.repeat(50));
  }
}

// Command line interface
const command = process.argv[2];
const args = process.argv.slice(3);

switch (command) {
  case 'all':
    processAllPDFs();
    break;
    
  case 'states':
    if (args.length === 0) {
      console.error('❌ Please provide state codes: node process_state_pdfs.mjs states IN IL OH');
      process.exit(1);
    }
    processSpecificStates(args);
    break;
    
  case 'test':
    if (args.length === 0) {
      console.error('❌ Please provide a state code: node process_state_pdfs.mjs test IN');
      process.exit(1);
    }
    testExtraction(args[0]);
    break;
    
  default:
    console.log(`
📖 PDF Processing Script Usage:

  Process all states:
  node process_state_pdfs.mjs all
  
  Process specific states:
  node process_state_pdfs.mjs states IN IL OH MI
  
  Test single state extraction:
  node process_state_pdfs.mjs test IN
  
Examples:
  node process_state_pdfs.mjs all              # Process all 51 states
  node process_state_pdfs.mjs states IN IL     # Process only Indiana and Illinois  
  node process_state_pdfs.mjs test IN          # Test Indiana extraction and show preview
`);
    break;
}

export { processPDF, processAllPDFs, processSpecificStates };