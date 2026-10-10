import os
import json
import re
import io
from datetime import datetime
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload
import fitz

SCOPES = ['https://www.googleapis.com/auth/drive.readonly']
OUTPUT_DIR = 'data/laws'
MANIFEST_PATH = 'data/laws/_manifest.json'


def load_manifest():
    if os.path.exists(MANIFEST_PATH):
        try:
            with open(MANIFEST_PATH, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def save_manifest(m):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    with open(MANIFEST_PATH, 'w', encoding='utf-8') as f:
        json.dump(m, f, ensure_ascii=False, indent=2)


def clean_filename(filename):
    name = re.sub(r'(\.pdf|\.PDF)+$', '', filename)
    name = re.sub(r'[_\-]?(نسخة|copie|copy|final|نهائي|الجديد|جديد)\s*', ' ', name, flags=re.IGNORECASE)
    return re.sub(r'\s+', ' ', name).strip()


def parse_filename(filename):
    name = clean_filename(filename)
    parts = name.split()
    if len(parts) < 2:
        return None
    first = parts[0].strip()
    type_map = {
        'اساسي': 'أساسي', 'أساسي': 'أساسي', 'الأساسي': 'أساسي', 'الاساسي': 'أساسي',
        'معدل': 'معدل', 'تعديل': 'معدل', 'التعديل': 'معدل'
    }
    law_type = 'أساسي'
    if first in type_map:
        law_type = type_map[first]
        law_name_parts = parts[1:]
    else:
        law_name_parts = parts
    full_text = ' '.join(law_name_parts)
    year = None
    ym = re.search(r'(?:لعام|عام|سنة|لسنة|سنه|الصادر\s+عام)\s*(\d{4})', full_text)
    if ym:
        year = ym.group(1)
        before = full_text[:ym.start()].strip()
        after = full_text[ym.end():].strip()
        law_name_parts = (before + ' ' + after).split()
    else:
        for i in range(len(law_name_parts) - 1, -1, -1):
            if re.match(r'^\d{4}$', law_name_parts[i]):
                year = law_name_parts[i]
                law_name_parts = law_name_parts[:i] + law_name_parts[i+1:]
                break
    if not year:
        year = 'غير-محدد'
    law_name = re.sub(r'\s+', ' ', ' '.join(law_name_parts)).strip()
    return {'type': law_type, 'name': law_name, 'year': year}


def make_id(law_name, year):
    clean = re.sub(r'\s+', '-', law_name.strip())
    clean = re.sub(r'[\\/:*?"<>|]', '', clean)
    return f"{clean}-{year}"


def normalize_num(s):
    return s.translate(str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789')) if s else None



DIRECTIONAL_RE = re.compile('[\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff\u061c]')
HARAKAT_RE = re.compile('[\u064b-\u065f\u0670]')


def strip_invisible(text):
    """إزالة علامات اتجاه النص والمحارف غير المرئية التي يتركها Tesseract"""
    if not text:
        return text
    return DIRECTIONAL_RE.sub('', text)


def fix_arabic_errors(text):
    """إصلاح الأخطاء الشائعة في Tesseract للعربية (مسار OCR فقط)"""
    if not text:
        return text
    text = strip_invisible(text)
    # توحيد الهمزات والألفات دون المساس بالتاء المربوطة (لها قيمة قانونية)
    text = text.replace('ٱ', 'ا')
    # إزالة التشكيل الذي يضيفه Tesseract ضجيجاً (النصوص القانونية غير مشكولة)
    text = HARAKAT_RE.sub('', text)
    # همزة شاردة بعد علامات الترقيم: "يوجد,ء فبمقتضى" → "يوجد, فبمقتضى"
    text = re.sub(r'([،؛:.؟!?,;:])\s*ء\s+', r'\1 ', text)
    text = re.sub(r'\s+ء\s+', ' ', text)
    # مسافات قبل علامات الترقيم العربية واللاتينية
    text = re.sub(r'\s+([،؛:.؟!?,;:])', r'\1', text)
    # إصلاحات همزات شائعة وآمنة فقط (حُذفت قاعدتا اا→ا و اال→الا الخطيرتان)
    text = re.sub(r'األ', 'الأ', text)
    text = re.sub(r'اإل', 'الإ', text)
    text = re.sub(r'\bالي\b', 'إلى', text)
    text = re.sub(r'\bاذا\b', 'إذا', text)
    # تطبيع الأرقام المشرقية داخل رؤوس المواد فقط (يُترك المتن كما هو)
    text = re.sub(r'[ \t]+', ' ', text)
    text = re.sub(r'\n{3,}', '\n\n', text)
    return text.strip()


def light_clean(text):
    """تنظيف خفيف لمسار PyMuPDF (نصه سليم أصلاً — لا إصلاحات عدوانية)"""
    if not text:
        return text
    text = strip_invisible(text)
    text = re.sub(r'[ \t]+', ' ', text)
    text = re.sub(r'\n{3,}', '\n\n', text)
    return text.strip()


def parse_articles(text):
    articles = []
    # يقبل ايضا "؟" و "?" مكان الرقم (Tesseract يقراها 2 و 3 احيانا) - ترقم تسلسليا لاحقا
    ZW = '\u200B-\u200F'
    pattern = re.compile(
        r'(?:\u0627\u0644\u0645\u0627\u062f\u0629|\u0627\u0644\u0645\u0627\u062f\u0647|\u0645\u0627\u062f\u0629|\u0645\u0627\u062f\u0647)\s*'
        r'[(\[/\s' + ZW + r']*'
        r'([\u0660-\u0669\d\u061f?]{1,4})'
        r'[)\]/\s' + ZW + r':.\u061f?]*',
        re.MULTILINE
    )
    matches = list(pattern.finditer(text))
    if not matches:
        return articles
    seen = set()
    last_num = 0
    for i, m in enumerate(matches):
        raw = normalize_num(m.group(1))
        num = None
        if raw and re.fullmatch(r'\d{1,4}', raw):
            try:
                num = int(raw)
            except (ValueError, TypeError):
                num = None
        if num is None:
            # راس مشوه (؟) - رقم تسلسلي بعد السابق
            num = last_num + 1
        if num < 1 or num > 2000 or num in seen:
            continue
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        body = re.sub(r'\n{3,}', '\n\n', re.sub(r'[ \t]+', ' ', text[start:end].strip()))
        if len(body) < 10:
            continue
        seen.add(num)
        last_num = num
        articles.append({'number': num, 'text': f"\u0627\u0644\u0645\u0627\u062f\u0629 {num}\n\n{body}"})
    articles.sort(key=lambda x: x['number'])
    return articles



def is_text_good(text, articles):
    """يكشف النص العربي المشوّه بفحص الكلمات الشائعة"""
    if not text or len(articles) < 5:
        return False
    arabic = len(re.findall(r'[\u0600-\u06FF]', text))
    total = len(re.sub(r'\s', '', text))
    if total == 0 or (arabic / total) < 0.6:
        return False
    if articles:
        avg = sum(len(a['text']) for a in articles) / len(articles)
        if avg < 50:
            return False
    common_words = [
        'من', 'في', 'على', 'إلى', 'الذي', 'التي', 'هذا', 'هذه', 'ذلك',
        'المادة', 'القانون', 'أو', 'أن', 'لا', 'ما', 'هو', 'هي', 'كل', 'أي',
        'كان', 'يكون', 'بين', 'عند', 'بعد', 'قبل', 'حسب', 'وفق', 'إذا'
    ]
    sample = text[:20000]
    words_found = sum(1 for w in common_words if re.search(r'\b' + w + r'\b', sample))
    if words_found < 6:
        print(f"    كلمات شائعة موجودة: {words_found}/21 → النص مشوّه")
        return False
    # رفض النص المليء بعلامات استفهام Tesseract (أرقام مواد ضائعة)
    qmarks = sample.count('؟')
    if len(sample) > 0 and (qmarks / max(len(sample), 1)) > 0.02:
        print(f"    علامات ؟ كثيرة ({qmarks}) → النص مشوّه")
        return False
    return True


def extract_pymupdf(pdf_bytes):
    try:
        doc = fitz.open(stream=pdf_bytes, filetype="pdf")
        txt = "".join(page.get_text("text") + "\n" for page in doc)
        doc.close()
        return txt
    except Exception as e:
        print(f"    PyMuPDF فشل: {e}")
        return ""


def extract_tesseract(pdf_bytes):
    try:
        from pdf2image import convert_from_bytes, pdfinfo_from_bytes
        import pytesseract
        info = pdfinfo_from_bytes(pdf_bytes)
        num_pages = info.get('Pages', 1)
        print(f"    صفحات: {num_pages}")
        full = ""
        for p in range(1, num_pages + 1):
            try:
                imgs = convert_from_bytes(pdf_bytes, dpi=200, first_page=p, last_page=p, fmt='jpeg', thread_count=1)
                if not imgs:
                    continue
                img = imgs[0].convert('L')
                full += pytesseract.image_to_string(img, lang='ara', config='--psm 6 --oem 1') + "\n"
                del imgs, img
                if p % 20 == 0:
                    print(f"    {p}/{num_pages}")
            except Exception as pe:
                print(f"    خطأ صفحة {p}: {pe}")
                continue
        return fix_arabic_errors(full)
    except Exception as e:
        print(f"    Tesseract فشل: {e}")
        return ""


def main():
    creds_dict = json.loads(os.environ['GDRIVE_SERVICE_ACCOUNT_JSON'])
    creds = service_account.Credentials.from_service_account_info(creds_dict, scopes=SCOPES)
    drive = build('drive', 'v3', credentials=creds)
    folder_id = os.environ['GDRIVE_FOLDER_ID']

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    old_manifest = load_manifest()
    new_manifest = {}

    results = drive.files().list(
        q=f"'{folder_id}' in parents and mimeType='application/pdf' and trashed=false",
        fields='files(id, name, modifiedTime)',
        pageSize=1000
    ).execute()
    files = results.get('files', [])
    print(f"ملفات على Drive: {len(files)}")

    index_laws = []

    for f in files:
        filename = f['name']
        drive_id = f['id']
        modified = f['modifiedTime']

        # هل الملف معالَج مسبقًا ولم يتغير؟
        prev = old_manifest.get(filename)
        if prev and prev.get('drive_id') == drive_id and prev.get('modified_time') == modified:
            law_id = prev.get('law_id')
            json_path = os.path.join(OUTPUT_DIR, f"{law_id}.json")
            if os.path.exists(json_path):
                print(f"⏭  تخطي: {filename}")
                new_manifest[filename] = prev
                try:
                    with open(json_path, 'r', encoding='utf-8') as jf:
                        existing = json.load(jf)
                    index_laws.append({
                        'id': existing['id'],
                        'name': existing['name'],
                        'year': existing['year'],
                        'type': existing['type'],
                        'file': f"{law_id}.json",
                        'articles_count': existing.get('articles_count', 0),
                        'extraction_method': existing.get('extraction_method', 'cached')
                    })
                except Exception:
                    pass
                continue

        # ملف جديد أو معدّل → معالجة
        print(f"\n=== معالجة: {filename}")
        parsed = parse_filename(filename)
        if not parsed:
            print("    تجاهل: اسم الملف لا يتبع الصيغة")
            continue

        request = drive.files().get_media(fileId=drive_id)
        buf = io.BytesIO()
        dl = MediaIoBaseDownload(buf, request)
        done = False
        while not done:
            _, done = dl.next_chunk()
        pdf_bytes = buf.getvalue()

        try:
            year_int = int(parsed['year'])
        except (ValueError, TypeError):
            year_int = 9999

        method = "PyMuPDF"
        articles = []
        if year_int < 2000:
            print(f"    قانون قديم ({year_int}) → Tesseract")
            articles = parse_articles(extract_tesseract(pdf_bytes))
            method = "Tesseract"
        else:
            text = light_clean(extract_pymupdf(pdf_bytes))
            articles = parse_articles(text)
            print(f"    PyMuPDF: {len(articles)} مادة")
            if not is_text_good(text, articles):
                print(f"    نص مشوّه → Tesseract")
                articles = parse_articles(extract_tesseract(pdf_bytes))
                method = "Tesseract"

        print(f"    ✓ {method}: {len(articles)} مادة")
        law_id = make_id(parsed['name'], parsed['year'])
        data = {
            'id': law_id, 'name': parsed['name'], 'year': parsed['year'],
            'type': parsed['type'], 'source_file': filename,
            'extraction_method': method,
            'articles_count': len(articles), 'articles': articles
        }
        with open(os.path.join(OUTPUT_DIR, f"{law_id}.json"), 'w', encoding='utf-8') as jf:
            json.dump(data, jf, ensure_ascii=False, indent=2)

        new_manifest[filename] = {
            'drive_id': drive_id, 'modified_time': modified, 'law_id': law_id
        }
        index_laws.append({
            'id': law_id, 'name': parsed['name'], 'year': parsed['year'],
            'type': parsed['type'], 'file': f"{law_id}.json",
            'articles_count': len(articles), 'extraction_method': method
        })

    save_manifest(new_manifest)

    index_data = {
        'last_updated': datetime.utcnow().isoformat(),
        'total_laws': len(index_laws),
        'laws': index_laws
    }
    with open(os.path.join(OUTPUT_DIR, 'index.json'), 'w', encoding='utf-8') as jf:
        json.dump(index_data, jf, ensure_ascii=False, indent=2)

    print(f"\n=== {len(index_laws)} قانون في الفهرس ===")


if __name__ == '__main__':
    main()
