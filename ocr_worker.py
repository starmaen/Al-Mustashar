import os
import json
import re
import io
import shutil
from datetime import datetime
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload
import fitz

SCOPES = ['https://www.googleapis.com/auth/drive.readonly']
OUTPUT_DIR = 'data/laws'
MIN_TEXT_LENGTH_PER_PAGE = 50


def clean_filename(filename):
    name = filename
    name = re.sub(r'(\.pdf|\.PDF)+$', '', name)
    name = re.sub(r'[_\-]?(نسخة|copie|copy|final|نهائي|الجديد|جديد)\s*', ' ', name, flags=re.IGNORECASE)
    name = re.sub(r'\s+', ' ', name).strip()
    return name


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

    year_match = re.search(r'(?:لعام|عام|سنة|لسنة|سنه|الصادر\s+عام)\s*(\d{4})', full_text)
    if year_match:
        year = year_match.group(1)
        before = full_text[:year_match.start()].strip()
        after = full_text[year_match.end():].strip()
        law_name_parts = (before + ' ' + after).split()
    else:
        for i in range(len(law_name_parts) - 1, -1, -1):
            if re.match(r'^\d{4}$', law_name_parts[i]):
                year = law_name_parts[i]
                law_name_parts = law_name_parts[:i] + law_name_parts[i+1:]
                break

    if not year:
        year = 'غير-محدد'

    law_name = ' '.join(law_name_parts).strip()
    law_name = re.sub(r'\s+', ' ', law_name)
    return {'type': law_type, 'name': law_name, 'year': year}


def make_id(law_name, year):
    clean = re.sub(r'\s+', '-', law_name.strip())
    clean = re.sub(r'[\\/:*?"<>|]', '', clean)
    return f"{clean}-{year}"


def extract_text_pymupdf(pdf_bytes):
    try:
        doc = fitz.open(stream=pdf_bytes, filetype="pdf")
        full_text = ""
        for page in doc:
            full_text += page.get_text("text") + "\n"
        doc.close()
        return full_text
    except Exception as e:
        print(f"  PyMuPDF فشل: {e}")
        return ""


def has_good_text_layer(text, num_pages):
    if not text or not num_pages:
        return False
    avg_per_page = len(text.strip()) / num_pages
    return avg_per_page >= MIN_TEXT_LENGTH_PER_PAGE


def extract_text_ocr(pdf_bytes):
    from pdf2image import convert_from_bytes
    import pytesseract
    images = convert_from_bytes(pdf_bytes, dpi=300)
    full_text = ""
    for img in images:
        page_text = pytesseract.image_to_string(img, lang='ara+eng', config='--psm 6')
        full_text += page_text + "\n"
    return full_text


def normalize_num(num_str):
    if not num_str:
        return None
    return num_str.translate(str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789'))


def parse_articles(text):
    articles = []
    pattern = re.compile(
        r'(?:المادة|المادّة|مادة|مادّة)\s*'
        r'[\(\[/\s]*'
        r'([٠-٩\d]{1,4})'
        r'[\)\]/\s]*',
        re.MULTILINE
    )

    matches = list(pattern.finditer(text))
    if not matches:
        return articles

    seen = set()
    for i, match in enumerate(matches):
        raw_num = normalize_num(match.group(1))
        try:
            article_num = int(raw_num)
        except ValueError:
            continue

        if article_num < 1 or article_num > 2000:
            continue
        if article_num in seen:
            continue

        start = match.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        body = text[start:end].strip()
        body = re.sub(r'\n{3,}', '\n\n', body)
        body = re.sub(r'[ \t]+', ' ', body)

        if len(body) < 5:
            continue

        seen.add(article_num)
        articles.append({
            'number': article_num,
            'text': f"المادة {article_num}\n\n{body}"
        })

    articles.sort(key=lambda x: x['number'])
    return articles


def main():
    creds_dict = json.loads(os.environ['GDRIVE_SERVICE_ACCOUNT_JSON'])
    creds = service_account.Credentials.from_service_account_info(creds_dict, scopes=SCOPES)
    drive = build('drive', 'v3', credentials=creds)
    folder_id = os.environ['GDRIVE_FOLDER_ID']

    if os.path.exists(OUTPUT_DIR):
        shutil.rmtree(OUTPUT_DIR)
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    results = drive.files().list(
        q=f"'{folder_id}' in parents and mimeType='application/pdf' and trashed=false",
        fields='files(id, name, modifiedTime)',
        pageSize=1000
    ).execute()

    files = results.get('files', [])
    print(f"عدد الملفات: {len(files)}")

    index_laws = []

    for file in files:
        filename = file['name']
        print(f"\n=== معالجة: {filename}")

        parsed = parse_filename(filename)
        if not parsed:
            print("  تجاهل: اسم الملف لا يتبع الصيغة")
            continue

        print(f"  النوع: {parsed['type']} | الاسم: {parsed['name']} | السنة: {parsed['year']}")

        request = drive.files().get_media(fileId=file['id'])
        pdf_buffer = io.BytesIO()
        downloader = MediaIoBaseDownload(pdf_buffer, request)
        done = False
        while not done:
            _, done = downloader.next_chunk()
        pdf_bytes = pdf_buffer.getvalue()

        try:
            doc = fitz.open(stream=pdf_bytes, filetype="pdf")
            num_pages = len(doc)
            doc.close()
        except Exception:
            num_pages = 1

        text = extract_text_pymupdf(pdf_bytes)
        method = "PyMuPDF"

        if not has_good_text_layer(text, num_pages):
            print(f"  الطبقة النصية ضعيفة، جاري OCR...")
            text = extract_text_ocr(pdf_bytes)
            method = "Tesseract"

        print(f"  الطريقة: {method} | طول النص: {len(text)} حرف")

        articles = parse_articles(text)
        print(f"  عدد المواد: {len(articles)}")
        if articles:
            print(f"  النطاق: {articles[0]['number']} إلى {articles[-1]['number']}")

        law_id = make_id(parsed['name'], parsed['year'])
        law_data = {
            'id': law_id,
            'name': parsed['name'],
            'year': parsed['year'],
            'type': parsed['type'],
            'source_file': filename,
            'extraction_method': method,
            'articles_count': len(articles),
            'articles': articles
        }

        with open(os.path.join(OUTPUT_DIR, f"{law_id}.json"), 'w', encoding='utf-8') as f:
            json.dump(law_data, f, ensure_ascii=False, indent=2)

        index_laws.append({
            'id': law_id,
            'name': parsed['name'],
            'year': parsed['year'],
            'type': parsed['type'],
            'file': f"{law_id}.json",
            'articles_count': len(articles)
        })

    index_data = {
        'last_updated': datetime.utcnow().isoformat(),
        'total_laws': len(index_laws),
        'laws': index_laws
    }
    with open(os.path.join(OUTPUT_DIR, 'index.json'), 'w', encoding='utf-8') as f:
        json.dump(index_data, f, ensure_ascii=False, indent=2)

    print(f"\n=== تمت معالجة {len(index_laws)} قانون ===")


if __name__ == '__main__':
    main()
