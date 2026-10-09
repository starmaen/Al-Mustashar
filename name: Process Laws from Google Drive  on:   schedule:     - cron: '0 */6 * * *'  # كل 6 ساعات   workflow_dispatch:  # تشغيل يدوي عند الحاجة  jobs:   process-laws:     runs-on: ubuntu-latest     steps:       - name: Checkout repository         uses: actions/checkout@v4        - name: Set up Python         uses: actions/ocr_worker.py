import os
import json
import re
import io
from datetime import datetime
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload
from pdf2image import convert_from_bytes
import pytesseract

SCOPES = ['https://www.googleapis.com/auth/drive.readonly']
OUTPUT_DIR = 'data/laws'


def parse_filename(filename):
    name = filename.replace('.pdf', '').strip()
    parts = name.split()
    if len(parts) < 3:
        return None
    law_type = parts[0]
    year = parts[-1]
    law_name = ' '.join(parts[1:-1])
    return {'type': law_type, 'name': law_name, 'year': year}


def make_id(law_name, year):
    clean = re.sub(r'\s+', '-', law_name.strip())
    return f"{clean}-{year}"


def extract_text_from_pdf(pdf_bytes):
    images = convert_from_bytes(pdf_bytes, dpi=300)
    full_text = ""
    for img in images:
        page_text = pytesseract.image_to_string(
            img, lang='ara+eng', config='--psm 6'
        )
        full_text += page_text + "\n"
    return full_text


def parse_articles(text):
    pattern = r'(المادة\s*\(?\s*\d+\s*\)?)'
    parts = re.split(pattern, text)
    articles = []
    for i in range(1, len(parts), 2):
        header = parts[i]
        body = parts[i + 1] if i + 1 < len(parts) else ""
        num_match = re.search(r'\d+', header)
        if num_match:
            articles.append({
                'number': int(num_match.group()),
                'text': (header + ' ' + body).strip()
            })
    return articles


def main():
    creds_dict = json.loads(os.environ['GDRIVE_SERVICE_ACCOUNT_JSON'])
    creds = service_account.Credentials.from_service_account_info(
        creds_dict, scopes=SCOPES
    )
    drive = build('drive', 'v3', credentials=creds)
    folder_id = os.environ['GDRIVE_FOLDER_ID']

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
        print(f"معالجة: {filename}")

        parsed = parse_filename(filename)
        if not parsed:
            print("  تجاهل: اسم الملف لا يتبع الصيغة")
            continue

        request = drive.files().get_media(fileId=file['id'])
        pdf_buffer = io.BytesIO()
        downloader = MediaIoBaseDownload(pdf_buffer, request)
        done = False
        while not done:
            _, done = downloader.next_chunk()

        text = extract_text_from_pdf(pdf_buffer.getvalue())
        articles = parse_articles(text)
        law_id = make_id(parsed['name'], parsed['year'])

        law_data = {
            'id': law_id,
            'name': parsed['name'],
            'year': parsed['year'],
            'type': parsed['type'],
            'source_file': filename,
            'articles_count': len(articles),
            'articles': articles
        }

        output_path = os.path.join(OUTPUT_DIR, f"{law_id}.json")
        with open(output_path, 'w', encoding='utf-8') as f:
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

    print(f"تمت معالجة {len(index_laws)} قانون")


if __name__ == '__main__':
    main()
