package com.maen.almustashar

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
    private var cachedLawsIndex: org.json.JSONObject? = null
    private val cachedLawsMap = java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject>()

    private fun searchDriveFiles(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            android.widget.Toast.makeText(this, "يرجى إدخال نص البحث", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = android.view.View.VISIBLE
        tvEmpty.visibility = android.view.View.GONE
        scrollResults.visibility = android.view.View.GONE
        rvDriveResults.visibility = android.view.View.GONE
        searchActions.visibility = android.view.View.GONE

        Thread {
            val resultsText = try {
                val baseUrl = "https://raw.githubusercontent.com/starmaen/Al-Mustashar/main/data/laws/"
                fun fetchJson(endpoint: String): org.json.JSONObject? {
                    return try {
                        val url = java.net.URL(baseUrl + endpoint)
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 7000
                        conn.readTimeout = 7000
                        conn.useCaches = false
                        if (conn.responseCode == 200) {
                            val t = conn.inputStream.bufferedReader().readText()
                            org.json.JSONObject(t)
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }

                if (cachedLawsIndex == null) {
                    cachedLawsIndex = fetchJson("index.json")
                }
                val index = cachedLawsIndex

                if (index == null) {
                    "فشل الاتصال بقاعدة بيانات القوانين، تحقق من الاتصال بالإنترنت."
                } else {
                    val lawsArray = index.optJSONArray("laws") ?: org.json.JSONArray()
                    val sb = java.lang.StringBuilder()

                    val isListQuery = (trimmed == "\u0642\u0627\u0646\u0648\u0646" || trimmed == "\u0627\u0644\u0642\u0648\u0627\u0646\u064a\u0646")
                    if (isListQuery) {
                        sb.append("\u0642\u0627\u0626\u0645\u0629 \u0627\u0644\u0642\u0648\u0627\u0646\u064a\u0646 \u0627\u0644\u0645\u062a\u0627\u062d\u0629:\n\n")
                        for (i in 0 until lawsArray.length()) {
                            val l = lawsArray.getJSONObject(i)
                            sb.append("• ").append(l.optString("name"))
                            val yr = l.optString("year")
                            if (yr.isNotEmpty()) sb.append(" (").append(yr).append(")")
                            sb.append("\n  \u0639\u062f\u062f \u0627\u0644\u0645\u0648\u0627\u062f: ").append(l.optInt("articles_count"))
                            sb.append("\n\n")
                        }
                        sb.toString().trim()
                    } else {
                        val digitsOnly = trimmed.filter { it.isDigit() }
                        val targetNum = if (trimmed.startsWith("\u0627\u0644\u0645\u0627\u062f\u0629") || digitsOnly == trimmed) digitsOnly.toIntOrNull() else null

                        var foundMatches = 0
                        for (i in 0 until lawsArray.length()) {
                            val entry = lawsArray.getJSONObject(i)
                            val lawId = entry.optString("id")
                            val fileName = entry.optString("file")
                            val lawName = entry.optString("name")

                            var lawJson = cachedLawsMap[lawId]
                            if (lawJson == null) {
                                lawJson = fetchJson(fileName)
                                if (lawJson != null) cachedLawsMap[lawId] = lawJson
                            }
                            if (lawJson == null) continue

                            val articles = lawJson.optJSONArray("articles") ?: org.json.JSONArray()
                            val isLawNameMatch = (targetNum == null && lawName.contains(trimmed) && trimmed.length > 2)

                            for (j in 0 until articles.length()) {
                                val art = articles.getJSONObject(j)
                                val artNum = art.optInt("number")
                                val artText = art.optString("text")

                                val match = when {
                                    targetNum != null -> (artNum == targetNum)
                                    isLawNameMatch -> true
                                    else -> artText.contains(trimmed)
                                }

                                if (match) {
                                    foundMatches++
                                    sb.append("📜 ").append(lawName).append(" - \u0627\u0644\u0645\u0627\u062f\u0629 (").append(artNum).append(")\n")
                                    sb.append(artText.trim()).append("\n\n-------------------\n\n")
                                }
                            }
                        }

                        if (foundMatches == 0) {
                            "لا توجد نتائج مطابقة لـ \"$trimmed\""
                        } else {
                            sb.toString().trim()
                        }
                    }
                }
            } catch (e: Exception) {
                "خطأ: ${e.localizedMessage}"
            }

            runOnUiThread {
                progressBar.visibility = android.view.View.GONE
                if (resultsText.isNotEmpty() && !resultsText.startsWith("لا توجد") && !resultsText.startsWith("فشل")) {
                    tvSearchResult.text = resultsText
                    scrollResults.visibility = android.view.View.VISIBLE
                    searchActions.visibility = android.view.View.VISIBLE
                } else {
                    tvEmpty.text = resultsText
                    tvEmpty.visibility = android.view.View.VISIBLE
                }
            }
        }.start()
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
