import re

path = "app/src/main/java/com/maen/almustashar/SearchActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# تحديث دالة searchDriveCloud للتعامل مع رابط السحابة أو الفتح المباشر
drive_search_code = """    private fun searchDriveCloud(query: String) {
        lifecycleScope.launch {
            var errorDetail = ""
            val filesList = withContext(Dispatchers.IO) {
                try {
                    val folderId = "1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"
                    // 1. محاولة استدعاء الدالة السحابية
                    val url = "https://us-central1-al-mustashar-7f6b7.cloudfunctions.net/searchDriveLaws"
                    val jsonBody = "{\\"query\\": \\"$query\\"}".toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(url).post(jsonBody).build()

                    val resp = httpClient.newCall(request).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val root = JsonParser.parseString(body).asJsonObject
                        val arr = root.getAsJsonArray("files") ?: return@withContext emptyList<DriveLawFile>()
                        val list = mutableListOf<DriveLawFile>()
                        for (elem in arr) {
                            val obj = elem.asJsonObject
                            list.add(
                                DriveLawFile(
                                    id = obj.get("id")?.asString.orEmpty(),
                                    name = obj.get("name")?.asString.orEmpty(),
                                    mimeType = obj.get("mimeType")?.asString.orEmpty(),
                                    webViewLink = obj.get("webViewLink")?.asString,
                                    webContentLink = obj.get("webContentLink")?.asString
                                )
                            )
                        }
                        return@withContext list
                    } else {
                        errorDetail = "كود الخادم: ${resp.code}"
                    }
                } catch (e: Exception) {
                    errorDetail = e.localizedMessage ?: "فشل الاتصال"
                }
                emptyList<DriveLawFile>()
            }

            progressBar.visibility = View.GONE
            if (filesList.isNotEmpty()) {
                driveAdapter.submitList(filesList)
                rvDriveResults.visibility = View.VISIBLE
            } else {
                tvEmpty.text = "لم يتم العثور على نتائج في Drive ($errorDetail)\\n\\nيمكنك تصفح مجلد القوانين السحابي مباشرة."
                tvEmpty.visibility = View.VISIBLE
                
                // زر فوري لفتح مجلد القوانين الأصلي في Drive مباشرة
                searchActions.visibility = View.VISIBLE
                btnOpenPdf.text = "📂 فتح مجلد القوانين على Drive"
                btnOpenPdf.setOnClickListener {
                    val driveIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"))
                    startActivity(driveIntent)
                }
            }
        }
    }"""

content = re.sub(
    r'private fun searchDriveCloud\(query: String\)[\s\S]*?tvEmpty\.visibility = View\.VISIBLE\s*\}\s*\}',
    drive_search_code.strip(),
    content
)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("✅ تم تحديث SearchActivity.kt بآلية بديلة تتيح فتح المجلد السحابي وتوضيح كود الخطأ.")
