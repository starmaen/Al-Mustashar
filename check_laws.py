import json, time, base64, requests, rsa
from base64 import b64decode, b64encode

PROJECT_ID = "al-mustashar-7f6b7"

with open("serviceAccount.json", 'r', encoding='utf-8') as f:
    sa = json.load(f)

def pkcs8_to_pkcs1_pem(pem):
    lines = pem.strip().split('\n')
    b64_data = ''.join([l for l in lines if not l.startswith('-----')])
    der = b64decode(b64_data)
    pos = 1
    length = der[pos]
    if length & 0x80: pos += 1 + (length & 0x7F)
    else: pos += 1
    pos += 3
    pos += 1
    alg_len = der[pos]
    if alg_len & 0x80:
        nb = alg_len & 0x7F
        pos += 1
        alg_len = int.from_bytes(der[pos:pos+nb], 'big')
        pos += nb
    else: pos += 1
    pos += alg_len
    pos += 1
    octet_len = der[pos]
    if octet_len & 0x80:
        nb = octet_len & 0x7F
        pos += 1
        octet_len = int.from_bytes(der[pos:pos+nb], 'big')
        pos += nb
    else: pos += 1
    inner = der[pos:pos+octet_len]
    b64 = b64encode(inner).decode()
    out = ['-----BEGIN RSA PRIVATE KEY-----']
    for i in range(0, len(b64), 64): out.append(b64[i:i+64])
    out.append('-----END RSA PRIVATE KEY-----')
    return '\n'.join(out)

pkcs1 = pkcs8_to_pkcs1_pem(sa["private_key"])

now = int(time.time())
h = {"alg": "RS256", "typ": "JWT"}
p = {"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/datastore",
     "aud": "https://oauth2.googleapis.com/token", "iat": now, "exp": now + 3600}
def b64(x): return base64.urlsafe_b64encode(x).rstrip(b'=').decode()
hh = b64(json.dumps(h, separators=(',',':')).encode())
pp = b64(json.dumps(p, separators=(',',':')).encode())
priv = rsa.PrivateKey.load_pkcs1(pkcs1.encode())
sig = rsa.sign(f'{hh}.{pp}'.encode(), priv, 'SHA-256')
jwt = f"{hh}.{pp}.{b64(sig)}"

r = requests.post("https://oauth2.googleapis.com/token",
    data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt})
if r.status_code != 200:
    print(f"❌ Token: {r.text}")
    exit(1)
token = r.json()["access_token"]
headers = {"Authorization": f"Bearer {token}"}

print("=" * 60)
print("📚 القوانين الموجودة في Firestore:")
print("=" * 60)

url = f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents/laws?pageSize=50"
r = requests.get(url, headers=headers)
data = r.json()

docs = data.get("documents", [])
if not docs:
    print("❌ لا يوجد أي قانون!")
else:
    for d in docs:
        name = d.get("name", "").split("/")[-1]
        f = d.get("fields", {})
        lawName = f.get("name", {}).get("stringValue", "بدون اسم")
        cat = f.get("category", {}).get("stringValue", "")

        # عدد المواد
        try:
            art_url = f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents/laws/{name}/articles?pageSize=1&showMissing=true"
            r2 = requests.get(art_url, headers=headers)
            count_url = f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents:runQuery"
            query = {"structuredQuery": {
                "from": [{"collectionId": "articles", "allDescendants": False}],
                "parent": f"projects/{PROJECT_ID}/databases/(default)/documents/laws/{name}",
                "select": {"fields": [{"fieldPath": "__name__"}]}
            }}
            r3 = requests.post(count_url, headers=headers, json=query)
            count = len(r3.json()) - 1 if r3.status_code == 200 else "?"
        except:
            count = "?"

        print(f"✅ {name}")
        print(f"   الاسم: {lawName}")
        if cat: print(f"   التصنيف: {cat}")
        print()
