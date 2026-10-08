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

class SearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var spinnerLaw: Spinner
    private lateinit var btnDoSearch: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var scrollResults: NestedScrollView
    private lateinit var tvSearchResult: TextView
    private lateinit var searchActions: LinearLayout

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

        // تفعيل النقر المباشر على روابط الإنترنت داخل نص المادة وضبط ألوانها
        tvSearchResult.movementMethod = LinkMovementMethod.getInstance()
        tvSearchResult.setTextColor(android.graphics.Color.parseColor("#0F2042"))
        tvSearchResult.setLinkTextColor(android.graphics.Color.parseColor("#1565C0"))

        spinnerLaw.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            lawChoices
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        lifecycleScope.launch {
            try {
                val laws = LawsRepository.loadLawsList()
                cachedLaws = laws
                val choices = mutableListOf(LawChoice(null, "كل القوانين"))
                choices.addAll(laws.map { LawChoice(it.id, it.name) })
                lawChoices = choices
                spinnerLaw.adapter = ArrayAdapter(
                    this@SearchActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    lawChoices
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
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
            Toast.makeText(this, "يرجى إدخال رقم المادة أو موضوع البحث", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        scrollResults.visibility = View.GONE
        searchActions.visibility = View.GONE

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
                tvEmpty.text = "حدث خطأ أثناء البحث: ${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }
}
