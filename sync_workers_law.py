import json, time, urllib.request, urllib.parse, jwt

with open('./serviceAccount.json', 'r') as f:
    key_data = json.load(f)

payload = {
    'iss': key_data['client_email'],
    'sub': key_data['client_email'],
    'aud': 'https://oauth2.googleapis.com/token',
    'iat': int(time.time()),
    'exp': int(time.time()) + 3600,
    'scope': 'https://www.googleapis.com/auth/datastore'
}
signed_jwt = jwt.encode(payload, key_data['private_key'], algorithm='RS256')
token_req = urllib.request.Request(
    'https://oauth2.googleapis.com/token',
    data=urllib.parse.urlencode({'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion': signed_jwt}).encode(),
    headers={'Content-Type': 'application/x-www-form-urlencoded'}
)
with urllib.request.urlopen(token_req) as resp:
    token = json.loads(resp.read().decode())['access_token']

base_firestore = 'https://firestore.googleapis.com/v1/projects/al-mustashar-7f6b7/databases/(default)/documents/laws'

# 1. تحديث وثيقة القانون القديم ببيانات متكاملة وعدد المواد الصحيح
workers_law_patch = {
    'fields': {
        'name': {'stringValue': 'القانون الأساسي للعاملين في الدولة'},
        'title': {'stringValue': 'القانون الأساسي للعاملين في الدولة رقم 50 لعام 2004'},
        'shortTitle': {'stringValue': 'قانون العاملين في الدولة'},
        'lawNumber': {'stringValue': '50'},
        'year': {'integerValue': '2004'},
        'category': {'stringValue': 'إداري'},
        'totalArticles': {'integerValue': '165'},
        'articlesCount': {'integerValue': '165'},
        'hasPdfArchive': {'booleanValue': True}
    }
}
patch_req = urllib.request.Request(
    f'{base_firestore}/state_workers_law',
    data=json.dumps(workers_law_patch).encode(),
    headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
    method='PATCH'
)
urllib.request.urlopen(patch_req)
print('تم تحديث بيانات وثيقة القانون ورفع عدد المواد إلى 165.')

# 2. جلب جميع المواد ونسخها إلى مسار القانون المتصل بالتطبيق
articles_req = urllib.request.Request(
    f'{base_firestore}/state_employees_law/articles?pageSize=200',
    headers={'Authorization': f'Bearer {token}'}
)
with urllib.request.urlopen(articles_req) as resp:
    articles_data = json.loads(resp.read().decode()).get('documents', [])

print(f'جاري مزامنة {len(articles_data)} مادة مع المعرف المرتبط بالتطبيق...')

count = 0
for doc in articles_data:
    fields = doc.get('fields', {})
    num = fields.get('number', {}).get('stringValue', '')
    
    # تحديث معرف القانون داخل المادة ليطابق المسار الجديد
    fields['lawId'] = {'stringValue': 'state_workers_law'}
    
    dest_url = f'{base_firestore}/state_workers_law/articles/{num}'
    put_req = urllib.request.Request(
        dest_url,
        data=json.dumps({'fields': fields}).encode(),
        headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
        method='PATCH'
    )
    try:
        urllib.request.urlopen(put_req)
        count += 1
    except Exception as e:
        pass

print(f'تمت المزامنة بنجاح لنحو {count} مادة!')
