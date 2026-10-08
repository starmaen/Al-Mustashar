import re

path = "app/src/main/java/com/maen/almustashar/SearchActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# تحديث دالة searchDriveCloud لفتح البحث المباشر بالكلمة المحددة داخل مجلد القوانين
direct_search_logic = """    private fun searchDriveCloud(query: String) {
        progressBar.visibility = View.GONE
        tvEmpty.visibility = View.VISIBLE
        searchActions.visibility = View.VISIBLE
        rvDriveResults.visibility = View.GONE

        tvEmpty.text = "جاري فتح البحث في أرشيف Drive عن: \\"$query\\""
        
        btnOpenPdf.text = "🔍 ابحث عن '$query' في مجلد القوانين"
        btnOpenPdf.setOnClickListener {
            openDriveFolderSearch(query)
        }

        // فتح البحث تلقائياً في Drive
        openDriveFolderSearch(query)
    }

    private fun openDriveFolderSearch(query: String) {
        try {
            val folderId = "1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"
            val encodedQuery = java.net.URLEncoder.encode("parent:'$folderId' $query", "UTF-8")
            val driveUri = Uri.parse("https://drive.google.com/drive/u/0/search?q=$encodedQuery")
            
            val intent = Intent(Intent.ACTION_VIEW, driveUri)
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackUri = Uri.parse("https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3")
            startActivity(Intent(Intent.ACTION_VIEW, fallbackUri))
        }
    }"""

# استبدال دالة searchDriveCloud القديمة
code = re.sub(
    r'private fun searchDriveCloud\(query: String\)[\s\S]*?tvEmpty\.visibility = View\.VISIBLE[\s\S]*?\}\s*\}\s*\}',
    direct_search_logic.strip(),
    code
)

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ تم تحويل بحث Drive إلى بحث مباشر وسريع بدون مفاتيح أو أخطاء 401.")
