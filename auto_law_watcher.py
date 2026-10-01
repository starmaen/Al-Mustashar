import time
import os
import shutil
import glob
from upload_pipeline import run_pipeline

WATCH_DIR = "inbox_laws"
DONE_DIR = "completed_laws"

print(f"👀 نظام المراقبة التلقائية يعمل الآن ويفحص مجلد '{WATCH_DIR}'...")

while True:
    try:
        # البحث عن أي ملف json جديد داخل inbox_laws
        files = glob.glob(os.path.join(WATCH_DIR, "*.json"))
        for file_path in files:
            file_name = os.path.basename(file_path)
            print(f"\n⚡ تم اكتشاف قانون جديد: {file_name}")
            print("🚀 بدء الاستخراج التلقائي للكلمات المفتاحية والرفع إلى Firebase...")
            
            # تشغيل خط المعالجة والرفع
            run_pipeline(file_path)
            
            # نقل الملف بعد الرفع إلى مجلد المكتملات
            dest_path = os.path.join(DONE_DIR, file_name)
            shutil.move(file_path, dest_path)
            print(f"✅ تم نقل {file_name} إلى مجلد الأرشيف '{DONE_DIR}'.\n")
            
        time.sleep(5)  # فحص المجلد كل 5 ثوانٍ
    except Exception as e:
        print(f"⚠️ خطأ أثناء المراقبة: {e}")
        time.sleep(10)
