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

# نسخة مخطط المعالجة: تغييرها يعيد معالجة كل الكتب مرة واحدة (لإصلاح نصوص قديمة مشوهة).
# v3-hybrid: استخراج هجين صفحة-صفحة (Tesseract للصفحات الممسوحة التالفة).
SCHEMA_VERSION = "v3-hybrid"

# كلمات شائعة لكشف النص المشوه/المعكوس (تُحسب معكوساتها آلياً)
_COMMON_AR = {
    'من', 'في', 'على', 'إلى', 'إلا', 'الذي', 'التي', 'هذا', 'هذه', 'ذلك', 'تلك',
    'عن', 'مع', 'بعد', 'قبل', 'بين', 'حيث', 'عند', 'غير', 'كما', 'إذا', 'لكن',
    'قال', 'كان', 'قد', 'لم', 'لن', 'ثم', 'أو', 'ما', 'أن', 'كل', 'هو', 'هي',
    'القانون', 'المادة', 'الكتاب', 'الفصل', 'الباب', 'أي', 'دون', 'حتى', 'وقد',
}
_REVERSED_AR = {w[::-1] for w in _COMMON_AR if len(w) > 1}
_REVERSED_AR.discard('لا')  # معكوسها 'ال' شائع سليماً — لتفادي الإيجاب الكاذب
_NUMERIC_LINE_RE = re.compile(r'^[\d٠-٩\s\-—–_.*·•«»"\'()\[\]]+$')


def fix_reversed_arabic(text):
    """إصلاح السطور المعكوسة بصرياً (استخراج PDF مقلوب الترتيب).

    يكشف السطر الذي تغلب عليه الكلمات الشائعة معكوسة (نم/يف/ىلع...)
    مع غياب الصيغ السليمة، فيعكسه حرفياً. السطور المختلطة تُترك كما هي.
    """
    if not text:
        return text
    out = []
    for line in text.split('\n'):
        s = line.strip()
        if len(s) < 8:
            out.append(line)
            continue
        ar = len(re.findall(r'[\u0600-\u06FF]', s))
        if ar < len(s) * 0.5:
            out.append(line)
            continue
        toks = re.findall(r'[\u0600-\u06FF]+', s)
        if not toks:
            out.append(line)
            continue
        norm = sum(1 for t in toks if t in _COMMON_AR)
        rev = sum(1 for t in toks if t in _REVERSED_AR)
        if rev >= 2 and rev > norm + 1:
            out.append(line[::-1])
        else:
            out.append(line)
    return '\n'.join(out)


def is_good_library_text(text):
    """بوابة جودة نص المكتبة: عربية كافية + كلمات شائعة + لا ضجيج ؟"""
    if not text or len(text) < 500:
        return False
    sample = text[:20000]
    total = len(re.sub(r'\s', '', sample)) or 1
    arabic = len(re.findall(r'[\u0600-\u06FF]', sample))
    if (arabic / total) < 0.5:
        return False
    found = sum(1 for w in _COMMON_AR if re.search(r'\b' + w + r'\b', sample))
    if found < 4:
        return False
    if sample.count('؟') / max(len(sample), 1) > 0.02:
        return False
    return True

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
    """تقسيم وتبويب: على الترويسات (باب/فصل/مبحث...) مع مسار القسم لكل مقطع.

    - تُتجاهل سطور الترقيم الصرفة (أرقام الصفحات) كترويسات.
    - كل مقطع يحمل: heading (أقرب ترويسة) + section (آخر باب/فصل كبير).
    """
    text = re.sub(r"\n{3,}", "\n\n", text.strip())
    if not text:
        return []
    # إسقاط سطور أرقام الصفحات الصرفة
    lines_all = [ln for ln in text.split('\n') if not (ln.strip() != "" and _NUMERIC_LINE_RE.fullmatch(ln.strip()) and len(ln.strip()) <= 6)]
    text = '\n'.join(lines_all)
    parts = re.split(
        r"\n(?=(?:الباب|الفصل|المبحث|المطلب|الفرع|المقصد|التمهيد|التوطئة|المقدمة|الخاتمة|الفصل)[\s:0-9٠-٩\-—\.]+)",
        text,
    )
    chunks = []
    section = ""
    for part in parts:
        part = part.strip()
        if not part:
            continue
        lines = part.split("\n", 1)
        first = lines[0].strip()
        heading = ""
        if len(lines) > 1 and len(first) < 80 and not _NUMERIC_LINE_RE.fullmatch(first):
            heading = first
            if re.match(r"^(?:الباب|الفصل|التمهيد|التوطئة|المقدمة|الخاتمة)\b", heading):
                section = heading
        body = part if not heading else (lines[1] if len(lines) > 1 else part)
        if len(body) <= CHUNK_SIZE:
            if len(body.strip()) >= 40:
                chunks.append({"heading": heading, "section": section, "text": body.strip()})
        else:
            step = CHUNK_SIZE - CHUNK_OVERLAP
            for i in range(0, len(body), step):
                piece = body[i:i + CHUNK_SIZE].strip()
                if len(piece) >= 40:
                    chunks.append({"heading": heading, "section": section, "text": piece})
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


