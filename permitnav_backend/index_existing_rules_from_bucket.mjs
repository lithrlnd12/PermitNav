import { bucket, db, FieldValue } from './firebase_bootstrap.mjs';

async function main() {
  const [files] = await bucket.getFiles({ prefix: 'rules/' });
  if (!files.length) {
    console.warn('⚠️ No files under rules/. Upload first or run upload_state_rules.mjs.');
    return;
  }
  for (const file of files) {
    const name = file.name; // e.g., "rules/delaware.pdf"
    const stateKey = name.replace(/^rules\/|\.pdf$/g, '').toLowerCase();
    await db.collection('rules').doc(stateKey).set({
      state: stateKey.toUpperCase(),
      pdfPath: name,
      createdAt: FieldValue.serverTimestamp()
    }, { merge: true });
    console.log(`✅ Indexed: ${stateKey} -> ${name}`);
  }
  const snap = await db.collection('rules').get();
  console.log(`\n📚 Indexed states: ${snap.size}`);
  snap.forEach(d => console.log(` - ${d.id} -> ${d.data().pdfPath}`));
}
main().catch(e => { console.error('❌ Index-from-bucket error:', e); process.exit(1); });