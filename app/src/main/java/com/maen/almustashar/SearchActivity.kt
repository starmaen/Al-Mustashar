package com.maen.almustashar

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class SearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var spinnerLaw: Spinner
    private lateinit var btnDoSearch: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var scrollResults: NestedScrollView
    private lateinit var tvSearchResult: TextView
    private lateinit var searchActions: LinearLayout
    private lateinit var btnCopyArticle: Button
    private lateinit var btnShareArticle: Button
    private lateinit var btnOpenPdf: Button

    private lateinit var rgMode: RadioGroup
    private lateinit var rbFirestore: RadioButton
    private lateinit var rbDrive: RadioButton
    private lateinit var rvDriveResults: RecyclerView
    private lateinit var driveAdapter: DriveLawAdapter

    private data class LawChoice(val id: String?, val title: String) {
        override fun toString(): String = title
    }

    private var cachedLaws: List<LawsRepository.LawMeta> = emptyList()
    private var lawChoices: List<LawChoice> = listOf(LawChoice(null, "كل القوانين"))
    private var currentPdfUrl: String? = null
    private val defaultDriveFolder = "https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"

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
        btnCopyArticle = findViewById(R.id.btnCopyArticle)
        btnShareArticle = findViewById(R.id.btnShareArticle)
        btnOpenPdf = findViewById(R.id.btnOpenPdf)

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
                tvEmpty.visibility = View.GONE
                rvDriveResults.visibility = View.GONE
                etSearch.hint = "ابحث عن ملف أو قانون في مراجع Drive..."
            } else {
                spinnerLaw.visibility = View.VISIBLE
                rvDriveResults.visibility = View.GONE
                tvEmpty.visibility = View.GONE
                scrollResults.visibility = View.GONE
                searchActions.visibility = View.GONE
                etSearch.hint = "ابحث برقم المادة أو اسم القانون أو الموضوع..."
            }
        }

        setupLawsSpinner()

        btnDoSearch.setOnClickListener {
            performSearch()
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        btnCopyArticle.setOnClickListener {
            val text = tvSearchResult.text.toString()
            if (text.isNotEmpty()) {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("LawArticle", text))
                Toast.makeText(this, "تم نسخ نص المادة", Toast.LENGTH_SHORT).show()
            }
        }

        btnShareArticle.setOnClickListener {
            val text = tvSearchResult.text.toString()
            if (text.isNotEmpty()) {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(share, "مشاركة المادة القانونية عبر:"))
            }
        }

        btnOpenPdf.setOnClickListener {
            val target = currentPdfUrl ?: defaultDriveFolder
            openDirectInBrowserOnly(target)
        }
    }

    private fun setupLawsSpinner() {
        lifecycleScope.launch {
            try {
                cachedLaws = LawsRepository.loadLawsList()
                val list = mutableListOf<LawChoice>()
                list.add(LawChoice(null, "كل القوانين"))
                for (law in cachedLaws) {
                    list.add(LawChoice(law.id, law.name))
                }
                lawChoices = list
                val adapter = ArrayAdapter(this@SearchActivity, android.R.layout.simple_spinner_dropdown_item, lawChoices)
                spinnerLaw.adapter = adapter
            } catch (_: Exception) {
                val fallbackList = listOf(LawChoice(null, "كل القوانين"))
                val adapter = ArrayAdapter(this@SearchActivity, android.R.layout.simple_spinner_dropdown_item, fallbackList)
                spinnerLaw.adapter = adapter
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
            searchDriveFiles(query)
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

                    val driveUrlRegex = Regex("https://drive\\.google\\.com/[^\\s]+")
                    val match = driveUrlRegex.find(result)
                    currentPdfUrl = match?.value ?: defaultDriveFolder
                    btnOpenPdf.text = "فتح ملف الـ PDF الأصلي"
                } else {
                    tvEmpty.text = result
                    tvEmpty.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvEmpty.text = "تعذر إتمام البحث: ${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    // كاش خاص بمسار Drive فقط
        private var cachedLawsIndex: JSONObject? = null
    private val cachedLawsMap = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    private fun searchDriveFiles(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, "يرجى إدخال نص البحث", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        scrollResults.visibility = View.GONE
        rvDriveResults.visibility = View.GONE
        searchActions.visibility = View.GONE

        lifecycleScope.launch {
            val resultsText = withContext(Dispatchers.IO) {
                try {
                    val baseUrl = "https://raw.githubusercontent.com/starmaen/Al-Mustashar/main/data/laws/"

                    fun fetchJson(endpoint: String): JSONObject? {
                        return try {
                            val url = URL(baseUrl + endpoint)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 7000
                            conn.readTimeout = 7000
                            conn.useCaches = false
                            if (conn.responseCode == 200) {
                                val t = conn.inputStream.bufferedReader().readText()
                                JSONObject(t)
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (cachedLawsIndex == null) {
                        cachedLawsIndex = fetchJson("index.json")
                    }
                    val index = cachedLawsIndex ?: return@withContext "تعذر الاتصال بقاعدة بيانات القوانين، تحقق من الاتصال بالإنترنت."

                    val lawsArray = index.optJSONArray("laws") ?: JSONArray()
                    val sb = StringBuilder()

                    // الحالة 4: كتابة كلمة "قانون" أو "القوانين"
                    if (trimmed == "قانون" || trimmed == "القوانين") {
                        sb.append("📚 قائمة القوانين المتاحة:

")
                        for (i in 0 until lawsArray.length()) {
                            val l = lawsArray.getJSONObject(i)
                            val name = l.optString("name")
                            val year = l.optString("year")
                            val type = l.optString("type")
                            val count = l.optInt("articles_count")
                            sb.append("• ").append(name)
                            if (year.isNotEmpty()) sb.append(" (").append(year).append(")")
                            sb.append("
  النوع: ").append(type).append(" | عدد المواد: ").append(count)
                            sb.append("

")
                        }
                        return@withContext sb.toString().trim()
                    }

                    val numRegex = Regex("^(?:المادة\s*)?(\d+)$")
                    val numMatch = numRegex.find(trimmed)
                    val targetNum = numMatch?.groupValues?.get(1)?.toIntOrNull()

                    var foundMatches = 0

                    for (i in 0 until lawsArray.length()) {
                        val entry = lawsArray.getJSONObject(i)
                        val lawId = entry.optString("id")
                        val fileName = entry.optString("file")
                        val lawName = entry.optString("name")

                        var lawJson = cachedLawsMap[lawId]
                        if (lawJson == null) {
                            lawJson = fetchJson(fileName)
                            if (lawJson != null) {
                                cachedLawsMap[lawId] = lawJson
                            }
                        }
                        if (lawJson == null) continue

                        val articles = lawJson.optJSONArray("articles") ?: JSONArray()
                        val isLawNameMatch = lawName.contains(trimmed) && targetNum == null && trimmed.length > 3

                        for (j in 0 until articles.length()) {
                            val art = articles.getJSONObject(j)
                            val artNum = art.optInt("number")
                            val artText = art.optString("text")

                            var match = false
                            if (targetNum != null) {
                                match = (artNum == targetNum)
                            } else if (isLawNameMatch) {
                                match = true
                            } else {
                                match = artText.contains(trimmed)
                            }

                            if (match) {
                                foundMatches++
                                sb.append("📜 ").append(lawName)
                                sb.append(" - المادة (").append(artNum).append(")
")
                                sb.append(artText.trim()).append("

-------------------

")
                            }
                        }
                    }

                    if (foundMatches == 0) {
                        "لا توجد نتائج مطابقة لـ \"$trimmed\""
                    } else {
                        sb.toString().trim()
                    }
                } catch (e: Exception) {
                    "حدث خطأ أثناء معالجة البحث: ${e.localizedMessage}"
                }
            }

            progressBar.visibility = View.GONE
            if (resultsText.isNotEmpty() && !resultsText.startsWith("لا توجد نتائج")) {
                tvSearchResult.text = resultsText
                scrollResults.visibility = View.VISIBLE
                searchActions.visibility = View.VISIBLE
            } else {
                tvEmpty.text = resultsText
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }
    private fun openDirectInBrowserOnly(url: String) {
        var target = url
        if (target.contains("/view")) {
            target = target.replace("/view", "/preview")
        }
        try {
            val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
            customTabs.intent.setPackage("com.android.chrome")
            customTabs.launchUrl(this, Uri.parse(target))
        } catch (_: Exception) {
            try {
                val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
                customTabs.launchUrl(this, Uri.parse(target))
            } catch (_: Exception) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
