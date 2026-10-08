path = "app/src/main/java/com/maen/almustashar/SearchActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# تعديل الرابط لمعرف المشروع الفعلي
old_url = 'val url = "https://us-central1-almustashar-law.cloudfunctions.net/searchDriveLaws"'
new_url = 'val url = "https://us-central1-al-mustashar-7f6b7.cloudfunctions.net/searchDriveLaws"'

code = code.replace(old_url, new_url)

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ تم تصحيح عنوان مشروع Firebase السحابي.")
