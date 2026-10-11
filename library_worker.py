"""عامل المكتبة القانونية: مجلد Drive --> data/library/*.json (مفهرسة) --> التطبيق.

يقبل: PDF (OCR/نص) + TXT (مباشرة) + ZIP (يفك ضغطه ويعالج ما بداخله).
يعالج الجديد/المعدّل فقط عبر _manifest.json، ويكتب _report.json لبوابة الجودة.

المخرجات (تطابق شاشة المكتبة في التطبيق):
  data/library/{bookId}.json: {id, title, type, year, source_file,
      drive_file_id, drive_url, chunks_count, chunks: [{n, heading, text}]}
  data/library/index.json + _manifest.json + _report.json

المتغيرات: GDRIVE_SERVICE_ACCOUNT_JSON، GDRIVE_LIBRARY_FOLDER_ID.
"""

import io
import json
import os
import re
import sys
import zipfile
from datetime import datetime, timezone

OUTPUT_DIR = "data/library"
MANIFEST_PATH = "data/library/_manifest.json"

CHUNK_SIZE = 1500
CHUNK_OVERLAP = 150

TYPE_KEYWORDS = {
    "اجتهاد": ["اجتهاد", "اجتهادات", "قرارات محكمة", "نقض"],
    "شرح": ["شرح", "تعليق", "موسوعة"],
    "كتاب": ["كتاب", "مؤلف", "رسالة", "أطروحة"],
}


def load_manifest():
    if os.path.exists(MANIFEST_PATH):
        try:
            with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def save_manifest(m):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=2)


def detect_type(name):
    for t, words in TYPE_KEYWORDS.items():
        for w in words:
            if w in name:
                return t
    return "مرجع"


def make_id(title):
    clean = re.sub(r"\s+", "-", title.strip())
    clean = re.sub(r'[\\/:*?"<>|]', "", clean)
    return clean[:80] or "كتاب-بدون-عنوان"


def chunk_text(text):
    """تقسيم النص إلى مقاطع: على الترويسات (باب/فصل/مبحث) ثم حجم ثابت مع تداخل."""
    text = re.sub(r"\n{3,}", "\n\n", text.strip())
    if not text:
        return []
    parts = re.split(r"\n(?=(?:الباب|الفصل|المبحث|المطلب|المقدمة|الخاتمة)[\s:0-9٠-٩\-—]+)", text)
    chunks = []
    for part in parts:
        part = part.strip()
        if not part:
            continue
        lines = part.split("\n", 1)
        heading = lines[0].strip()[:80] if len(lines) > 1 and len(lines[0].strip()) < 80 else ""
        body = part if not heading else lines[1] if len(lines) > 1 else part
        if len(body) <= CHUNK_SIZE:
            if len(body.strip()) >= 40:
                chunks.append({"heading": heading, "text": body.strip()})
        else:
            step = CHUNK_SIZE - CHUNK_OVERLAP
            for i in range(0, len(body), step):
                piece = body[i:i + CHUNK_SIZE].strip()
                if len(piece) >= 40:
                    chunks.append({"heading": heading, "text": piece})
                if i + CHUNK_SIZE >= len(body):
                    break
    return chunks


def decode_txt(raw):
    for enc in ("utf-8", "windows-1256", "cp1256", "utf-8-sig"):
        try:
            return raw.decode(enc)
        except Exception:
            continue
    return raw.decode("utf-8", errors="ignore")


def process_pdf_bytes(pdf_bytes, ocr):
    """يستخرج النص: PyMuPDF أولاً ثم Tesseract عند التشوه (نفس منطق القوانين)."""
    year_guess = 9999
    text = ocr.light_clean(ocr.extract_pymupdf(pdf_bytes))
    arts_probe = len(text)
    good = arts_probe > 500
    if good:
        return text, "PyMuPDF"
    return ocr.fix_arabic_errors(ocr.extract_tesseract(pdf_bytes)), "Tesseract"


