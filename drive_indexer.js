const pdf = require("pdf-parse");
/**
 * drive-tools.js — ربط مشروع المستشار بـ Google Drive
 * -----------------------------------------------------------
 * يستخدم نفس serviceAccountKey.json الموجود أصلاً (لا اعتماد جديد).
 *
 * إعداد لمرة واحدة (يدوي، خارج الكود):
 *   1) فعّل Drive API لهذا المشروع:
 *      https://console.cloud.google.com/apis/library/drive.googleapis.com?project=al-mustashar-7f6b7
 *   2) بحساب Drive (starsyria2500@gmail.com) أنشئ مجلدًا رئيسيًا وشاركه
 *      (Editor) مع إيميل حساب الخدمة الظاهر بملف serviceAccountKey.json
 *      (الحقل client_email، غالبًا firebase-adminsdk-fbsvc@...).
 *   3) انسخ معرّف ذلك المجلد من رابطه (الجزء بعد /folders/) وضعه هنا:
 */
const ROOT_FOLDER_ID = "1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3";

/**
 * التثبيت (مرة واحدة):
 *   npm install googleapis
 *
 * الاستخدام:
 *   node drive-tools.js upload <local_file> [subfolder]
 *     يرفع ملفًا، وينشئ المجلد الفرعي تلقائيًا إذا لم يكن موجودًا
 *     (مثال: node drive-tools.js upload case1.pdf cases/userUid123)
 *
 *   node drive-tools.js list [subfolder]
 *     يعرض الملفات داخل مجلد (الجذر افتراضيًا)
 *
 *   node drive-tools.js download <fileId> <output_path>
 *     يحمّل ملفًا بمعرّفه
 *
 *   node drive-tools.js delete <fileId>
 *     يحذف ملفًا بمعرّفه
 */

const fs = require("fs");
const path = require("path");
const { google } = require("googleapis");

function getDrive() {
  // يستخدم رمز OAuth الشخصي (drive-oauth-token.json) بدل حساب الخدمة،
  // لأن حسابات الخدمة بلا مساحة تخزين خاصة بها على Drive العادي.
  const auth = new google.auth.GoogleAuth({
  keyFile: path.join(__dirname, "serviceAccount.json"),
  scopes: ["https://www.googleapis.com/auth/drive"]
});
  
  return google.drive({ version: "v3", auth });
}

// يجد أو ينشئ مجلدًا فرعيًا (يدعم مسارًا متداخلًا مثل cases/userUid)، ويرجع معرّفه
async function resolveFolder(drive, subfolderPath) {
  let parentId = ROOT_FOLDER_ID;
  if (!subfolderPath) return parentId;

  for (const name of subfolderPath.split("/").filter(Boolean)) {
    const q = `'${parentId}' in parents and name = '${name.replace(/'/g, "\\'")}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false`;
    const res = await drive.files.list({ q, fields: "files(id, name)" });
    if (res.data.files && res.data.files.length > 0) {
      parentId = res.data.files[0].id;
    } else {
      const created = await drive.files.create({
        requestBody: {
          name,
          mimeType: "application/vnd.google-apps.folder",
          parents: [parentId],
        },
        fields: "id",
      });
      parentId = created.data.id;
    }
  }
  return parentId;
}

async function upload(localFile, subfolder) {
  const drive = getDrive();
  const folderId = await resolveFolder(drive, subfolder);
  const res = await drive.files.create({
    requestBody: { name: path.basename(localFile), parents: [folderId] },
    media: { body: fs.createReadStream(localFile) },
    fields: "id, name, webViewLink",
  });
  console.log("تم الرفع بنجاح:");
  console.log("  fileId:", res.data.id);
  console.log("  name:", res.data.name);
  console.log("  link:", res.data.webViewLink);
  console.log("\n(احفظ fileId هذا في مستند القضية بـ Firestore كمؤشر على الملف)");
}

async function list(subfolder) {
  const drive = getDrive();
  const folderId = await resolveFolder(drive, subfolder);
  const res = await drive.files.list({
    q: `'${folderId}' in parents and trashed = false`,
    fields: "files(id, name, size, modifiedTime)",
    orderBy: "modifiedTime desc",
  });
  if (!res.data.files.length) {
    console.log("لا توجد ملفات.");
    return;
  }
  for (const f of res.data.files) {
    console.log(`${f.id}  ${f.name}  (${f.size || "?"} bytes)  ${f.modifiedTime}`);
  }
}

async function downloadFile(fileId, outPath) {
  const drive = getDrive();
  const dest = fs.createWriteStream(outPath);
  const res = await drive.files.get({ fileId, alt: "media" }, { responseType: "stream" });
  await new Promise((resolve, reject) => {
    res.data.pipe(dest).on("finish", resolve).on("error", reject);
  });
  console.log("تم التحميل إلى:", outPath);
}

async function deleteFile(fileId) {
  const drive = getDrive();
  await drive.files.delete({ fileId });
  console.log("تم حذف الملف:", fileId);
}

