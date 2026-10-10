const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { google } = require("googleapis");

admin.initializeApp();

// مجلد القوانين (البحث القانوني عبر Drive/GitHub) — داخل مجلد التطبيق
const LAWS_FOLDER_ID = "1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3";
// مجلد الأرشيف الاحتياطي Secure PDFs (مرتبط بـ Firebase)
const ARCHIVE_FOLDER_ID = "1Dl0H-rKSCTbO5ZMs2U4Ws_t7Lsc55Dr9";

exports.searchDriveLaws = functions.https.onRequest(async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");

  if (req.method === "OPTIONS") {
    return res.status(204).send("");
  }

  const queryText = (req.body.query || req.query.query || "").trim();
  if (!queryText) {
    return res.status(400).json({ error: "Missing search query parameter" });
  }
  // scope=laws (افتراضي) أو scope=archive لمجلد الأرشيف الاحتياطي
  const scope = ((req.body.scope || req.query.scope || "laws").toString().toLowerCase() === "archive")
    ? "archive" : "laws";
  const folderId = scope === "archive" ? ARCHIVE_FOLDER_ID : LAWS_FOLDER_ID;

  try {
    const auth = new google.auth.GoogleAuth({
      scopes: ["https://www.googleapis.com/auth/drive.readonly"]
    });
    const drive = google.drive({ version: "v3", auth });

    const sanitizedTerm = queryText.replace(/'/g, "\\'");
    const driveQuery = `'${folderId}' in parents and (name contains '${sanitizedTerm}' or fullText contains '${sanitizedTerm}') and trashed = false`;

    const driveRes = await drive.files.list({
      q: driveQuery,
      fields: "files(id, name, mimeType, webViewLink, webContentLink, size)",
      pageSize: 20
    });

    const files = (driveRes.data.files || []).map(f => ({
      id: f.id,
      name: f.name,
      mimeType: f.mimeType,
      webViewLink: f.webViewLink,
      webContentLink: f.webContentLink,
      size: f.size
    }));

    return res.status(200).json({
      success: true,
      scope: scope,
      count: files.length,
      files: files
    });
  } catch (error) {
    console.error("Drive Search Error:", error);
    return res.status(500).json({
      success: false,
      error: error.message || "Internal server error"
    });
  }
});
