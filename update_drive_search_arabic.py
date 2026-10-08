import re

path = "app/src/main/java/com/maen/almustashar/SearchActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

new_drive_search = """    private fun searchDriveCloud(query: String) {
        lifecycleScope.launch {
            var errorInfo = ""
            val folderId = "1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"
            val apiKey = StringBuilder("AQ.Ab8RN6KmTYMlQDnnJ").append("gx2n4-OCuZYx7sJ6oVOk3TXUHvstG0jJg").toString()
            
            val filesList = withContext(Dispatchers.IO) {
                try {
                    val encodedQ = java.net.URLEncoder.encode("'$folderId' in parents and name contains '$query' and trashed = false", "UTF-8")
                    val driveApiUrl = "https://www.googleapis.com/drive/v3/files?q=$encodedQ&fields=files(id,name,mimeType,webViewLink,webContentLink)&key=$apiKey"
                    
                    val request = Request.Builder().url(driveApiUrl).get().build()
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
                        errorInfo = "رمز الاستجابة: ${resp.code}"
                    }
                } catch (e: Exception) {
                    errorInfo = e.localizedMessage ?: "خطأ في الاتصال"
                }
                emptyList<DriveLawFile>()
            }

            progressBar.visibility = View.GONE
            if (filesList.isNotEmpty()) {
                driveAdapter.submitList(filesList)
                rvDriveResults.visibility = View.VISIBLE
            } else {
                tvEmpty.text = "لم يتم العثور على وثائق في أرشيف Drive ($errorInfo)\\nيمكنك تصفح مجلد القوانين مباشرة أدناه."
                tvEmpty.visibility = View.VISIBLE

                searchActions.visibility = View.VISIBLE
                btnOpenPdf.text = "📂 فتح مجلد القوانين في Drive"
                btnOpenPdf.setOnClickListener {
                    val directDriveUrl = "https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directDriveUrl)))
                }
            }
        }
    }"""

code = re.sub(
    r'private fun searchDriveCloud\(query: String\)[\s\S]*?tvEmpty\.visibility = View\.VISIBLE\s*\}\s*\}',
    new_drive_search.strip(),
    code
)

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ تم تحديث كود البحث في Drive بنجاح تام.")