// يبحث في كامل Drive (ليس فقط داخل مجلد المشروع) عن ملفات اسمها يحتوي كلمة معينة
async function searchAnywhere(term) {
  const drive = getDrive();
  const safeTerm = term.replace(/'/g, "\\'");
  const q = "name contains '" + safeTerm + "' and trashed = false";
  const res = await drive.files.list({
    q,
    fields: "files(id, name, mimeType, parents, size, modifiedTime)",
    pageSize: 100,
    orderBy: "modifiedTime desc",
  });
  if (!res.data.files.length) { console.log("لا نتائج."); return; }
  for (const f of res.data.files) {
    console.log(f.id + "  " + f.name + "  [" + f.mimeType + "]  (" + (f.size || "?") + " bytes)");
  }
}

// ينسخ ملفًا (بمعرفه) إلى داخل مجلد المشروع (أو مجلد فرعي منه) دون نقله من مكانه الأصلي
async function copyIntoProject(fileId, subfolder) {
  const drive = getDrive();
  const folderId = await resolveFolder(drive, subfolder);
  const meta = await drive.files.get({ fileId, fields: "name" });
  const res = await drive.files.copy({
    fileId,
    requestBody: { name: meta.data.name, parents: [folderId] },
  });
  console.log("تم النسخ إلى مجلد المشروع. fileId الجديد: " + res.data.id + "  الاسم: " + meta.data.name);
}

async function main() {
  if (ROOT_FOLDER_ID.includes("ضع_معرف")) {
    console.error("⚠️ لم يتم ضبط ROOT_FOLDER_ID داخل drive-tools.js بعد. ضع معرف المجلد المشترك أولاً.");
    process.exit(1);
  }
  const [cmd, ...args] = process.argv.slice(2);
  switch (cmd) {
    case "upload": return upload(args[0], args[1]);
    case "index": return indexAll();
    case "list": return list(args[0]);
    case "download": return downloadFile(args[0], args[1]);
    case "delete": return deleteFile(args[0]);
    case "search": return searchAnywhere(args[0]);
    case "copy-into": return copyIntoProject(args[0], args[1]);
    default:
      console.log("الأوامر: upload <file> [subfolder] | list [subfolder] | download <fileId> <out> | delete <fileId> | search <term> | copy-into <fileId> [subfolder]");
  }
}
main().catch((e) => { console.error("خطأ:", e.message); process.exit(1); });


async function indexAll() {
  
  let db = null;
  try {
    const admin = require("firebase-admin");
    const certData = require("./serviceAccount.json");
    if (!admin.apps || !admin.apps.length) {
      admin.initializeApp({
        credential: admin.credential ? admin.credential.cert(certData) : admin.cert(certData)
      });
    }
    db = admin.firestore();
  } catch(e) {
    console.log("ℹ️ العمل في الوضع المحلي المستقل (تخطي Firestore مؤقتاً):", e.message);
  }

  console.log("🔍 فحص المجلد للفهرسة...");
  const drive = getDrive();
  const res = await drive.files.list({
    q: "'" + ROOT_FOLDER_ID + "' in parents and trashed = false and mimeType = 'application/pdf'",
    fields: "files(id, name)"
  });
  const files = res.data.files || [];
  console.log("📂 عدد الملفات:", files.length);

  for (const f of files) {
    const lawName = f.name.replace(/\.pdf$/i, "").trim();
    const lawId = "law_" + Buffer.from(lawName).toString("base64url").substring(0, 16);
    const pdfUrl = "https://drive.google.com/file/d/" + f.id + "/preview";
    console.log("\n📖 قراءة:", lawName);

    const stream = await drive.files.get({ fileId: f.id, alt: "media" }, { responseType: "arraybuffer" });
    
    let parsedText = "";
    try {
      const buf = Buffer.from(stream.data);
      if (typeof pdf === "function") {
        try { const res = await pdf(buf); parsedText = res.text || ""; }
        catch(e) {
          if (e.message.includes("without 'new'")) {
            const instance = new pdf();
            parsedText = (await instance.parse(buf)).text || (await instance.getText(buf)) || "";
          } else throw e;
        }
      } else if (pdf.PDFExtract) {
        const extractor = new pdf.PDFExtract();
        const data = await extractor.extractBuffer(buf);
        parsedText = data.pages.map(p => p.content.map(i => i.str).join(" ")).join("
");
      }
    } catch(errExtract) {
      console.log("استخراج النص المباشر...");
      parsedText = Buffer.from(stream.data).toString("binary");
    }
    const text = parsedText;


    if (db) await db.collection("laws").document(lawId).set({
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
    console.log("📝 المواد المستخرجة:", matches.length);

    const batch = db.batch();
    for (let i = 0; i < matches.length; i++) {
      const start = matches[i].idx;
      const end = (i + 1 < matches.length) ? matches[i + 1].idx : text.length;
      const artText = text.substring(start, end).trim().substring(0, 5000);
      const docRef = db.collection("laws").document(lawId).collection("articles").document(matches[i].num);
      batch.set(docRef, { text: artText, drivePdfUrl: pdfUrl }, { merge: true });
    }
    if (db) { try { await batch.commit(); } catch(e){ console.log("تنبيه Firestore:", e.message); } }
    
    // تحديث المخزن المحلي للخادم الموازي
    const localDbPath = path.join(__dirname, "laws_store.json");
    let localDb = { laws: [] };
    if (fs.existsSync(localDbPath)) {
      try { localDb = JSON.parse(fs.readFileSync(localDbPath, "utf8")); } catch(e){}
    }
    const lawObj = {
      id: lawId,
      name: lawName,
      drivePdfUrl: pdfUrl,
      articles: matches.map((m, idx) => {
        const s = m.idx;
        const e = (idx + 1 < matches.length) ? matches[idx + 1].idx : text.length;
        return { num: m.num, text: text.substring(s, e).trim().substring(0, 3000) };
      })
    };
    localDb.laws = (localDb.laws || []).filter(l => l.id !== lawId).concat([lawObj]);
    fs.writeFileSync(localDbPath, JSON.stringify(localDb, null, 2), "utf8");

    console.log("✅ تم حفظ المواد في Firestore بنجاح:", lawName);
  }
  console.log("\n🎉 اكتملت الفهرسة لجميع الملفات.");
}
