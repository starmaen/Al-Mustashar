import re

path = "app/src/main/java/com/maen/almustashar/SearchActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. إضافة الاستيرادات اللازمة
imports_to_add = """import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
"""

if "DriveLawAdapter" not in content:
    content = content.replace("import kotlinx.coroutines.launch", "import kotlinx.coroutines.launch\n" + imports_to_add)

# 2. إضافة المتغيرات الجديدة داخل كلاس SearchActivity
var_declarations = """    private lateinit var rgMode: RadioGroup
    private lateinit var rbFirestore: RadioButton
    private lateinit var rbDrive: RadioButton
    private lateinit var rvDriveResults: RecyclerView
    private lateinit var driveAdapter: DriveLawAdapter
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
"""

content = re.sub(
    r'(private lateinit var searchActions: LinearLayout)',
    r'\1\n' + var_declarations,
    content
)

# 3. ربط العناصر داخل onCreate وضبط التبديل
init_views = """        rgMode = findViewById(R.id.rgSearchMode)
        rbFirestore = findViewById(R.id.rbModeFirestore)
        rbDrive = findViewById(R.id.rbModeDrive)
        rvDriveResults = findViewById(R.id.rvDriveResults)
        
        driveAdapter = DriveLawAdapter()
        rvDriveResults.layoutManager = LinearLayoutManager(this)
        rvDriveResults.adapter = driveAdapter

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbModeDrive) {
                spinnerLaw.visibility = View.GONE
                scrollResults.visibility = View.GONE
                searchActions.visibility = View.GONE
                etSearch.hint = "ابحث بالنص الكامل داخل وثائق ومراجع Drive..."
            } else {
                spinnerLaw.visibility = View.VISIBLE
                rvDriveResults.visibility = View.GONE
                etSearch.hint = "مثال: المادة 117 أصول جزائية، أو 221 مدنية..."
            }
        }
"""

content = re.sub(
    r'(searchActions = findViewById\(R\.id\.searchActions\))',
    r'\1\n' + init_views,
    content
)

# 4. تحديث دالة performSearch لتدعم المسارين مع المعالجة الاحتياطية
new_search_logic = """    private fun performSearch() {
        val query = etSearch.text?.toString()?.trim() ?: ""
        if (query.isEmpty()) {
            Toast.makeText(this, "يرجى إدخال كلمة البحث أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        scrollResults.visibility = View.GONE
        searchActions.visibility = View.GONE
        rvDriveResults.visibility = View.GONE

        if (rbDrive.isChecked) {
            searchDriveCloud(query)
        } else {
            searchFirestoreLaws(query)
        }
    }

    private fun searchFirestoreLaws(query: String) {
        lifecycleScope.launch {
            try {
                val selectedLawId = (spinnerLaw.selectedItem as? LawChoice)?.id
                val result = LawsRepository.searchRelevantLaws(query, selectedLawId)
                progressBar.visibility = View.GONE

                if (result.isNotBlank() && !result.startsWith("⚠️")) {
                    tvSearchResult.text = result
                    Linkify.addLinks(tvSearchResult, Linkify.WEB_URLS)
                    scrollResults.visibility = View.VISIBLE
                    searchActions.visibility = View.VISIBLE
                } else {
                    tvEmpty.text = result
                    tvEmpty.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvEmpty.text = "تعذر إتمام البحث المحلي/المهيكل: ${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun searchDriveCloud(query: String) {
        lifecycleScope.launch {
            val filesList = withContext(Dispatchers.IO) {
                try {
                    // رابط الدالة السحابية searchDriveLaws
                    val url = "https://us-central1-almustashar-law.cloudfunctions.net/searchDriveLaws"
                    val jsonBody = "{\\"query\\": \\"$query\\"}".toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(url).post(jsonBody).build()

                    httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext emptyList<DriveLawFile>()
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
                        list
                    }
                } catch (_: Exception) {
                    emptyList<DriveLawFile>()
                }
            }

            progressBar.visibility = View.GONE
            if (filesList.isNotEmpty()) {
                driveAdapter.submitList(filesList)
                rvDriveResults.visibility = View.VISIBLE
            } else {
                tvEmpty.text = "لم يتم العثور على وثائق مطابقة في Drive أو تعذر الوصول للسحابة حالياً."
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }
"""

content = re.sub(
    r'private fun performSearch\(\)[\s\S]*?tvEmpty\.visibility = View\.VISIBLE\s*\}\s*\}',
    new_search_logic.strip(),
    content
)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("✅ تم تحديث SearchActivity.kt بنجاح ودمج المسارين بسلاسة وأمان.")
