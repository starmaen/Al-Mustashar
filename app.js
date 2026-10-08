const express = require('express');
const fs = require('fs');
const path = require('path');
const { google } = require('googleapis');

const app = express();
const PORT = 3000;
const DB_FILE = path.join(__dirname, 'laws_store.json');
const SERVICE_ACCOUNT = path.join(__dirname, 'serviceAccount.json');
const FOLDER_ID = '1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3';

app.use(express.json());

// قاعدة البيانات المحلية
function getDb() {
  if (!fs.existsSync(DB_FILE)) return { indexed: [], laws: [] };
  try { return JSON.parse(fs.readFileSync(DB_FILE, 'utf8')); }
  catch (e) { return { indexed: [], laws: [] }; }
}

function saveDb(data) {
  fs.writeFileSync(DB_FILE, JSON.stringify(data, null, 2), 'utf8');
}

// دالة آمنة لاستخراج النصوص من PDF تدعم جميع إصدارات pdf-parse
async function extractPdfText(buffer) {
  try {
    const PdfParse = require("pdf-parse");
    const uint8 = new Uint8Array(buffer);
    
    // فحص المحلل الحديث مع Uint8Array
    if (typeof PdfParse === "function") {
      try {
        const parser = new PdfParse({ data: uint8 });
        if (parser.getText) {
          const res = await parser.getText();
          if (res && res.text) return res.text;
          if (typeof res === "string") return res;
        }
      } catch(e) {}
      
      try {
        const res = await PdfParse(uint8);
        if (res && res.text) return res.text;
      } catch(e) {}
    }

    const ParserClass = PdfParse.PDFParse || PdfParse.default || PdfParse;
    const parserInstance = new ParserClass({ data: uint8 });
    if (parserInstance.getText) {
      const res = await parserInstance.getText();
      return (res && res.text) ? res.text : (res || "");
    }
  } catch (err) {
    console.log("تنبيه استخراج النص:", err.message);
  }

  return buffer.toString("utf-8");
}

// الاتصال بـ Google Drive
const auth = new google.auth.GoogleAuth({
  keyFile: SERVICE_ACCOUNT,
  scopes: ['https://www.googleapis.com/auth/drive.readonly']
});
const drive = google.drive({ version: 'v3', auth });

// محرك الفهرسة التلقائي
async function syncDrive() {
  try {
    console.log('\n[🔄 فحص Drive تلقائي]', new Date().toLocaleTimeString());
    const res = await drive.files.list({
      q: `'${FOLDER_ID}' in parents and trashed = false and mimeType = 'application/pdf'`,
      fields: 'files(id, name)'
    });

    const files = res.data.files || [];
    console.log(`📂 عدد ملفات PDF في المجلد: ${files.length}`);
    const db = getDb();

    for (const f of files) {
      if (db.indexed.includes(f.id)) {
        console.log(`⏩ تم تخطي (مفهرس مسبقاً): ${f.name}`);
        continue;
      }

      console.log(`📖 جارٍ قراءة وفهرسة: ${f.name}...`);
      const fileRes = await drive.files.get(
        { fileId: f.id, alt: 'media' },
        { responseType: 'arraybuffer' }
      );

      const buf = Buffer.from(fileRes.data);
      const text = await extractPdfText(buf);
      const lawName = f.name.replace(/\.pdf$/i, '').trim();
      const pdfUrl = `https://drive.google.com/file/d/${f.id}/preview`;

      // تقسيم المواد
      const regex = /(?:المادة|الماده)\s*(\d+)/gi;
      let matches = [];
      let m;
      while ((m = regex.exec(text)) !== null) {
        matches.push({ num: m[1], idx: m.index });
      }

      let articles = [];
      if (matches.length > 0) {
        for (let i = 0; i < matches.length; i++) {
          const s = matches[i].idx;
          const e = (i + 1 < matches.length) ? matches[i + 1].idx : text.length;
          articles.push({
            num: matches[i].num,
            text: text.substring(s, e).trim().substring(0, 3000)
          });
        }
      } else {
        articles.push({ num: '1', text: text.substring(0, 4000) });
      }

      db.laws.push({
        id: f.id,
        name: lawName,
        drivePdfUrl: pdfUrl,
        articlesCount: articles.length,
        articles: articles,
        date: new Date().toISOString()
      });
      db.indexed.push(f.id);
      saveDb(db);
      console.log(`✅ تمت الفهرسة بنجاح: ${lawName} (${articles.length} مادة)`);
    }
  } catch (err) {
    console.error('❌ خطأ في فحص Drive:', err.message);
  }
}

// واجهات الخادم (API) لتطبيق المستشار
app.get('/api/status', (req, res) => {
  const db = getDb();
  res.json({ status: 'running', lawsCount: db.laws.length });
});

app.get('/api/laws', (req, res) => {
  const db = getDb();
  res.json(db.laws.map(l => ({ id: l.id, name: l.name, url: l.drivePdfUrl, articles: l.articlesCount })));
});

app.get('/api/search', (req, res) => {
  const q = (req.query.q || '').trim();
  if (!q) return res.status(400).json({ error: 'اكتب كلمة البحث' });

  const db = getDb();
  const results = [];
  for (const law of db.laws) {
    for (const art of law.articles) {
      if (art.text.includes(q) || art.num === q) {
        results.push({
          law: law.name,
          article: art.num,
          previewUrl: law.drivePdfUrl,
          text: art.text
        });
      }
    }
  }
  res.json({ query: q, count: results.length, results });
});

// بدء التشغيل
app.listen(PORT, async () => {
  console.log(`\n==============================================`);
  console.log(`🚀 خادم المستشار الموازي يعمل على المنفذ: ${PORT}`);
  console.log(`🔍 واجهة البحث جاهزة: http://localhost:${PORT}/api/search?q=كلمة`);
  console.log(`==============================================`);

  // فحص أولي فوري
  await syncDrive();
  // مراقبة دورية تلقائية كل دقيقتين
  setInterval(syncDrive, 2 * 60 * 1000);
});
