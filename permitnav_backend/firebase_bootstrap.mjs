import 'dotenv/config';
import admin from 'firebase-admin';

if (!admin.apps.length) {
  admin.initializeApp({
    credential: admin.credential.applicationDefault(),
    storageBucket: process.env.FIREBASE_STORAGE_BUCKET || 'permit-nav.firebasestorage.app'
  });
}

export const bucket = admin.storage().bucket();
export const db = admin.firestore();
export const FieldValue = admin.firestore.FieldValue;