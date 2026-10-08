package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.RadioButton
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


class SearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var spinnerLaw: Spinner
    private lateinit var btnDoSearch: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var scrollResults: NestedScrollView
    private lateinit var tvSearchResult: TextView
    private lateinit var searchActions: LinearLayout
    private lateinit var rgMode: RadioGroup
    private lateinit var rbFirestore: RadioButton
    private lateinit var rbDrive: RadioButton
    private lateinit var rvDriveResults: RecyclerView
    private lateinit var driveAdapter: DriveLawAdapter
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()


    private data class LawChoice(val id: String?, val title: String) {
        override fun toString(): String = title
    }

    private var cachedLaws: List<LawsRepository.LawMeta> = emptyList()
    private var lawChoices: List<LawChoice> = listOf(LawChoice(null, "كل القوانين"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        etSearch = findViewById(R.id.etSearch)
        spinnerLaw = findViewById(R.id.spinnerLaw)
        btnDoSearch = findViewById(R.id.btnDoSearch)
        progressBar = findViewById(R.id.searchProgressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        scrollResults = findViewById(R.id.scrollResults)
        tvSearchResult = findViewById(R.id.tvSearchResult)
        searchActions = findViewById(R.id.searchActions)
        rgMode = findViewById(R.id.rgSearchMode)
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


        // تفعيل النقر المباشر على روابط الإنترنت داخل نص المادة وضبط ألوانها
        tvSearchResult.movementMethod = LinkMovementMethod.getInstance()
        tvSearchResult.setTextColor(android.graphics.Color.parseColor("#0F2042"))
        tvSearchResult.setLinkTextColor(android.graphics.Color.parseColor("#1565C0"))

        spinnerLaw.adapter = LawSpinnerAdapter(this@SearchActivity, lawChoices)

        lifecycleScope.launch {
            try {
                val laws = LawsRepository.loadLawsList()
                cachedLaws = laws
                val choices = mutableListOf(LawChoice(null, "كل القوانين"))
                choices.addAll(laws.map { LawChoice(it.id, it.name) })
                lawChoices = choices
                spinnerLaw.adapter = LawSpinnerAdapter(this@SearchActivity, lawChoices)
            } catch (_: Exception) {}
        }

        btnDoSearch.setOnClickListener { performSearch() }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.btnCopyArticle).setOnClickListener {
            val text = tvSearchResult.text.toString()
            if (text.isNotEmpty()) {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Article", text))
                Toast.makeText(this, "تم نسخ نص المادة ورابط القانون", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnShareArticle).setOnClickListener {
            val text = tvSearchResult.text.toString()
            if (text.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, "مشاركة المادة"))
            }
        }

                                        findViewById<Button>(R.id.btnOpenPdf).setOnClickListener {
            val text = tvSearchResult.text.toString()
            var rawTarget: String? = null

            val marker = "https://drive.google.com/"
            val sIdx = text.indexOf(marker)
            if (sIdx != -1) {
                val sub = text.substring(sIdx)
                val eIdx = sub.indexOfFirst { it.isWhitespace() }
                rawTarget = if (eIdx != -1) sub.substring(0, eIdx) else sub
            }

            if (rawTarget.isNullOrBlank()) {
                val selectedId = (spinnerLaw.selectedItem as? LawChoice)?.id
                if (!selectedId.isNullOrBlank()) {
                    rawTarget = cachedLaws.find { it.id == selectedId }?.drivePdfUrl
                }
            }

            if (rawTarget.isNullOrBlank()) {
                for (law in cachedLaws) {
                    if (text.contains(law.name, ignoreCase = true) || text.contains(law.id, ignoreCase = true)) {
                        if (!law.drivePdfUrl.isNullOrBlank()) {
                            rawTarget = law.drivePdfUrl
                            break
                        }
                    }
                }
            }

            val folderPreviewUrl = "https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3?usp=sharing"

            val targetUrl = when {
                rawTarget.isNullOrBlank() -> folderPreviewUrl
                rawTarget.contains("/view") -> rawTarget.replace("/view", "/preview")
                rawTarget.startsWith("http") -> rawTarget
                else -> "https://drive.google.com/file/d/" + rawTarget + "/preview"
            }

            try {
                // إجبار الفتح في المتصفح فقط لمنع تطبيق Google Drive من اعتراض الرابط وطلب حسابات
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl.trim())).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    selector = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
                }
                startActivity(browserIntent)
            } catch (_: Exception) {
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl.trim()))
                    startActivity(fallbackIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر فتح المستند: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performSearch() {
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
                    val jsonBody = "{\"query\": \"$query\"}".toRequestBody("application/json".toMediaType())
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
    } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvEmpty.text = "حدث خطأ أثناء البحث: ${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private class LawSpinnerAdapter(
        context: android.content.Context,
        private val items: List<LawChoice>
    ) : ArrayAdapter<LawChoice>(context, android.R.layout.simple_spinner_item, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            view.setTextColor(android.graphics.Color.parseColor("#0F2042"))
            view.textSize = 15f
            view.setTypeface(null, android.graphics.Typeface.BOLD)
            view.gravity = android.view.Gravity.RIGHT or android.view.Gravity.CENTER_VERTICAL
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getDropDownView(position, convertView, parent) as TextView
            view.setTextColor(android.graphics.Color.parseColor("#0F2042"))
            view.setBackgroundColor(android.graphics.Color.WHITE)
            view.textSize = 15f
            view.setPadding(32, 24, 32, 24)
            view.gravity = android.view.Gravity.RIGHT or android.view.Gravity.CENTER_VERTICAL
            return view
        }
    }

}
