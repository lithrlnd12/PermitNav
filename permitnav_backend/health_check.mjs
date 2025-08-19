import { bucket, db } from './firebase_bootstrap.mjs';

async function main() {
  // Check Firestore access
  const ping = db.collection('_health').doc('ping');
  await ping.set({ at: new Date(), ok: true }, { merge: true });
  const got = await ping.get();

  // Check Storage listing (no write yet)
  const [files] = await bucket.getFiles({ prefix: 'rules/', autoPaginate: false });

  console.log('✅ Firestore OK. Doc:', got.ref.path);
  console.log('✅ Storage OK. Rules files seen:', files.length);
  console.log('Bucket:', bucket.name);
}
main().catch(e => {
  console.error('❌ Health check failed:', e);
  process.exit(1);
});