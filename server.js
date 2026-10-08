const express = require("express");
const fs = require("fs");
const path = require("path");
const { exec } = require("child_process");

const app = express();
const PORT = 3000;
const DB_FILE = path.join(__dirname, "laws_store.json");

app.use(express.json());

function loadData() {
  if (!fs.existsSync(DB_FILE)) return { laws: [] };
  try { return JSON.parse(fs.readFileSync(DB_FILE, "utf8")); }
  catch(e) { return { laws: [] }; }
}

// 1. فحص حالة الخادم
app.get("/api/status", (req, res) => {
  res.json({ status: "running", port: PORT, ready: true });
});

// 2. قائمة القوانين المتاحة
app.get("/api/laws", (req, res) => {
  const data = loadData();
  res.json(data.laws || []);
});

// 3. البحث المباشر في المواد من التطبيق
app.get("/api/search", (req, res) => {
  const q = (req.query.q || "").trim();
  if (!q) return res.status(400).json({ error: "اكتب كلمة البحث" });

  const data = loadData();
  const results = [];
  (data.laws || []).forEach(law => {
    (law.articles || []).forEach(art => {
      if ((art.text && art.text.includes(q)) || art.num === q) {
        results.push({
          law: law.name,
          article: art.num,
          text: art.text,
          driveUrl: law.drivePdfUrl
        });
      }
    });
  });
  res.json({ query: q, total: results.length, results });
});

// 4. تشغيل الفهرسة التلقائية لـ Drive عبر السكربت الجاهز
function runAutoIndexer() {
  console.log("[" + new Date().toLocaleTimeString() + "] 🔄 فحص تلقائي لمجلد Drive...");
  exec("node " + path.join(__dirname, "drive_indexer.js") + " index", (err, stdout, stderr) => {
    if (err) {
      console.log("تنبيه الفهرسة:", stderr || err.message);
    } else {
      console.log(stdout);
    }
  });
}

app.listen(PORT, () => {
  console.log("==========================================");
  console.log("🚀 خادم المستشار الموازي يعمل بنجاح على المنفذ " + PORT);
  console.log("🔍 جاهز لاستقبال طلبات البحث من التطبيق");
  console.log("==========================================");

  // تشغيل أول فحص فوراً ثم دورياً كل 3 دقائق
  runAutoIndexer();
  setInterval(runAutoIndexer, 3 * 60 * 1000);
});
