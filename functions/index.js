const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { google } = require("googleapis");

admin.initializeApp();

const LAWS_FOLDER_ID = "1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3";

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

  try {
    const auth = new google.auth.GoogleAuth({
      scopes: ["https://www.googleapis.com/auth/drive.readonly"]
    });
    const drive = google.drive({ version: "v3", auth });

    const sanitizedTerm = queryText.replace(/'/g, "\\'");
    const driveQuery = `'${LAWS_FOLDER_ID}' in parents and (name contains '${sanitizedTerm}' or fullText contains '${sanitizedTerm}') and trashed = false`;

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
