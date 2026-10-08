path = "app/src/main/java/com/maen/almustashar/SearchActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

import re

# استبدال دالة فتح الروابط لتكون عبر المتصفح فقط وتمنع تطبيق درايف من الاعتراض
new_open_methods = """    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(Intent.createChooser(intent, "فتح بواسطة المتصفح"))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun searchDriveDynamic(query: String) {
        progressBar.visibility = View.GONE
        tvEmpty.text = "سيتم فتح أرشيف Drive للبحث عن:\n\"$query\""
        tvEmpty.visibility = View.VISIBLE

        searchActions.visibility = View.VISIBLE
        btnOpenPdf.text = "📂 فتح نتائج البحث في المتصفح"
        
        val folderId = "1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"
        val targetUrl = "https://drive.google.com/drive/folders/$folderId"
        
        btnOpenPdf.setOnClickListener {
            openInBrowser(targetUrl)
        }
        
        openInBrowser(targetUrl)
    }"""

# إزالة الدوال السابقة الخاصة بالبحث في درايف واستبدالها بالمتصفح المباشر
if "private fun searchDriveDynamic" in code:
    code = re.sub(r'private fun searchDriveDynamic[\s\S]*?\n    \}', new_open_methods.strip(), code)
elif "private fun searchDriveCloud" in code:
    code = re.sub(r'private fun searchDriveCloud[\s\S]*?\n    \}', new_open_methods.strip(), code)

# تعديل ضغطة زر فتح PDF في أسفل الشاشة لتعمل بالمتصفح حصراً
code = re.sub(
    r'btnOpenPdf\.setOnClickListener\s*\{[\s\S]*?startActivity\(Intent\(Intent\.ACTION_VIEW,\s*Uri\.parse\(.*?\)\)\)\s*\}',
    'btnOpenPdf.setOnClickListener { openInBrowser(currentPdfUrl ?: "https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3") }',
    code
)

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ تم تعديل فتح روابط درايف لتمر عبر المتصفح وتمنع طلب الحسابات.")