def decode_docx(raw):
    """استخراج نص DOCX (فقرات + جداول). يعيد نصاً فارغاً عند الفشل."""
    try:
        import io as _io
        from docx import Document
        doc = Document(_io.BytesIO(raw))
        parts = []
        for p in doc.paragraphs:
            t = (p.text or "").strip()
            if t:
                parts.append(t)
        for tbl in getattr(doc, "tables", []):
            for row in tbl.rows:
                cells = [(c.text or "").strip() for c in row.cells]
                line = " | ".join([c for c in cells if c])
                if line:
                    parts.append(line)
        return "\n".join(parts)
    except Exception as e:
        print(f"    DOCX فشل: {e}")
        return ""


def _page_good(t):
    """جودة صفحة واحدة: طول أدنى + عربية غالبة + كلمتان شائعتان على الأقل."""
    if not t or len(t) < 80:
        return False
    sample = t[:6000]
    total = len(re.sub(r'\s', '', sample)) or 1
    if len(re.findall(r'[\u0600-\u06FF]', sample)) / total < 0.4:
        return False
    found = sum(1 for w in _COMMON_AR if re.search(r'\b' + w + r'\b', sample))
    return found >= 2


def extract_hybrid(pdf_bytes, ocr):
    """استخراج هجين صفحة-صفحة: نص PyMuPDF الجيد يُؤخذ كما هو، والصفحة
    التالفة التي تحتها صور (ممسوحة) تُعاد قراءتها بـ Tesseract.

    يعالج حالة الغلاف السليم + المحتوى الممسوح بطبقة OCR تالفة، حيث يفشل
    أي فحص على مستوى الكتاب كاملاً (الصفحات السليمة تخفي التالفة).
    """
    try:
        import pymupdf as fitz
    except ImportError:
        import fitz
    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    n = doc.page_count
    out_pages = []
    ocr_pages = 0
    for i in range(n):
        page = doc[i]
        try:
            t = fix_reversed_arabic(ocr.light_clean(page.get_text("text")))
        except Exception:
            t = ""
        needs_ocr = not _page_good(t)
        if needs_ocr:
            try:
                has_images = bool(page.get_images(full=True))
            except Exception:
                has_images = True
        else:
            has_images = False
        if needs_ocr and has_images:
            try:
                from pdf2image import convert_from_bytes
                import pytesseract
                imgs = convert_from_bytes(pdf_bytes, dpi=150, first_page=i + 1,
                                          last_page=i + 1, fmt='jpeg', thread_count=1)
                if imgs:
                    o = fix_reversed_arabic(ocr.fix_arabic_errors(
                        pytesseract.image_to_string(imgs[0].convert('L'),
                                                    lang='ara', config='--psm 6 --oem 1')))
                    if len(o) > max(80, len(t) // 2):
                        t = o
                    ocr_pages += 1
                    del imgs
            except Exception as e:
                print(f"    OCR صفحة {i + 1} فشل: {e}")
        out_pages.append(t)
    try:
        doc.close()
    except Exception:
        pass
    text = "\n".join(out_pages)
    method = "PyMuPDF" if ocr_pages == 0 else f"Hybrid+OCR({ocr_pages}/{n})"
    return text, method


def process_pdf_bytes(pdf_bytes, ocr):
    """يستخرج النص هجيناً صفحة-صفحة مع بوابة جودة لكل صفحة."""
    try:
        return extract_hybrid(pdf_bytes, ocr)
    except Exception as e:
        print(f"    الهجين فشل ({e}) — مسار النص الكامل الاحتياطي")
        raw_p = ocr.light_clean(ocr.extract_pymupdf(pdf_bytes))
        fixed_p = fix_reversed_arabic(raw_p)
        if is_good_library_text(fixed_p):
            return fixed_p, "PyMuPDF-fallback"
        raw_t = ocr.fix_arabic_errors(ocr.extract_tesseract(pdf_bytes))
        fixed_t = fix_reversed_arabic(raw_t)
        return fixed_t, "Tesseract-fallback"


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

    def list_children(fid):
        return drive.files().list(
            q=f"'{fid}' in parents and trashed=false",
            fields="files(id, name, mimeType, modifiedTime)",
            pageSize=1000,
        ).execute().get("files", [])

    # تشخيص ذاتي: هل المجلد نفسه مرئي لحساب الخدمة؟ (يكشف المعرف الخاطئ أو المشاركة الناقصة)
    try:
        meta = drive.files().get(fileId=folder_id, fields="id,name,mimeType").execute()
        print(f"المجلد المستهدف: {meta.get('name')} ({meta.get('id')})")
    except Exception as e:
        print(f"⚠️ تعذر رؤية المجلد نفسه: {e}")
        print("الأسباب: (1) قيمة Secret ‏GDRIVE_LIBRARY_FOLDER_ID خاطئة، (2) المجلد غير مشارك مع حساب الخدمة بدور محرر.")

    # المستوى الأول + الغوص داخل المجلدات الفرعية (تصنيفات الكتب)
    # تنبيه: أي مجلد أعمق من مستوى واحد يُسجل كمتجاهل بدل الصمت.
    top = list_children(folder_id)
    files = [f for f in top if f.get("mimeType") != "application/vnd.google-apps.folder"]
    deep_folders = []
    for sub in [f for f in top if f.get("mimeType") == "application/vnd.google-apps.folder"]:
        try:
            for f in list_children(sub["id"]):
                if f.get("mimeType") != "application/vnd.google-apps.folder":
                    f["_sub"] = sub["name"]
                    files.append(f)
                else:
                    deep_folders.append(f"{sub.get('name')}/{f.get('name')}")
        except Exception as e:
            print(f"    تعذر قراءة المجلد الفرعي {sub.get('name')}: {e}")
    print(f"ملفات المكتبة على Drive: {len(files)}")

    # حماية 1: منع الكتابة الفارغة — خلل مؤقت (0 ملف) لا يمسح الفهرس السابق الجيد.
    # منطق التخطي للملفات المرفوعة سابقاً (_manifest) يبقى كما هو ولا يتأثر.
    if len(files) == 0 and len(old_manifest) > 0:
        print("⚠️ Drive أعاد 0 ملف مع وجود فهرس سابق — إجهاض لحماية البيانات (لا كتابة ولا مسح)")
        return 0

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
    ignored = []
    for f in files:
        name, mt, fbid, mod = f["name"], f.get("mimeType", ""), f["id"], f.get("modifiedTime", "")
        sub = f.get("_sub", "")
        disp = f"{sub} / {name}" if sub else name
        low = name.lower()
        if low.endswith(".zip") or mt == "application/zip":
            try:
                zf = zipfile.ZipFile(io.BytesIO(download(fbid)))
                for inner in zf.namelist():
                    il = inner.lower()
                    if il.endswith((".pdf", ".txt", ".docx")) and not inner.startswith("__"):
                        kind = "pdf" if il.endswith(".pdf") else ("docx" if il.endswith(".docx") else "txt")
                        units.append({
                            "title": (f"{sub} / " if sub else "") + re.sub(r"\.(pdf|txt|docx)$", "", inner.split("/")[-1], flags=re.I),
                            "data": zf.read(inner), "kind": kind,
                            "skip_key": f"zip:{name}/{inner}@{mod}",
                            "source": f"{name} ← {inner}", "drive_id": fbid,
                        })
            except Exception as e:
                print(f"    تعذر فك: {name}: {e}")
        elif low.endswith(".pdf") or mt == "application/pdf":
            units.append({
                "title": re.sub(r"\.pdf$", "", disp, flags=re.I),
                "data": None, "kind": "pdf", "skip_key": f"file:{name}@{mod}",
                "source": name, "drive_id": fbid, "fbid": fbid,
            })
        elif low.endswith(".txt") or mt == "text/plain":
            units.append({
                "title": re.sub(r"\.txt$", "", disp, flags=re.I),
                "data": None, "kind": "txt", "skip_key": f"file:{name}@{mod}",
                "source": name, "drive_id": fbid, "fbid": fbid,
            })
        elif low.endswith(".docx") or mt == "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            units.append({
                "title": re.sub(r"\.docx$", "", disp, flags=re.I),
                "data": None, "kind": "docx", "skip_key": f"file:{name}@{mod}",
                "source": name, "drive_id": fbid, "fbid": fbid,
            })
        elif low.endswith(".doc") or mt == "application/msword":
            ignored.append({"file": disp, "reason": "DOC قديم غير مدعوم — حوّله إلى DOCX أو PDF"})
        else:
            ignored.append({"file": disp, "reason": f"صيغة غير مدعومة ({mt or low.rsplit('.', 1)[-1]}) — المدعوم: PDF/TXT/DOCX/ZIP"})

    print(f"وحدات للمعالجة: {len(units)}")

    # وسم المفاتيح بنسخة المخطط: أي تغيير بـ SCHEMA_VERSION يعيد المعالجة مرة واحدة.
    for u in units:
        u["skip_key"] = SCHEMA_VERSION + "|" + u["skip_key"]

    # حماية 2: ملفات موجودة لكن لا وحدة صالحة (صيغ غير مدعومة) — لا تمسح السابق أيضاً.
    if len(units) == 0 and len(old_manifest) > 0:
        print("⚠️ لا وحدات صالحة (PDF/TXT/DOCX/ZIP) مع وجود فهرس سابق — إجهاض لحماية البيانات")
        return 0

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
        elif u["kind"] == "docx":
            text = fix_reversed_arabic(ocr.strip_invisible(decode_docx(raw)))
            method = "DOCX"
        else:
            text = fix_reversed_arabic(ocr.strip_invisible(decode_txt(raw)))
        chunks = chunk_text(text)
        print(f"    ✓ {method}: {len(chunks)} مقطعاً")
        # بوابة الجودة النهائية على النص بعد كل الإصلاحات
        if len(chunks) < 3:
            st = "TOO_FEW"
        elif "fallback" in method or not is_good_library_text(text):
            st = "GARBLED"
        else:
            st = "ok"
        book_id = make_id(u["title"])
        did = u.get("drive_id", "")
        data = {
            "id": book_id, "title": u["title"], "type": detect_type(u["title"]),
            "source_file": u["source"], "extraction_method": method,
            "drive_file_id": did,
            "drive_url": f"https://drive.google.com/file/d/{did}/view" if did else "",
            "chunks_count": len(chunks),
            "chunks": [{"n": i + 1, "heading": c.get("heading", ""), "section": c.get("section", ""), "text": c["text"]}
                        for i, c in enumerate(chunks)],
        }
        with open(os.path.join(OUTPUT_DIR, f"{book_id}.json"), "w", encoding="utf-8") as jf:
            json.dump(data, jf, ensure_ascii=False, indent=2)
        new_manifest[u["skip_key"]] = {"book_id": book_id}
        index_books.append({
            "id": book_id, "title": u["title"], "type": data["type"],
            "file": f"{book_id}.json", "chunks_count": len(chunks),
        })
        report.append({"title": u["title"], "status": st, "chunks": len(chunks), "method": method})
        if st != "ok":
            print(f"    ⚠️ {st}: مقاطع قليلة أو نص مشوّه — راجع جودة الملف")

    # حماية 3 (احتياط أخير): لا تدفع فهرساً فارغاً فوق فهرس سابق جيد.
    if len(index_books) == 0 and len(old_manifest) > 0:
        print("⚠️ النتيجة 0 كتاب مع وجود سابق — إجهاض نهائي بدون كتابة (حماية من المسح)")
        return 0

    save_manifest(new_manifest)
    with open(os.path.join(OUTPUT_DIR, "index.json"), "w", encoding="utf-8") as jf:
        json.dump({
            "last_updated": datetime.now(timezone.utc).isoformat(),
            "total_books": len(index_books), "books": index_books,
        }, jf, ensure_ascii=False, indent=2)
    with open(os.path.join(OUTPUT_DIR, "_report.json"), "w", encoding="utf-8") as rf:
        json.dump({"generated_at": datetime.now(timezone.utc).isoformat(),
                   "books": report, "ignored": ignored,
                   "deep_folders_skipped": deep_folders}, rf, ensure_ascii=False, indent=2)
    if ignored:
        print("\n=== ملفات متجاهلة (لم تدخل الفهرس) ===")
        for ig in ignored:
            print(f"  ⏭ {ig.get('file')}: {ig.get('reason')}")
    if deep_folders:
        print("\n=== مجلدات عميقة لم تُفحص (أعمق من مستوى واحد) ===")
        for df in deep_folders:
            print(f"  📁 {df}")
    print(f"\n=== {len(index_books)} مرجعاً في فهرس المكتبة ===")


if __name__ == "__main__":
    sys.exit(main() or 0)
