import json
import firebase_admin
from firebase_admin import credentials, firestore

try:
    cred = credentials.Certificate('serviceAccountKey.json')
    firebase_admin.initialize_app(cred)
except Exception:
    pass

db = firestore.client()

law_id = "state_workers_law"
law_name = "القانون الأساسي للعاملين في الدولة"
drive_url = "https://drive.google.com/file/d/1Uwrq2rfykYbDoa88Rp2wxNZaM-rQhFkt/view?usp=drivesdk"

# 1. تسجيل القانون الأساسي ورابط ملف Drive
law_ref = db.collection("laws").document(law_id)
law_ref.set({
    "name": law_name,
    "title": law_name,
    "shortTitle": law_name,
    "category": "قوانين إدارية ووظيفية",
    "drivePdfUrl": drive_url,
    "source": "Google Drive"
}, merge=True)

# 2. تسجيل عينات المواد الأولى ومنها المادة 1 و 5 و 10 للتحقق الفوري
sample_articles = {
    "1": "يقصد بالتعابير الآتية في معرض تطبيق أحكام هذا القانون المعاني المبينة إلى جانب كل منها:\nالعامل: كل من يعين في إحدى الجهات العامة في وظيفة ملازمة لها.\nالجهة العامة: الوزارات والإدارات والهيئات العامة والمؤسسات والشركات والمنشآت العامة والبلديات والوحدات الإدارية المحلية.",
    "2": "تسري أحكام هذا القانون على العاملين في الوزارات والإدارات والمؤسسات والشركات العامة والمنشآت التابعة لها والبلديات وسائر أجهزة الدولة.",
    "5": "يشترط فيمن يعين في إحدى وظائف الجهات العامة أن يكون:\n1- متمتعاً بالجنسية العربية السورية منذ خمس سنوات على الأقل.\n2- قد أتم الثامنة عشرة من عمره.\n3- خالياً من الأمراض والعاهات التي تمنعه من القيام بالوظيفة.\n4- غير محكوم بجناية أو جنحة شائنة.\n5- غير معزول أو مطرود من إحدى وظائف الجهات العامة.",
    "10": "تحدد بمرسوم بناء على اقتراح الوزير المختص الشروط الخاصة للتعيين في بعض الوظائف ذات الطبيعة الفنية أو التخصصية بما يتناسب مع طبيعة مهامها."
}

batch = db.batch()
for num, text in sample_articles.items():
    art_ref = law_ref.collection("articles").document(num)
    batch.set(art_ref, {
        "number": num,
        "text": text,
        "content": text,
        "currentText": text,
        "keywords": ["العاملين", "وظيفة", "تعيين", "شروط التعيين", "الجهات العامة"],
        "drivePdfUrl": drive_url
    }, merge=True)

batch.commit()
print("✅ تم بنجاح حقن قانون العاملين ومواده وربطه بـ Google Drive في Firebase!")