def main():
    import importlib.util
    spec = importlib.util.spec_from_file_location("ocr", "ocr_worker.py")
    ocr = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ocr)

    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaIoBaseDownload

    creds = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["GDRIVE_SERVICE_ACCOUNT_JSON"]),
        scopes=["https://www.googleapis.com/auth/drive.readonly"],
    )
    drive = build("drive", "v3", credentials=creds)
    folder_id = os.environ["GDRIVE_LIBRARY_FOLDER_ID"]

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    old_manifest = load_manifest()
    new_manifest = {}

    files = drive.files().list(
        q=f"'{folder_id}' in parents and trashed=false",
        fields="files(id, name, mimeType, modifiedTime)",
        pageSize=1000,
    ).execute().get("files", [])
    print(f"ملفات المكتبة على Drive: {len(files)}")

    index_books = []
    report = []

    def download(fbid):
        req = drive.files().get_media(fileId=fbid)
        buf = io.BytesIO()
        dl = MediaIoBaseDownload(buf, req)
        done = False
        while not done:
            _, done = dl.next_chunk()
        return buf.getvalue()

    # تجميع الوحدات: (العنوان، النوع، البايتات، الصيغة، مفتاح التخطي)
    units = []
    for f in files:
        name, mt, fbid, mod = f["name"], f.get("mimeType", ""), f["id"], f.get("modifiedTime", "")
        low = name.lower()
        if low.endswith(".zip") or mt == "application/zip":
            try:
                zf = zipfile.ZipFile(io.BytesIO(download(fbid)))
                for inner in zf.namelist():
                    il = inner.lower()
                    if il.endswith((".pdf", ".txt")) and not inner.startswith("__"):
                        units.append({
                            "title": re.sub(r"\.(pdf|txt)$", "", inner.split("/")[-1], flags=re.I),
                            "data": zf.read(inner), "kind": "pdf" if il.endswith(".pdf") else "txt",
                            "skip_key": f"zip:{name}/{inner}@{mod}",
                            "source": f"{name} ← {inner}", "drive_id": fbid,
                        })
            except Exception as e:
                print(f"    تعذر فك: {name}: {e}")
        elif low.endswith(".pdf") or mt == "application/pdf":
            units.append({
                "title": re.sub(r"\.pdf$", "", name, flags=re.I),
                "data": None, "kind": "pdf", "skip_key": f"file:{name}@{mod}",
                "source": name, "drive_id": fbid, "fbid": fbid,
            })
        elif low.endswith(".txt") or mt == "text/plain":
            units.append({
                "title": re.sub(r"\.txt$", "", name, flags=re.I),
                "data": None, "kind": "txt", "skip_key": f"file:{name}@{mod}",
                "source": name, "drive_id": fbid, "fbid": fbid,
            })

    print(f"وحدات للمعالجة: {len(units)}")

    for u in units:
        prev = old_manifest.get(u["skip_key"])
        if prev:
            book_id = prev.get("book_id")
            jp = os.path.join(OUTPUT_DIR, f"{book_id}.json")
            if book_id and os.path.exists(jp):
                print(f"⏭  تخطي: {u['title']}")
                new_manifest[u["skip_key"]] = prev
                try:
                    ex = json.load(open(jp, encoding="utf-8"))
                    index_books.append({
                        "id": ex["id"], "title": ex["title"], "type": ex.get("type", "مرجع"),
                        "file": f"{book_id}.json", "chunks_count": ex.get("chunks_count", 0),
                    })
                    report.append({"title": ex["title"], "status": "unchanged",
                                   "chunks": ex.get("chunks_count", 0)})
                except Exception:
                    pass
                continue

        print(f"\n=== معالجة: {u['title']}")
        raw = u["data"] if u["data"] is not None else download(u["fbid"])
        method = "TXT"
        if u["kind"] == "pdf":
            text, method = process_pdf_bytes(raw, ocr)
        else:
            text = ocr.strip_invisible(decode_txt(raw))
        chunks = chunk_text(text)
        print(f"    ✓ {method}: {len(chunks)} مقطعاً")
        book_id = make_id(u["title"])
        did = u.get("drive_id", "")
        data = {
            "id": book_id, "title": u["title"], "type": detect_type(u["title"]),
            "source_file": u["source"], "extraction_method": method,
            "drive_file_id": did,
            "drive_url": f"https://drive.google.com/file/d/{did}/view" if did else "",
            "chunks_count": len(chunks),
            "chunks": [{"n": i + 1, "heading": c["heading"], "text": c["text"]}
                       for i, c in enumerate(chunks)],
        }
        with open(os.path.join(OUTPUT_DIR, f"{book_id}.json"), "w", encoding="utf-8") as jf:
            json.dump(data, jf, ensure_ascii=False, indent=2)
        new_manifest[u["skip_key"]] = {"book_id": book_id}
        index_books.append({
            "id": book_id, "title": u["title"], "type": data["type"],
            "file": f"{book_id}.json", "chunks_count": len(chunks),
        })
        st = "ok" if len(chunks) >= 3 else "TOO_FEW"
        report.append({"title": u["title"], "status": st, "chunks": len(chunks)})
        if st != "ok":
            print(f"    ⚠️ {st}: مقاطع قليلة — راجع جودة الملف")

    save_manifest(new_manifest)
    with open(os.path.join(OUTPUT_DIR, "index.json"), "w", encoding="utf-8") as jf:
        json.dump({
            "last_updated": datetime.now(timezone.utc).isoformat(),
            "total_books": len(index_books), "books": index_books,
        }, jf, ensure_ascii=False, indent=2)
    with open(os.path.join(OUTPUT_DIR, "_report.json"), "w", encoding="utf-8") as rf:
        json.dump({"generated_at": datetime.now(timezone.utc).isoformat(),
                   "books": report}, rf, ensure_ascii=False, indent=2)
    print(f"\n=== {len(index_books)} مرجعاً في فهرس المكتبة ===")


if __name__ == "__main__":
    sys.exit(main() or 0)
