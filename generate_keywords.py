import re
import json
from collections import Counter

# 1. قائمة كلمات التوقف العامة والتشريعية التي يجب استبعادها
ARABIC_STOP_WORDS = {
    # حروف وأدوات
    "في", "من", "على", "إلى", "الى", "عن", "مع", "أو", "او", "ثم", "حتى", "لا", "ما", "لم", "لن", "كل", "جميع",
    "هذا", "هذه", "ذلك", "تلك", "التي", "الذي", "الذين", "اللواتي", "إذا", "اذا", "لو", "إن", "ان", "أن", "كان",
    "يكون", "كانت", "يكونون", "قد", "بين", "غير", "فقط", "كما", "حيث", "لدى", "عند", "بعد", "قبل", "دون",
    
    # ألفاظ تشريعية مكررة لا تميز موضوعاً عن آخر
    "مادة", "المادة", "قانون", "القانون", "أحكام", "احكام", "حكم", "فقرة", "الفقرة", "بند", "البند",
    "مرسوم", "المرسوم", "تشريع", "سنة", "تاريخ", "صادر", "نص", "النص", "تعديل", "تعديلات",
    "يجوز", "يجب", "يمتنع", "يعتبر", "تعتبر", "يكون", "تكون", "وفقا", "وفق", "بموجب", "بناء", "تطبيق"
}

# 2. عبارات ومصطلحات قانونية مركبة ذات دلالة خاصة
LEGAL_PHRASES = [
    "امن الدولة", "سلامة الدولة", "تزوير خاتم", "اوراق مصرفية", "تقليد نقود",
    "تجريد مدني", "حقوق مدنية", "شروع جنائي", "شروع تام", "شروع ناقص",
    "سند امانة", "شيك بدون رصيد", "اساءة ائتمان", "احتيال مالي", "سرقة موصوفة",
    "سند رسمي", "سند عادي", "يمين حاسمة", "يمين متممة", "عبء الاثبات",
    "عدم محكومية", "شهادة حقوق", "نقابة المحامين", "جدول التمرين", "يمين قانونية",
    "قاضي التحقيق", "قاضي الاحالة", "محكمة الجنايات", "محكمة الاستئناف", "محكمة النقض"
]

def normalize_arabic(text: str) -> str:
    """تنظيف وتوحيد الحروف العربية وإزالة التشكيل"""
    text = re.sub(r'[\u064B-\u0652]', '', text)  # إزالة التشكيل والتنوين
    text = text.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
    text = text.replace("ة", "ه").replace("ى", "ي")
    text = re.sub(r'[^\w\s]', ' ', text)  # استبدال علامات الترقيم بمسافات
    text = re.sub(r'\d+', ' ', text)     # إزالة الأرقام المنفصلة
    return re.sub(r'\s+', ' ', text).strip()

def extract_keywords(law_title: str, text: str, max_keywords: int = 8) -> list:
    """استخراج أهم الكلمات الدلالية من عنوان القانون ونص المادة"""
    full_text = f"{law_title} {text}"
    norm_text = normalize_arabic(full_text)
    
    found_keywords = set()

    # أ. فحص المصطلحات القانونية المركبة أولاً
    for phrase in LEGAL_PHRASES:
        if phrase in norm_text:
            found_keywords.add(phrase)

    # ب. تقطيع النص وحساب تردد الكلمات المفتاحية الفردية
    words = norm_text.split()
    filtered_words = [
        w for w in words 
        if len(w) > 2 and w not in ARABIC_STOP_WORDS and not w.startswith("الماد")
    ]
    
    word_counts = Counter(filtered_words)
    
    # استخراج الكلمات الأكثر تكراراً
    for word, _ in word_counts.most_common(max_keywords * 2):
        if len(found_keywords) >= max_keywords:
            break
        # تفادي الكلمات الجزئية المتكررة
        if not any(word in kw for kw in found_keywords):
            found_keywords.add(word)

    return list(found_keywords)

def process_laws_file(input_file: str, output_file: str):
    """قراءة ملف القوانين وإضافة الكلمات المفتاحية تلقائياً وحفظه"""
    with open(input_file, 'r', encoding='utf-8') as f:
        data = json.load(f)

    updated_count = 0
    for item in data:
        title = item.get("law_title", "")
        body = item.get("text", "")
        # استخراج الكلمات المفتاحية وحفظها
        item["keywords"] = extract_keywords(title, body)
        updated_count += 1

    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"✅ تمت معالجة {updated_count} مادة واستخراج الكلمات المفتاحية بنجاح في: {output_file}")

if __name__ == "__main__":
    # تجربة فورية على ملف new_laws_batch.json
    process_laws_file("new_laws_batch.json", "laws_ready_for_upload.json")
