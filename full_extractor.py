import json, time, re, urllib.request, urllib.parse, io, jwt
from pypdf import PdfReader

# 1. المصادقة
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

# 2. تنزيل الـ PDF
file_id = '1Uwrq2rfykYbDoa88Rp2wxNZaM-rQhFkt'
drive_url = f'https://www.googleapis.com/drive/v3/files/{file_id}?alt=media'
drive_req = urllib.request.Request(drive_url, headers={'Authorization': f'Bearer {token}'})
with urllib.request.urlopen(drive_req) as resp:
    pdf_bytes = resp.read()

reader = PdfReader(io.BytesIO(pdf_bytes))
full_text = '\n'.join([page.extract_text() or '' for page in reader.pages])

# تحويل الأرقام المشرقية إلى غربية
arabic_to_western = str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789')
normalized_raw_text = full_text.translate(arabic_to_western)

# 3. نمط مرن وشامل لالتقاط كافة أشكال ترقيم المواد
# يدعم: المادة 40، المادة /40/، المادة (40) - المادة - 40
pattern = r'(?:المادة|المـادة|مادة)\s*[\/\-\:\(]?\s*(\d+)\s*[\/\-\:\)]?'
splits = re.split(pattern, normalized_raw_text)

articles_dict = {}
if len(splits) > 1:
    for i in range(1, len(splits), 2):
        num = splits[i].strip()
        body = splits[i+1].strip() if i+1 < len(splits) else ''
        if num.isdigit() and int(num) < 300:
            content = f'المادة {num}: ' + body
            articles_dict[num] = content

print(f'تم استخراج وتحديد {len(articles_dict)} مادة مختلفة من ملف القانون!')

# دالة تطبيع النص العربي
def normalize_arabic(text):
    text = re.sub(r'[إأآا]', 'ا', text)
    text = re.sub(r'ة', 'ه', text)
    text = re.sub(r'ى', 'ي', text)
    text = re.sub(r'[^\w\s]', ' ', text)
    return re.sub(r'\s+', ' ', text).strip()

def extract_keywords(norm_text, num):
    words = [w for w in norm_text.split() if len(w) > 2]
    unique_words = list(dict.fromkeys(words))[:35]
    extra = [
        num,
        f'الماده {num}',
        f'المادة {num}',
        f'ماده {num}',
        f'مادة {num}',
        f'الماده {num} قانون العاملين',
        f'المادة {num} قانون العاملين',
        f'الماده {num} قانون العاملين الاساسي',
        f'المادة {num} قانون العاملين الاساسي',
        'العاملين',
        'العامل',
        'قانون العاملين',
        'قانون العاملين الاساسي'
    ]
    return list(dict.fromkeys(unique_words + extra))

# 4. الرفع إلى Firestore
law_id = 'state_employees_law'
base_firestore_url = f'https://firestore.googleapis.com/v1/projects/al-mustashar-7f6b7/databases/(default)/documents/laws/{law_id}/articles'

count = 0
for num, raw_content in sorted(articles_dict.items(), key=lambda x: int(x[0])):
    norm_content = normalize_arabic(raw_content)
    kws = extract_keywords(norm_content, num)

    art_payload = {
        'fields': {
            'lawId': {'stringValue': law_id},
            'lawName': {'stringValue': 'القانون الأساسي للعاملين في الدولة'},
            'number': {'stringValue': num},
            'title': {'stringValue': f'المادة {num} - القانون الأساسي للعاملين في الدولة'},
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
        urllib.request.urlopen(req)
        count += 1
        if count % 20 == 0 or count == len(articles_dict):
            print(f'تم رفع {count} مادة حتى الآن...')
    except Exception as e:
        print(f'خطأ في المادة {num}: {e}')

print('اكتمل استخراج ورفع كامل مواد القانون بنجاح!')
