import re
import json
import os
import firebase_admin
from firebase_admin import credentials, firestore
from collections import Counter

# 1. قائمة كلمات التوقف العامة والتشريعية
ARABIC_STOP_WORDS = {
    "في", "من", "على", "إلى", "الى", "عن", "مع", "أو", "او", "ثم", "حتى", "لا", "ما", "لم", "لن", "كل", "جميع",
    "هذا", "هذه", "ذلك", "تلك", "التي", "الذي", "الذين", "اللواتي", "إذا", "اذا", "لو", "إن", "ان", "أن", "كان",
    "يكون", "كانت", "يكونون", "قد", "بين", "غير", "فقط", "كما", "حيث", "لدى", "عند", "بعد", "قبل", "دون",
    "مادة", "المادة", "قانون", "القانون", "أحكام", "احكام", "حكم", "فقرة", "الفقرة", "بند", "البند",
    "مرسوم", "المرسوم", "تشريع", "سنة", "تاريخ", "صادر", "نص", "النص", "تعديل", "تعديلات",
    "يجوز", "يجب", "يمتنع", "يعتبر", "تعتبر", "يكون", "تكون", "وفقا", "وفق", "بموجب", "بناء", "تطبيق"
}

# 2. المصطلحات والعبارات القانونية المركبة
LEGAL_PHRASES = [
    "امن الدولة", "سلامة الدولة", "تزوير خاتم", "اوراق مصرفية", "تقليد نقود",
    "تجريد مدني", "حقوق مدنية", "شروع جنائي", "شروع تام", "شروع ناقص",
    "سند امانة", "شيك بدون رصيد", "اساءة ائتمان", "احتيال مالي", "سرقة موصوفة",
    "سند رسمي", "سند عادي", "يمين حاسمة", "يمين متممة", "عبء الاثبات",
    "عدم محكومية", "شهادة حقوق", "نقابة المحامين", "جدول التمرين", "يمين قانونية",
    "قاضي التحقيق", "قاضي الاحالة", "محكمة الجنايات", "محكمة الاستئناف", "محكمة النقض"
]

def normalize_arabic(text: str) -> str:
    text = re.sub(r'[\u064B-\u0652]', '', text)
    text = text.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
    text = text.replace("ة", "ه").replace("ى", "ي")
    text = re.sub(r'[^\w\s]', ' ', text)
    text = re.sub(r'\d+', ' ', text)
    return re.sub(r'\s+', ' ', text).strip()

def extract_keywords(law_title: str, text: str, max_keywords: int = 8) -> list:
    full_text = f"{law_title} {text}"
    norm_text = normalize_arabic(full_text)
    found_keywords = set()

    for phrase in LEGAL_PHRASES:
        if phrase in norm_text:
            found_keywords.add(phrase)

    words = norm_text.split()
    filtered_words = [
        w for w in words 
        if len(w) > 2 and w not in ARABIC_STOP_WORDS and not w.startswith("الماد")
    ]
    
    word_counts = Counter(filtered_words)
    for word, _ in word_counts.most_common(max_keywords * 2):
        if len(found_keywords) >= max_keywords:
            break
        if not any(word in kw for kw in found_keywords):
            found_keywords.add(word)

    return list(found_keywords)

def init_firebase():
    key_path = "serviceAccountKey.json"
    if not os.path.exists(key_path):
        raise FileNotFoundError(f"ملف الصلاحيات '{key_path}' غير موجود في المجلد الحالي!")
    
    if not firebase_admin._apps:
        cred = credentials.Certificate(key_path)
        firebase_admin.initialize_app(cred)
    return firestore.client()

def run_pipeline(input_file: str):
    if not os.path.exists(input_file):
        print(f"❌ خطأ: ملف الإدخال '{input_file}' غير موجود.")
        return

    print(f"📖 جاري قراءة البيانات من: {input_file} ...")
    with open(input_file, 'r', encoding='utf-8') as f:
        laws_data = json.load(f)

    total_items = len(laws_data)
    print(f"🔍 تم العثور على {total_items} مادة. جاري استخراج الكلمات المفتاحية...")

    db = init_firebase()
    collection_ref = db.collection("laws")
    batch = db.batch()
    batch_size = 0
    total_uploaded = 0

    for idx, item in enumerate(laws_data, start=1):
        law_title = item.get("law_title", "").strip()
        art_num = str(item.get("article_number", "")).strip()
        body_text = item.get("text", "").strip()

        # استخراج الكلمات المفتاحية تلقائياً إذا لم تكن موجودة
        keywords = item.get("keywords")
        if not keywords:
            keywords = extract_keywords(law_title, body_text)

        # توليد معرّف ثابت ونظيف للمستند
        clean_prefix = normalize_arabic(law_title.split()[0])
        doc_id = f"{clean_prefix}_{art_num}_{idx}"

        doc_ref = collection_ref.document(doc_id)
        batch.set(doc_ref, {
            "law_title": law_title,
            "article_number": art_num,
            "text": body_text,
            "keywords": keywords
        })
        
        batch_size += 1
        total_uploaded += 1

        # الحد الأقصى لكل Batch في Firestore هو 500 عملية
        if batch_size == 450:
            batch.commit()
            print(f"🚀 تم رفع دفعة ({total_uploaded}/{total_items}) مادة...")
            batch = db.batch()
            batch_size = 0

    if batch_size > 0:
        batch.commit()

    print(f"\n🎉 اكتملت العملية بنجاح! تم تجهيز ورفع {total_uploaded} مادة قانونية إلى Firebase.")

if __name__ == "__main__":
    import sys
    target_file = sys.argv[1] if len(sys.argv) > 1 else "new_laws_batch.json"
    run_pipeline(target_file)
