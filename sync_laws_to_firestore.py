"""المزامنة التلقائية: data/laws/*.json (منقحة) --> Firestore.

يُشغَّل من GitHub Actions بعد ocr_worker.py، فيرفع كل قانون جديد/معدّل
فقط (يقارن drive_modified_time المخزّن في مستند القانون)، ويتجاهل الباقي.
لا يحذف قوانين اختفت من Drive — يطبع تحذيراً فقط لتفادي ضياع البيانات.

البنية في Firestore (تطابق LawsRepository في التطبيق):
  laws/{lawId}: {name, year, type, articles_count, source_file,
                 drivePdfUrl, drive_modified_time, updated_at}
  laws/{lawId}/articles/{number}: {number, text}

الاعتماد: firebase-admin ( adapted من نفس حساب الخدمة لمشروع Firebase).
المتغيرات: FIREBASE_SERVICE_ACCOUNT_JSON (أو GDRIVE_SERVICE_ACCOUNT_JSON
كبديل — نفس حساب الخدمة)، FIREBASE_PROJECT_ID (اختياري).
"""

import json
import os
import sys
from datetime import datetime, timezone

OUTPUT_DIR = "data/laws"
MANIFEST_PATH = "data/laws/_manifest.json"


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    try:
        import firebase_admin
        from firebase_admin import credentials, firestore
    except ImportError:
        print("firebase-admin غير مثبت. ثبّته: pip install firebase-admin")
        return 1

    sa_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON") or os.environ.get(
        "GDRIVE_SERVICE_ACCOUNT_JSON"
    )
    if not sa_json:
        print("خطأ: لا يوجد FIREBASE_SERVICE_ACCOUNT_JSON ولا GDRIVE_SERVICE_ACCOUNT_JSON")
        return 1

    sa = json.loads(sa_json)
    project_id = os.environ.get("FIREBASE_PROJECT_ID") or sa.get("project_id")
    cred = credentials.Certificate(sa)
    try:
        firebase_admin.get_app()
    except ValueError:
        firebase_admin.initialize_app(cred, {"projectId": project_id})

    db = firestore.client()

    manifest = load_json(MANIFEST_PATH) if os.path.exists(MANIFEST_PATH) else {}
    # drive_id لكل ملف -> modified_time (للكشف عن التغيير)
    manifest_by_law = {}
    for filename, info in manifest.items():
        if isinstance(info, dict) and info.get("law_id"):
            manifest_by_law[info["law_id"]] = info

    law_files = sorted(
        f for f in os.listdir(OUTPUT_DIR)
        if f.endswith(".json") and f not in ("index.json", "_manifest.json", "_report.json")
    )
    print(f"ملفات القوانين: {len(law_files)}")

    synced = 0
    skipped = 0
    for law_file in law_files:
        law_id = law_file[:-5]  # بدون .json
        law = load_json(os.path.join(OUTPUT_DIR, law_file))
        articles = law.get("articles", [])
        minfo = manifest_by_law.get(law_id, {})
        drive_modified = minfo.get("modified_time", "")
        drive_id = minfo.get("drive_id", "")
        drive_url = f"https://drive.google.com/file/d/{drive_id}/view" if drive_id else ""

        law_ref = db.collection("laws").document(law_id)
        existing = law_ref.get()
        if existing.exists:
            ed = existing.to_dict() or {}
            if (ed.get("drive_modified_time") == drive_modified
                    and ed.get("articles_count") == len(articles)
                    and drive_modified):
                print(f"تخطي (بدون تغيير): {law.get('name')}")
                skipped += 1
                continue

        # 1) مستند القانون
        law_ref.set({
            "name": law.get("name", law_id),
            "year": law.get("year", ""),
            "type": law.get("type", ""),
            "articles_count": len(articles),
            "source_file": law.get("source_file", ""),
            "extraction_method": law.get("extraction_method", ""),
            "drivePdfUrl": drive_url,
            "drive_modified_time": drive_modified,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        })

        # 2) المواد: حذف الزائدة + كتابة الكل بدفعات (حد الدفعة 400)
        existing_ids = {d.id for d in law_ref.collection("articles").stream()}
        new_ids = {str(a["number"]) for a in articles}
        stale = existing_ids - new_ids
        batch = db.batch()
        ops = 0

        def commit():
            nonlocal batch, ops
            if ops:
                batch.commit()
                batch = db.batch()
                ops = 0

        for sid in stale:
            batch.delete(law_ref.collection("articles").document(sid))
            ops += 1
            if ops >= 400:
                commit()
        for a in articles:
            num = str(a["number"])
            batch.set(law_ref.collection("articles").document(num), {
                "number": a["number"],
                "text": a.get("text", ""),
            })
            ops += 1
            if ops >= 400:
                commit()
        commit()

        synced += 1
        print(f"رُفع: {law.get('name')} — {len(articles)} مادة (حُذف الزائد: {len(stale)})")

    # تحذير عن قوانين يتيمة في Firestore (حُذفت من Drive) دون حذفها
    firestore_ids = {d.id for d in db.collection("laws").stream()}
    local_ids = {f[:-5] for f in law_files}
    orphans = firestore_ids - local_ids
    for o in orphans:
        print(f"تحذير: {o} موجود في Firestore وغير موجود في Drive — لم يُحذف.")

    print(f"\n=== تم: {synced} رفع، {skipped} تخطي، {len(orphans)} يتيم ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
