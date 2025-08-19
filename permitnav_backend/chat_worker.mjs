import 'dotenv/config';
import fetch from 'node-fetch';
import pdfParse from 'pdf-parse';
import OpenAI from 'openai';
import { bucket, db } from './firebase_bootstrap.mjs';

const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

async function signedReadUrl(storagePath, minutes = 60) {
  const [url] = await bucket.file(storagePath).getSignedUrl({
    action: 'read',
    expires: Date.now() + minutes * 60 * 1000
  });
  return url;
}

async function getPdfText(storagePath) {
  const url = await signedReadUrl(storagePath);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Fetch failed: ${res.status} ${res.statusText}`);
  const buf = Buffer.from(await res.arrayBuffer());
  const parsed = await pdfParse(buf);
  return parsed.text;
}

export async function chatCompliance(stateKey, permitText, userQuestion) {
  // 1) Grab state rules path (if present)
  const stateDoc = await db.collection('rules').doc(stateKey).get();
  if (!stateDoc.exists) {
    return { is_compliant: false, violations: ['Missing state rules'], escorts: '', travel_restrictions: '', notes: `No rules found for ${stateKey}` };
  }
  const statePdfPath = stateDoc.data().pdfPath;

  // 2) Extract rules text
  const rulesText = await getPdfText(statePdfPath);

  // 3) Ask OpenAI (4o) — structured response for UI
  const sys = `You are an OSOW trucking permit compliance assistant. Always return strict JSON with keys:
- is_compliant (boolean)
- violations (array of strings)
- escorts (string)
- travel_restrictions (string)
- notes (string)`;
  const usr = `STATE: ${stateKey.toUpperCase()}
STATE RULES (excerpt):
---
${rulesText.slice(0, 60000)}
---
PERMIT DETAILS:
---
${permitText}
---
QUESTION:
${userQuestion}`;

  const resp = await openai.chat.completions.create({
    model: 'gpt-4o',
    messages: [{ role: 'system', content: sys }, { role: 'user', content: usr }],
    temperature: 0.2
  });

  const content = resp.choices?.[0]?.message?.content?.trim() || '{}';
  try {
    return JSON.parse(content);
  } catch {
    const json = content.match(/\{[\s\S]*\}/);
    return json ? JSON.parse(json[0]) : { is_compliant: false, violations: ['Unparseable model output'], escorts: '', travel_restrictions: '', notes: content };
  }
}

// Example run (optional): node chat_worker.mjs delaware "permit text" "What escorts are required?"
if (process.argv[1] === new URL(import.meta.url).pathname) {
  (async () => {
    const stateKey = (process.argv[2] || 'delaware').toLowerCase();
    const permitText = process.argv[3] || 'Height 14\'2", Width 11\', Length 72\', Gross 120,000 lb, Dates 2025-08-18..25';
    const question = process.argv[4] || 'Do I need escorts?';
    const out = await chatCompliance(stateKey, permitText, question);
    console.log(JSON.stringify(out, null, 2));
  })().catch(e => { console.error('❌ Chat error:', e); process.exit(1); });
}