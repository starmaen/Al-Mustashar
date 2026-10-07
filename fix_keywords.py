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

law_id = 'state_employees_law'
base_url = f'https://firestore.googleapis.com/v1/projects/al-mustashar-7f6b7/databases/(default)/documents/laws/{law_id}/articles'

# قراءة كل مواد القانون وتحديث كلماتها المفتاحية
req = urllib.request.Request(f'{base_url}?pageSize=200', headers={'Authorization': f'Bearer {token}'})
with urllib.request.urlopen(req) as resp:
    docs = json.loads(resp.read().decode()).get('documents', [])

print(f'جاري تحديث كلمات البحث لـ {len(docs)} مادة...')

for doc in docs:
    doc_path = doc['name']
    fields = doc.get('fields', {})
    num = fields.get('number', {}).get('stringValue', '')
    
    current_kws = [v['stringValue'] for v in fields.get('keywords', {}).get('arrayValue', {}).get('values', [])]
    
    # إضافة تراكيب البحث الدقيقة
    extra_kws = [
        num,
        f'الماده {num}',
        f'المادة {num}',
        f'ماده {num}',
        f'مادة {num}',
        f'الماده {num} قانون العاملين',
        f'المادة {num} قانون العاملين',
        f'الماده {num} العاملين',
        f'قانون العاملين',
        'العاملين'
    ]
    
    all_kws = list(dict.fromkeys(current_kws + extra_kws))
    
    update_url = f'https://firestore.googleapis.com/v1/{doc_path}?updateMask.fieldPaths=keywords'
    patch_data = {
        'fields': {
            'keywords': {'arrayValue': {'values': [{'stringValue': k} for k in all_kws]}}
        }
    }
    
    patch_req = urllib.request.Request(
        update_url,
        data=json.dumps(patch_data).encode(),
        headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
        method='PATCH'
    )
    try:
        urllib.request.urlopen(patch_req)
    except Exception as e:
        pass

print('تم تحديث كلمات البحث لجميع المواد بنجاح!')
