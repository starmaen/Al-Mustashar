const { google } = require('googleapis');
const admin = require('firebase-admin');
const pdf = require('pdf-parse');

const SERVICE_ACCOUNT = './serviceAccount.json';
const FOLDER_ID = '1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3';

if (admin.apps.length === 0) {
  const serviceAccount = require(SERVICE_ACCOUNT);
  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
  });
}

const db = admin.firestore();

const auth = new google.auth.GoogleAuth({
  keyFile: SERVICE_ACCOUNT,
  scopes: ['https://www.googleapis.com/auth/drive']
});
const drive = google.drive({ version: 'v3', auth });

async function runIndex() {
  console.log('جار فحص مجلد Google Drive...');
  const res = await drive.files.list({
    q: `'${FOLDER_ID}' in parents and trashed = false and mimeType = 'application/pdf'`,
    fields: 'files(id, name, description)'
  });

  const files = res.data.files || [];
  console.log('تم العثور على ' + files.length + ' ملف PDF.');

  for (const file of files) {
    const lawName = file.name.replace(/\.pdf$/i, '').trim();
    const lawId = 'law_' + Buffer.from(lawName).toString('base64url').substring(0, 16);
    const pdfUrl = 'https://drive.google.com/file/d/' + file.id + '/preview';

    console.log('\nقراءة وفهرسة: ' + lawName);

    const fileStream = await drive.files.get(
      { fileId: file.id, alt: 'media' },
      { responseType: 'arraybuffer' }
    );
    const pdfBuffer = Buffer.from(fileStream.data);

    let parsedData;
    try {
      parsedData = await pdf(pdfBuffer);
    } catch (e) {
      console.log('تعذر استخراج النص: ' + e.message);
      continue;
    }

    const fullText = parsedData.text || '';
    if (fullText.length < 50) {
      console.log('المستند لا يحتوي على نصوص كافية.');
      continue;
    }

    await db.collection('laws').document(lawId).set({
      name: lawName,
      drivePdfUrl: pdfUrl,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    const regex = /(?:المادة|الماده)\s*(\d+)\s*[:\-\.]?/gi;
    let matches = [];
    let match;
    while ((match = regex.exec(fullText)) !== null) {
      matches.push({ number: match[1], index: match.index });
    }

    console.log('تم استخراج ' + matches.length + ' مادة قانونية.');

    const batch = db.batch();
    for (let i = 0; i < matches.length; i++) {
      const current = matches[i];
      const startIndex = current.index;
      const endIndex = (i + 1 < matches.length) ? matches[i + 1].index : fullText.length;
      let articleText = fullText.substring(startIndex, endIndex).trim();
      if (articleText.length > 5000) {
        articleText = articleText.substring(0, 5000);
      }

      const artRef = db.collection('laws').document(lawId).collection('articles').document(current.number);
      batch.set(artRef, {
        text: articleText,
        drivePdfUrl: pdfUrl
      }, { merge: true });
    }

    await batch.commit();
    console.log('اكتملت فهرسة ' + lawName + ' بنجاح.');
  }
}

runIndex().then(() => console.log('\nانتهت الفهرسة بنجاح.')).catch(err => console.error(err));
