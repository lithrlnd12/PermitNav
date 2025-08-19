import fs from 'fs';
import path from 'path';
import { bucket, db, FieldValue } from './firebase_bootstrap.mjs';

const RULES_DIR = './state_rules';
const DEST_PREFIX = 'rules';

function toStateKeyFromFilename(file) {
  const base = path.basename(file, path.extname(file));
  
  // Handle "Permit_Nav_StateName_OSOW_Binder.pdf" format
  if (base.startsWith('Permit_Nav_')) {
    const parts = base.split('_');
    if (parts.length >= 3) {
      // Join state name parts (e.g., "New_York" -> "new york")
      const stateNameParts = [];
      for (let i = 2; i < parts.length; i++) {
        if (parts[i] === 'OSOW' || parts[i] === 'Binder') break;
        stateNameParts.push(parts[i]);
      }
      return stateNameParts.join(' ').trim().toLowerCase().replace(/\s+/g, '_');
    }
  }
  
  // Handle "StateName_OSOW_Permit_Binder.pdf" format
  return (base.split('_')[0] || base).trim().toLowerCase();
}

async function uploadOne(localPath, stateKey) {
  const destination = `${DEST_PREFIX}/${stateKey}.pdf`;
  await bucket.upload(localPath, { destination });
  await db.collection('rules').doc(stateKey).set({
    state: stateKey.toUpperCase(),   // full name uppercased is fine
    pdfPath: destination,
    createdAt: FieldValue.serverTimestamp()
  }, { merge: true });
  console.log(`✅ Uploaded & indexed: ${localPath} -> gs://${bucket.name}/${destination}`);
}

async function main() {
  if (!fs.existsSync(RULES_DIR)) {
    console.warn(`⚠️ Folder not found: ${RULES_DIR}. Create it and drop PDFs later.`);
    return;
  }
  const files = fs.readdirSync(RULES_DIR).filter(f => f.toLowerCase().endsWith('.pdf'));
  if (files.length === 0) {
    console.warn('⚠️ No PDFs detected in ./state_rules. Add files like "Delaware_OSOW_Permit_Binder.pdf" then rerun.');
    return;
  }
  for (const f of files) {
    await uploadOne(path.join(RULES_DIR, f), toStateKeyFromFilename(f));
  }
  // Print index
  const snap = await db.collection('rules').get();
  console.log(`\n📚 Indexed states: ${snap.size}`);
  snap.forEach(d => console.log(` - ${d.id} -> ${d.data().pdfPath}`));
}
main().catch(e => { console.error('❌ Upload error:', e); process.exit(1); });