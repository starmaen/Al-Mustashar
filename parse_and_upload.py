import json, time, re, urllib.request, urllib.parse, io, jwt
from pypdf import PdfReader

# 1. إعداد المصادقة عبر Service Account
with open('./serviceAccount.json', 'r') as f:
    key_data = json.load(f)

payload = {
    'iss': key_data['client_email'],
    'sub': key_data['client_email'],
    'aud': 'https://oauth2.googleapis.com/token',
    'iat': int(time.time()),
    'exp': int(time.time()) + 3600,
    'scope': 'https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/drive.readonly'
}
signed_jwt = jwt.encode(payload, key_data['private_key'], algorithm='RS256')
token_req = urllib.request.Request(
    'https://oauth2.googleapis.com/token',
    data=urllib.parse.urlencode({'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion': signed_jwt}).encode(),
    headers={'Content-Type': 'application/x-www-form-urlencoded'}
)
with urllib.request.urlopen(token_req) as resp:
    token = json.loads(resp.read().decode())['access_token']

# 2. تنزيل ملف الـ PDF من Google Drive
file_id = '1Uwrq2rfykYbDoa88Rp2wxNZaM-rQhFkt'
drive_url = f'https://www.googleapis.com/drive/v3/files/{file_id}?alt=media'
drive_req = urllib.request.Request(drive_url, headers={'Authorization': f'Bearer {token}'})

print('جاري تنزيل ملف الـ PDF من Google Drive...')
with urllib.request.urlopen(drive_req) as resp:
    pdf_bytes = resp.read()

# 3. قراءة النص من الـ PDF
reader = PdfReader(io.BytesIO(pdf_bytes))
full_text = ''
for page in reader.pages:
    text = page.extract_text()
    if text:
        full_text += text + '\n'

print(f'تم استخراج {len(full_text)} حرفاً من ملف الـ PDF.')

# توحيد النص العربي
def normalize_arabic(text):
    text = re.sub(r'[إأآا]', 'ا', text)
    text = re.sub(r'ة', 'ه', text)
    text = re.sub(r'ى', 'ي', text)
    text = re.sub(r'[^\w\s]', ' ', text)
    return re.sub(r'\s+', ' ', text).strip()

def extract_keywords(norm_text):
    words = [w for w in norm_text.split() if len(w) > 2]
    return list(dict.fromkeys(words))[:40]

# 4. تقسيم النص إلى مواد
pattern = r'(?:المادة|المـادة)\s*[-:]?\s*(\d+)'
parts = re.split(pattern, full_text)

articles = []
if len(parts) > 1:
    for i in range(1, len(parts), 2):
        num = parts[i].strip()
        body = parts[i+1].strip() if i+1 < len(parts) else ''
        content = f'المادة {num}: ' + body
        articles.append((num, content))

print(f'تم التعرف على {len(articles)} مادة قانونية.')

# 5. رفع المواد إلى Firestore
law_id = 'state_employees_law'
base_firestore_url = f'https://firestore.googleapis.com/v1/projects/al-mustashar-7f6b7/databases/(default)/documents/laws/{law_id}/articles'

for num, raw_content in articles:
    norm_content = normalize_arabic(raw_content)
    kws = extract_keywords(norm_content)
    kws.append(num)
    kws.extend(['العاملين', 'العامل', 'الدوله', 'موظف'])
    kws = list(dict.fromkeys(kws))

    art_payload = {
        'fields': {
            'lawId': {'stringValue': law_id},
            'lawName': {'stringValue': 'القانون الأساسي للعاملين في الدولة'},
            'number': {'stringValue': num},
            'title': {'stringValue': f'المادة {num} - القانون الأساسي للعاملين'},
            'content': {'stringValue': raw_content[:1500]},
            'normalizedContent': {'stringValue': norm_content[:1500]},
            'driveFileId': {'stringValue': file_id},
            'hasPdfArchive': {'booleanValue': True},
            'keywords': {'arrayValue': {'values': [{'stringValue': w} for w in kws]}}
        }
    }

    doc_url = f'{base_firestore_url}/{num}'
    req = urllib.request.Request(
        doc_url,
        data=json.dumps(art_payload).encode(),
        headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
        method='PATCH'
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(f'تم رفع المادة {num} بنجاح.')
    except Exception as e:
        print(f'خطأ أثناء رفع المادة {num}: {e}')

print('اكتملت عملية المعالجة والرفع بالكامل!')
