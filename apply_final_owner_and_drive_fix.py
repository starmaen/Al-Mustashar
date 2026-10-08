import subprocess
import re

# 1. استرجاع أصل MainActivity.kt من Git لضمان نظافة الكود وعدم فقدان أي أزرار أو عناصر سابقة
subprocess.run(["git", "checkout", "HEAD~7", "--", "app/src/main/java/com/maen/almustashar/MainActivity.kt"])

# --- تعديل MainActivity.kt ---
main_path = "app/src/main/java/com/maen/almustashar/MainActivity.kt"
with open(main_path, "r", encoding="utf-8") as f:
    main_code = f.read()

main_imports = """import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.text.InputType
"""
main_code = main_code.replace("import androidx.appcompat.app.AlertDialog", "import androidx.appcompat.app.AlertDialog\n" + main_imports)

new_logo_behavior = """        var logoClicks = 0
        var lastLogoClick = 0L

        binding.ivLogo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastLogoClick > 2000L) {
                logoClicks = 0
            }
            lastLogoClick = now
            logoClicks++

            if (logoClicks >= 7) {
                logoClicks = 0
                val ctx = this@MainActivity
                val layout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 40, 50, 20)
                }
                val etEmail = EditText(ctx).apply {
                    hint = "البريد الإلكتروني للمالك"
                    inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                }
                val etPass = EditText(ctx).apply {
                    hint = "كلمة المرور السرية"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                layout.addView(etEmail)
                layout.addView(etPass)

                AlertDialog.Builder(ctx)
                    .setTitle("🔐 الدخول لحجرة المالك")
                    .setMessage("أدخل بيانات الاعتماد لتوليد التراخيص:")
                    .setView(layout)
                    .setPositiveButton("دخول") { dialog, _ ->
                        val email = etEmail.text.toString().trim()
                        val pass = etPass.text.toString().trim()
                        if (LicenseManager.verifyOwner(email, pass)) {
                            val deviceId = DeviceUtils.getDeviceId(ctx)
                            LicenseManager.grantOwnerDeviceLicense(ctx, deviceId)
                            dialog.dismiss()
                            startActivity(Intent(ctx, OwnerPanelActivity::class.java))
                        } else {
                            Toast.makeText(ctx, "بيانات المالك غير صحيحة", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("إلغاء") { dialog, _ -> dialog.dismiss() }
                    .show()
            } else if (logoClicks in 4..6) {
                val remaining = 7 - logoClicks
                Toast.makeText(this@MainActivity, "تبقى $remaining نقرات للوصول لحجرة المالك", Toast.LENGTH_SHORT).show()
            } else if (logoClicks == 1) {
                drawer.openDrawer(GravityCompat.END)
            }
        }"""

main_code = re.sub(
    r'binding\.ivLogo\.setOnClickListener\s*\{\s*drawer\.openDrawer\(GravityCompat\.END\)\s*\}',
    new_logo_behavior,
    main_code
)

with open(main_path, "w", encoding="utf-8") as f:
    f.write(main_code)


# --- تعديل SearchActivity.kt ---
search_path = "app/src/main/java/com/maen/almustashar/SearchActivity.kt"
with open(search_path, "r", encoding="utf-8") as f:
    search_code = f.read()

drive_search_method = """    private fun searchDriveCloud(query: String) {
        lifecycleScope.launch {
            var statusDetail = ""
            val filesList = withContext(Dispatchers.IO) {
                try {
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
                        statusDetail = "كود: " + resp.code.toString()
                    }
                } catch (e: Exception) {
                    statusDetail = e.localizedMessage ?: "انقطاع بالشبكة"
                }
                emptyList<DriveLawFile>()
            }

            progressBar.visibility = View.GONE
            if (filesList.isNotEmpty()) {
                driveAdapter.submitList(filesList)
                rvDriveResults.visibility = View.VISIBLE
            } else {
                tvEmpty.text = "لم يتم العثور على وثائق مطابقة (" + statusDetail + ")"
                tvEmpty.visibility = View.VISIBLE

                searchActions.visibility = View.VISIBLE
                btnOpenPdf.text = "فتح أرشيف المجلد في Drive"
                btnOpenPdf.setOnClickListener {
                    val folderUrl = "https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(folderUrl)))
                }
            }
        }
    }"""

search_code = re.sub(
    r'private fun searchDriveCloud\(query: String\)[\s\S]*?tvEmpty\.visibility = View\.VISIBLE\s*\}\s*\}',
    drive_search_method.strip(),
    search_code
)

with open(search_path, "w", encoding="utf-8") as f:
    f.write(search_code)

print("✅ تم تعديل وضبط MainActivity و SearchActivity بنجاح تام.")
