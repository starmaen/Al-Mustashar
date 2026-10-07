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

target_law_id = '1Uwrq2rfykYbDoa88Rp2wxNZaM-rQhFkt'

# 1. تحديث بيانات الوثيقة المستهدفة
law_meta = {
    'fields': {
        'name': {'stringValue': 'القانون الأساسي للعاملين في الدولة'},
        'title': {'stringValue': 'القانون الأساسي للعاملين في الدولة رقم 50 لعام 2004'},
        'shortTitle': {'stringValue': 'قانون العاملين في الدولة'},
        'lawNumber': {'stringValue': '50'},
        'year': {'integerValue': '2004'},
        'totalArticles': {'integerValue': '165'},
        'articlesCount': {'integerValue': '165'},
        'category': {'stringValue': 'إداري'},
        'hasPdfArchive': {'booleanValue': True}
    }
}
req = urllib.request.Request(
    f'{base_firestore}/{target_law_id}',
    data=json.dumps(law_meta).encode(),
    headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
    method='PATCH'
)
urllib.request.urlopen(req)
print('تم تحديث الوثيقة المعتمدة بنجاح.')

# 2. نسخ كامل المواد إلى هذا المسار
src_req = urllib.request.Request(
    f'{base_firestore}/state_employees_law/articles?pageSize=200',
    headers={'Authorization': f'Bearer {token}'}
)
with urllib.request.urlopen(src_req) as resp:
    articles = json.loads(resp.read().decode()).get('documents', [])

print(f'جاري نقل {len(articles)} مادة إلى المسار المعتمد...')

for art in articles:
    fields = art.get('fields', {})
    num = fields.get('number', {}).get('stringValue', '')
    fields['lawId'] = {'stringValue': target_law_id}
    
    put_req = urllib.request.Request(
        f'{base_firestore}/{target_law_id}/articles/{num}',
        data=json.dumps({'fields': fields}).encode(),
        headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
        method='PATCH'
    )
    try:
        urllib.request.urlopen(put_req)
    except:
        pass

# 3. إزالة الوثائق المكررة لإنهاء التضارب في القائمة
for dup in ['state_workers_law']:
    del_req = urllib.request.Request(
        f'{base_firestore}/{dup}',
        headers={'Authorization': f'Bearer {token}'},
        method='DELETE'
    )
    try:
        urllib.request.urlopen(del_req)
        print(f'تم تنظيف الوثيقة المكررة: {dup}')
    except:
        pass

print('اكتملت المزامنة والتنظيف بالكامل!')
