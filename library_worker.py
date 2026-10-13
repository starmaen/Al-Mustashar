"""عامل المكتبة القانونية: مجلد Drive --> data/library/*.json (مفهرسة) --> التطبيق.

يقبل: PDF (OCR/نص) + TXT (مباشرة) + ZIP (يفك ضغطه ويعالج ما بداخله).
يعالج الجديد/المعدّل فقط عبر _manifest.json، ويكتب _report.json لبوابة الجودة.

المخرجات (تطابق شاشة المكتبة في التطبيق):
  data/library/{bookId}.json: {id, title, type, year, source_file,
      drive_file_id, drive_url, chunks_count, toc_count, toc: [{t, p}],
      chunks: [{n, heading, text}]}
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

# نسخة مخطط المعالجة: تغييرها يعيد معالجة الكتب مرة واحدة.
# v5-full: فهرس احتياطي من بنية النص + تنظيف NFKC لنصوص المكتبة.
SCHEMA_VERSION = "v8-repair2"

# أقسام المكتبة الإلكترونية (بالترتيب المعتمد في التطبيق)
SECTIONS = ["مراجع قانونية", "قوانين واتفاقات دولية", "اجتهادات قضائية", "كتب منوعة"]

_SECTION_KEYWORDS = [
    ("اجتهادات قضائية", ["اجتهاد", "اجتهادات", "قرارات محكمة", "نقض",
                          "أحكام المحاكم", "السوابق", "مبادئ المحاكم"]),
    ("مراجع قانونية", ["شرح", "تعليق", "موسوعة", "مرجع", "صديق المحامي",
                        "دليل المحامي", "الدعاوى", "الطعون", "الصيغ"]),
    ("قوانين واتفاقات دولية", ["اتفاقية", "اتفاقيات", "اتفاق", "معاهدة", "معاهدات",
                                "بروتوكول", "نظام روما", "ميثاق", "عهد دولي",
                                "إعلان عالمي", "قانون"]),
]


def _section_key_hit(hay, key):
    """مطابقة بكلمة مستقلة مع جواز الـ التعريف (تتفادى قانونية/اتفاقية عند اللزوم)."""
    pat = r"(?<![\u0600-\u06FF])(?:ال)?" + re.escape(key) + r"(?![\u0600-\u06FF])"
    return re.search(pat, hay) is not None


def _norm_section_hay(s):
    s = re.sub(r"[_—–\-/]+", " ", s or "")
    return re.sub(r"\s+", " ", s).strip()


def detect_section(title, sub=""):
    """تصنيف الكتاب في قسم: اسم المجلد الفرعي أولاً، ثم عنوان الكتاب."""
    hay = _norm_section_hay(f"{sub} {title}")
    for section, words in _SECTION_KEYWORDS:
        for w in words:
            if _section_key_hit(hay, w):
                return section
    return "كتب منوعة"


def section_order(section):
    try:
        return SECTIONS.index(section)
    except ValueError:
        return len(SECTIONS)

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


def write_if_changed(path, obj):
    """يكتب JSON فقط عند اختلاف المحتوى (يتجاهل الطوابع الزمنية).
    يمنع طابعاً جديداً كل تشغيل — وإلا تعارض الدفع مع أي تعديل ويب.
    يعيد True إذا كتب فعلاً."""
    new_text = json.dumps(obj, ensure_ascii=False, indent=2)
    try:
        with open(path, "r", encoding="utf-8") as f:
            old_text = f.read()
        old = json.loads(old_text)
        new = json.loads(new_text)
        for k in ("last_updated", "generated_at"):
            if isinstance(old, dict):
                old.pop(k, None)
            if isinstance(new, dict):
                new.pop(k, None)
        if old == new:
            return False
    except (FileNotFoundError, ValueError, OSError):
        pass
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_text)
    return True


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


def clean_library_text(text):
    """تنظيف خاص بالمكتبة فقط (لا يمس القوانين):
    توحيد أشكال العرض + إزالة شوائب ترميز خطوط PDF.
    """
    if not text:
        return text
    try:
        import unicodedata
        text = unicodedata.normalize("NFKC", text)
    except Exception:
        pass
    # شائب خطوط مستندات الأمم المتحدة (يظهر بدل التطويل/الوصل)
    text = text.replace("ѧ", "").replace("Ѧ", "")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def fallback_toc_from_text(text, limit=200):
    """فهرس احتياطي من بنية النص عند غياب إشارات PDF والخط الكبير:
    المواد المرقمة (المادة 1..) ثم الترويسات القصيرة البنيوية.
    """
    if not text:
        return []
    seen = set()
    toc = []

    def push(t):
        t = re.sub(r"\s+", " ", (t or "").strip())
        if len(t) < 3 or len(t) > 120 or t in seen:
            return
        if re.fullmatch(r"[\d٠-٩\s\-—–_.*·•«»\"'()\[\]]+", t):
            return
        seen.add(t)
        toc.append({"t": t, "p": 0})

    # 1) المواد المرقمة — فهرس طبيعي للاتفاقيات والقوانين العالمية
    for m in re.finditer(r"(?m)^\s*(المادة\s*[\d٠-٩]{1,4})\s*(?::|[-–—])?\s*(.{0,60}?)\s*$", text):
        head = (m.group(1) + (": " + m.group(2).strip() if m.group(2).strip() else "")).strip()
        push(head)
        if len(toc) >= limit:
            return toc
    # 2) ترويسات بنيوية قصيرة منفردة بسطرها
    for m in re.finditer(
            r"(?m)^\s*((?:الباب|الفصل|المبحث|المطلب|الفرع|المقصد|التمهيد|التوطئة|المقدمة|الخاتمة|الديباجة|التعاريف|الغرض|نطاق|أحكام ختامية|أحكام عامة)[^:\n]{0,60})\s*$",
            text):
        push(m.group(1))
        if len(toc) >= limit:
            return toc
    return toc


def build_toc_entries(outline, font_heads, limit=200):
    """يدمج عناوين PDF المرجعية وعناوين الخط الكبير: إزالة تكرار وضجيج."""
    seen = set()
    toc = []
    for title, page in list(outline) + list(font_heads):
        t = re.sub(r"\s+", " ", (title or "").strip())
        if len(t) < 3 or len(t) > 120:
            continue
        if re.fullmatch(r"[\d٠-٩\s\-—–_.*·•«»\"'()\[\]]+", t):
            continue
        if t in seen:
            continue
        seen.add(t)
        toc.append({"t": t, "p": page or 0})
        if len(toc) >= limit:
            break
    return toc


def extract_pdf_toc(pdf_bytes):
    """فهرس الكتاب من PDF: الإشارات المرجعية أولاً، ثم سطور الخط الكبير.

    يعيد [{t: العنوان, p: الصفحة}]. آمن تماماً: أي فشل يعيد [].
    """
    try:
        import pymupdf as _pm
    except ImportError:
        try:
            import fitz as _pm
        except ImportError:
            return []
    try:
        doc = _pm.open(stream=pdf_bytes, filetype="pdf")
    except Exception:
        return []
    try:
        outline = []
        try:
            for lvl, title, page in doc.get_toc():
                if title and page and page > 0:
                    outline.append((title, page))
        except Exception:
            pass
        font_heads = []
        try:
            for pno in range(doc.page_count):
                try:
                    d = doc[pno].get_text("dict")
                except Exception:
                    continue
                sizes = []
                lines = []
                for b in d.get("blocks", []):
                    for l in b.get("lines", []):
                        txt = "".join(s.get("text", "") for s in l.get("spans", [])).strip()
                        if not txt:
                            continue
                        sps = [s for s in l.get("spans", []) if (s.get("text") or "").strip()]
                        if not sps:
                            continue
                        sz = max(s.get("size", 0) for s in sps)
                        ar = len(re.findall(r"[\u0600-\u06FF]", txt))
                        lines.append((txt, sz, ar, len(txt)))
                        if ar > len(txt) * 0.5 and len(txt) > 10:
                            sizes.append(sz)
                if not sizes:
                    continue
                sizes.sort()
                body = sizes[len(sizes) // 2]
                if body <= 0:
                    continue
                for txt, sz, ar, ln in lines:
                    if (sz >= body * 1.3 and ar > ln * 0.6 and 4 <= ln <= 100
                            and not re.fullmatch(r"[\d٠-٩\s\-—–_.*·•«»\"'()\[\]]+", txt)):
                        font_heads.append((txt, pno + 1))
        except Exception:
            pass
        try:
            doc.close()
        except Exception:
            pass
        return build_toc_entries(outline, font_heads)
    except Exception:
        try:
            doc.close()
        except Exception:
            pass
        return []


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
        # نطاق كامل: القراءة للفهرسة + إنشاء مجلدات الأقسام ونقل الكتب إليها.
        # (المفتاح في Secrets، والكتابة محصورة بمجلد المكتبة فقط)
        scopes=["https://www.googleapis.com/auth/drive"],
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

    # تنظيم تلقائي لمجلدات الأقسام: أي كتاب في الجذر ينتقل لمجلد قسمه.
    # (مجلدات مطابقة لأقسام التطبيق؛ الرفع للجذر يكفي والباقي تلقائي ومسجل)
    moved = []

    def ensure_section_folder(parent_id, section_name, existing):
        for s in existing:
            if s.get("name") == section_name:
                return s["id"]
        try:
            created = drive.files().create(
                body={"name": section_name,
                      "mimeType": "application/vnd.google-apps.folder",
                      "parents": [parent_id]},
                fields="id").execute()
            new_id = created.get("id")
            # سياسة القراءة فقط: أي شخص لديه الرابط = عارض، لا محرر أبداً.
            # (ملفاتك المنقولة تحتفظ بمشاركتها الحالية؛ الجديد يُفتح قراءة فقط)
            try:
                drive.permissions().create(
                    fileId=new_id,
                    body={"type": "anyone", "role": "reader"}).execute()
            except Exception as e:
                print(f"    ⚠️ تعذر ضبط مشاركة {section_name} قراءةً: {e}")
            print(f"    📁 أنشئ مجلد القسم: {section_name} (قراءة للجميع)")
            return new_id
        except Exception as e:
            print(f"    ⚠️ تعذر إنشاء مجلد {section_name}: {e}")
            return None

    try:
        section_folders = {}
        subs_now = [f for f in top if f.get("mimeType") == "application/vnd.google-apps.folder"]
        for section_name in SECTIONS:
            fid = ensure_section_folder(folder_id, section_name, subs_now)
            if fid:
                section_folders[section_name] = fid
                # حدّث القائمة المحلية ليتعرف عليها الغوص التالي
                if not any(s.get("id") == fid for s in subs_now):
                    subs_now.append({"id": fid, "name": section_name})
        if section_folders:
            for f in [x for x in top if x.get("mimeType") != "application/vnd.google-apps.folder"]:
                clean = re.sub(r"\.(pdf|txt|docx|zip)$", "", f.get("name", ""), flags=re.I)
                section = detect_section(clean, "")
                target = section_folders.get(section)
                if target and target != folder_id:
                    try:
                        drive.files().update(
                            fileId=f["id"], addParents=target, removeParents=folder_id,
                            fields="id, parents").execute()
                        moved.append({"file": f.get("name"), "section": section})
                        print(f"    ➜ نُقل: {f.get('name')} إلى [{section}]")
                    except Exception as e:
                        print(f"    ⚠️ تعذر نقل {f.get('name')}: {e}")
            if moved:
                # إعادة القراءة بعد النقل ليتسق ما بعده مع المواقع الجديدة
                top = list_children(folder_id)
                files = [f for f in top if f.get("mimeType") != "application/vnd.google-apps.folder"]
                for sub in [f for f in top if f.get("mimeType") == "application/vnd.google-apps.folder"]:
                    try:
                        for f in list_children(sub["id"]):
                            if f.get("mimeType") != "application/vnd.google-apps.folder":
                                f["_sub"] = sub["name"]
                                files.append(f)
                    except Exception:
                        pass
                print(f"ملفات المكتبة بعد التنظيم: {len(files)}")
    except Exception as e:
        print(f"    ⚠️ التنظيم التلقائي تعذر: {e} — المتابعة بدونه")

    # حماية 1: منع الكتابة الفارغة — خلل مؤقت (0 ملف) لا يمسح الفهرس السابق الجيد.
    # منطق التخطي للملفات المرفوعة سابقاً (_manifest) يبقى كما هو ولا يتأثر.
    if len(files) == 0 and len(old_manifest) > 0:
        print("⚠️ Drive أعاد 0 ملف مع وجود فهرس سابق — إجهاض لحماية البيانات (لا كتابة ولا مسح)")
        return 0

    index_books = []
    report = []
    wrote_books = [False]

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
        # العنوان باسم الملف مجرداً (مستقر عند نقل الملف بين الأقسام)؛
        # القسم يُحسم من المجلد الفرعي أولاً ثم العنوان.
        low = name.lower()
        if low.endswith(".zip") or mt == "application/zip":
            try:
                zf = zipfile.ZipFile(io.BytesIO(download(fbid)))
                for inner in zf.namelist():
                    il = inner.lower()
                    if il.endswith((".pdf", ".txt", ".docx")) and not inner.startswith("__"):
                        kind = "pdf" if il.endswith(".pdf") else ("docx" if il.endswith(".docx") else "txt")
                        units.append({
                            "title": re.sub(r"\.(pdf|txt|docx)$", "", inner.split("/")[-1], flags=re.I),
                            "data": zf.read(inner), "kind": kind,
                            "skip_key": f"zip:{name}/{inner}@{mod}",
                            "source": (f"{sub} / " if sub else "") + f"{name} ← {inner}",
                            "drive_id": fbid, "sub": sub,
                        })
            except Exception as e:
                print(f"    تعذر فك: {name}: {e}")
        elif low.endswith(".pdf") or mt == "application/pdf":
            units.append({
                "title": re.sub(r"\.pdf$", "", name, flags=re.I),
                "data": None, "kind": "pdf", "skip_key": f"file:{name}@{mod}",
                "source": (f"{sub} / " if sub else "") + name,
                "drive_id": fbid, "fbid": fbid, "sub": sub,
            })
        elif low.endswith(".txt") or mt == "text/plain":
            units.append({
                "title": re.sub(r"\.txt$", "", name, flags=re.I),
                "data": None, "kind": "txt", "skip_key": f"file:{name}@{mod}",
                "source": (f"{sub} / " if sub else "") + name,
                "drive_id": fbid, "fbid": fbid, "sub": sub,
            })
        elif low.endswith(".docx") or mt == "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            units.append({
                "title": re.sub(r"\.docx$", "", name, flags=re.I),
                "data": None, "kind": "docx", "skip_key": f"file:{name}@{mod}",
                "source": (f"{sub} / " if sub else "") + name,
                "drive_id": fbid, "fbid": fbid, "sub": sub,
            })
        elif low.endswith(".doc") or mt == "application/msword":
            ignored.append({"file": (f"{sub} / " if sub else "") + name, "reason": "DOC قديم غير مدعوم — حوّله إلى DOCX أو PDF"})
        else:
            ignored.append({"file": (f"{sub} / " if sub else "") + name, "reason": f"صيغة غير مدعومة ({mt or low.rsplit('.', 1)[-1]}) — المدعوم: PDF/TXT/DOCX/ZIP"})

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
                    # القسم من الموقع الحالي أولاً (قد يكون الملف نُقل تلقائياً)، ثم المخزن، ثم العنوان
                    cur_section = detect_section(u.get("title", ""), u.get("sub", ""))
                    if not u.get("sub"):
                        cur_section = ex.get("section") or detect_section(ex.get("title", ""), "")
                    index_books.append({
                        "id": ex["id"], "title": ex["title"], "type": ex.get("type", "مرجع"),
                        "section": cur_section,
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
            text = ocr.strip_invisible(decode_docx(raw))
            method = "DOCX"
        else:
            text = ocr.strip_invisible(decode_txt(raw))
        text = clean_library_text(text)
        # إصلاح التراكيب اللغوية (موصول لا/همزات/تاء مربوطة) — قاموس صريح آمن
        try:
            import arabic_fix
            text, _nfix = arabic_fix.repair_text(text)
        except Exception:
            pass
        chunks = chunk_text(text)
        print(f"    ✓ {method}: {len(chunks)} مقطعاً")
        # فهرس الكتاب (PDF فقط): إشارات المستند + سطور الخط الكبير، ثم بنية النص
        toc = []
        if u["kind"] == "pdf":
            try:
                toc = extract_pdf_toc(raw)
            except Exception as e:
                print(f"    الفهرس فشل: {e}")
            if not toc:
                toc = fallback_toc_from_text(text)
            print(f"    📑 الفهرس: {len(toc)} عنواناً")
        # فحص جودة المكتبة (مثل القوانين): عربية كافية + كلمات شائعة + طول أدنى
        st = "ok" if len(chunks) >= 3 else "TOO_FEW"
        try:
            _ar = len(re.findall(r'[\u0600-\u06FF]', text))
            _tot = len(re.sub(r'\s', '', text)) or 1
            _common = ['من', 'في', 'على', 'إلى', 'الذي', 'التي', 'هذا', 'القانون', 'المادة', 'أو', 'أن', 'لا']
            _found = sum(1 for w in _common if re.search(r'\b' + w + r'\b', text[:20000]))
            if (_ar / _tot) < 0.5 or _found < 4:
                st = "GARBLED"
        except Exception:
            pass
        book_id = make_id(u["title"])
        did = u.get("drive_id", "")
        section = detect_section(u["title"], u.get("sub", ""))
        data = {
            "id": book_id, "title": u["title"], "type": detect_type(u["title"]),
            "section": section,
            "source_file": u["source"], "extraction_method": method,
            "drive_file_id": did,
            "drive_url": f"https://drive.google.com/file/d/{did}/view" if did else "",
            "chunks_count": len(chunks),
            "toc": toc,
            "toc_count": len(toc),
            "chunks": [{"n": i + 1, "heading": c["heading"], "text": c["text"]}
                       for i, c in enumerate(chunks)],
        }
        with open(os.path.join(OUTPUT_DIR, f"{book_id}.json"), "w", encoding="utf-8") as jf:
            json.dump(data, jf, ensure_ascii=False, indent=2)
        wrote_books[0] = True
        new_manifest[u["skip_key"]] = {"book_id": book_id}
        index_books.append({
            "id": book_id, "title": u["title"], "type": data["type"],
            "section": section,
            "file": f"{book_id}.json", "chunks_count": len(chunks),
        })
        report.append({"title": u["title"], "status": st, "chunks": len(chunks), "method": method, "toc": len(toc)})
        if st != "ok":
            print(f"    ⚠️ {st}: مقاطع قليلة أو نص مشوّه — راجع جودة الملف")

    # حماية 3 (احتياط أخير): لا تدفع فهرساً فارغاً فوق فهرس سابق جيد.
    if len(index_books) == 0 and len(old_manifest) > 0:
        print("⚠️ النتيجة 0 كتاب مع وجود سابق — إجهاض نهائي بدون كتابة (حماية من المسح)")
        return 0

    wrote = {"books": wrote_books[0]}
    save_manifest(new_manifest)
    # ترتيب الفهرس: الأقسام بالتسلسل المعتمد ثم العناوين أبجدياً
    index_books.sort(key=lambda b: (section_order(b.get("section", "")), b.get("title", "")))
    wrote_index = write_if_changed(os.path.join(OUTPUT_DIR, "index.json"), {
        "last_updated": datetime.now(timezone.utc).isoformat(),
        "total_books": len(index_books), "books": index_books,
    })
    wrote_report = write_if_changed(os.path.join(OUTPUT_DIR, "_report.json"), {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "books": report, "ignored": ignored, "moved": moved,
        "deep_folders_skipped": deep_folders})
    if not (wrote["books"] or wrote_index or wrote_report or moved):
        print("لا جديد — كل الملفات مطابقة (لا كتابة ولا دفع).")
    if moved:
        print("\n=== كتب نُقلت تلقائياً لأقسامها ===")
        for mv in moved:
            print(f"  ➜ {mv.get('file')} → [{mv.get('section')}]")
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
