const { google } = require('googleapis');
const admin = require('firebase-admin');
const pdf = require('pdf-parse');

const SERVICE_ACCOUNT = './serviceAccount.json';
const FOLDER_ID = '1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3';

if (!admin.apps.length) {
  admin.initializeApp({
    credential: admin.credential.cert(require(SERVICE_ACCOUNT))
  });
}
const db = admin.firestore();

const auth = new google.auth.GoogleAuth({
  keyFile: SERVICE_ACCOUNT,
  scopes: ['https://www.googleapis.com/auth/drive']
});
const drive = google.drive({ version: 'v3', auth });

async function start() {
  console.log('--- بدء الفحص ---');
  const res = await drive.files.list({
    q: `'${FOLDER_ID}' in parents and trashed = false and mimeType = 'application/pdf'`,
    fields: 'files(id, name)'
  });
  const files = res.data.files || [];
  console.log('عدد ملفات PDF:', files.length);

  for (const f of files) {
    const lawName = f.name.replace(/\.pdf$/i, '').trim();
    const lawId = 'law_' + Buffer.from(lawName).toString('base64url').substring(0, 16);
    const pdfUrl = `https://drive.google.com/file/d/${f.id}/preview`;
    console.log('معالجة:', lawName);

    const stream = await drive.files.get({ fileId: f.id, alt: 'media' }, { responseType: 'arraybuffer' });
    const parsed = await pdf(Buffer.from(stream.data));
    const text = parsed.text || '';

    await db.collection('laws').document(lawId).set({
      name: lawName,
      drivePdfUrl: pdfUrl,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    const regex = /(?:المادة|الماده)\s*(\d+)/gi;
    let matches = [];
    let m;
    while ((m = regex.exec(text)) !== null) {
      matches.push({ num: m[1], idx: m.index });
    }
    console.log('المواد المستخرجة:', matches.length);

    const batch = db.batch();
    for (let i = 0; i < matches.length; i++) {
      const start = matches[i].idx;
      const end = (i + 1 < matches.length) ? matches[i + 1].idx : text.length;
      const artText = text.substring(start, end).trim().substring(0, 5000);
      const docRef = db.collection('laws').document(lawId).collection('articles').document(matches[i].num);
      batch.set(docRef, { text: artText, drivePdfUrl: pdfUrl }, { merge: true });
    }
    await batch.commit();
    console.log('تم حفظ:', lawName);
  }
  console.log('--- انتهت الفهرسة بنجاح ---');
}

start().catch(console.error);
