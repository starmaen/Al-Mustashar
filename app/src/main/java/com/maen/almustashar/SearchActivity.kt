package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private lateinit var btnCopyArticle: Button
    private lateinit var btnShareArticle: Button
    private lateinit var btnOpenPdf: Button

    private lateinit var rgMode: RadioGroup
    private lateinit var rbFirestore: RadioButton
    private lateinit var rbDrive: RadioButton
    private lateinit var rvDriveResults: RecyclerView
    private lateinit var driveAdapter: DriveLawAdapter

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class LawChoice(val id: String?, val title: String) {
        override fun toString(): String = title
    }

    private var cachedLaws: List<LawsRepository.LawMeta> = emptyList()
    private var lawChoices: List<LawChoice> = listOf(LawChoice(null, "كل القوانين"))
    private var currentPdfUrl: String? = null

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
                etSearch.hint = "ابحث بالنص الكامل داخل وثائق ومراجع Drive..."
            } else {
                spinnerLaw.visibility = View.VISIBLE
                rvDriveResults.visibility = View.GONE
                etSearch.hint = "مثال: المادة 117 أصول جزائية، أو 221 مدنية..."
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
            val url = currentPdfUrl
            if (!url.isNullOrBlank()) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر فتح رابط المستند: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "المستند الأصلي غير مرفق لهذه المادة", Toast.LENGTH_SHORT).show()
            }
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
                tvEmpty.text = "تعذر إتمام البحث المهيكل: ${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun searchDriveCloud(query: String) {
        progressBar.visibility = View.GONE
        tvEmpty.visibility = View.VISIBLE
        searchActions.visibility = View.VISIBLE
        rvDriveResults.visibility = View.GONE

        tvEmpty.text = "جاري فتح البحث في أرشيف Drive عن: \"$query\""
        
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
    }
    }
}
