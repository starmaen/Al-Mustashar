#!/bin/bash
set -e

# 1. تحديث محرك البحث (LawsRepository.kt)
cat << 'KOTLIN_LAWS' > app/src/main/java/com/maen/almustashar/LawsRepository.kt
package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    data class LawMeta(
        val id: String,
        val name: String,
        val drivePdfUrl: String?
    )

    data class ArticleMeta(
        val lawId: String,
        val lawName: String,
        val number: String,
        val text: String,
        val drivePdfUrl: String?
    )

    private var lawsCache: List<LawMeta>? = null

    suspend fun loadLawsList(): List<LawMeta> {
        lawsCache?.let { return it }
        val db = FirebaseFirestore.getInstance()
        val snapshot = db.collection("laws").get().await()
        val list = snapshot.documents.map { doc ->
            LawMeta(
                id = doc.id,
                name = doc.getString("name") ?: doc.id,
                drivePdfUrl = doc.getString("drivePdfUrl")
            )
        }
        lawsCache = list
        return list
    }

    private suspend fun loadAllArticles(laws: List<LawMeta>): List<ArticleMeta> {
        val db = FirebaseFirestore.getInstance()
        val result = mutableListOf<ArticleMeta>()
        for (law in laws) {
            val articlesSnap = db.collection("laws").document(law.id).collection("articles").get().await()
            for (art in articlesSnap.documents) {
                val text = pickText(art.data ?: emptyMap()) ?: continue
                val pdf = art.getString("drivePdfUrl") ?: law.drivePdfUrl
                result.add(
                    ArticleMeta(
                        lawId = law.id,
                        lawName = law.name,
                        number = art.id,
                        text = text,
                        drivePdfUrl = pdf
                    )
                )
            }
        }
        return result
    }

    private fun pickText(data: Map<String, Any?>): String? {
        val keys = listOf("currentText", "effectiveText", "text", "content", "originalText")
        for (k in keys) {
            val v = data[k] as? String
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    suspend fun searchRelevantLaws(rawQuery: String, targetLawId: String? = null): String {
        val query = rawQuery.trim()
        val laws = loadLawsList()

        fun norm(s: String): String {
            return s.lowercase()
                .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ة", "ه").replace("ى", "ي")
                .replace("السوري", "")
                .replace(Regex("[^a-zA-Z0-9\u0621-\u064A]"), "")
        }

        val normQ = norm(query)

        // 1. استخراج رقم المادة إن وجد
        val numberRegex = Regex("(\\d+)")
        val matchNumber = numberRegex.find(query)
        val extractedNumber = matchNumber?.value

        // 2. محاولة التعرف على القانون المحدد
        var identifiedLawId = targetLawId
        if (identifiedLawId == null && normQ.isNotEmpty()) {
            val matched = laws.find { law ->
                val nl = norm(law.name)
                nl.isNotEmpty() && (normQ.contains(nl) || nl.contains(normQ))
            }
            if (matched != null) {
                identifiedLawId = matched.id
            }
        }

        // الحالة 1: رقم + اسم قانون محدد
        if (!extractedNumber.isNullOrEmpty() && identifiedLawId != null) {
            val res = fetchArticleFromLaw(extractedNumber, identifiedLawId, laws)
            if (!res.isNullOrBlank()) return res
        }

        // الحالة 2: رقم فقط (جلب المادة من جميع القوانين)
        val hasArticleWord = query.contains("مادة") || query.contains("ماده") || query.contains("المادة") || query.contains("الماده")
        if (!extractedNumber.isNullOrEmpty() && (hasArticleWord || query.length <= 6)) {
            val res = fetchArticleFromAllLaws(extractedNumber, laws)
            if (!res.isNullOrBlank()) return res
        }

        // الحالة 3: عرض القانون كاملاً
        val hasDigits = query.any { it.isDigit() }
        val isFullLawRequest = identifiedLawId != null && !hasDigits && (
            query.isEmpty() ||
            query.contains("كامل") || query.contains("كاملا") ||
            laws.find { it.id == identifiedLawId }?.let {
                val nl = norm(it.name)
                normQ == nl || normQ.contains(nl) || nl.contains(normQ)
            } == true
        )

        if (identifiedLawId != null && isFullLawRequest) {
            val allArticles = loadAllArticles(laws).filter { it.lawId == identifiedLawId }
                .sortedBy { it.number.toIntOrNull() ?: 9999 }
            if (allArticles.isNotEmpty()) {
                return allArticles.joinToString("\n\n───────────────────────\n\n") { a ->
                    formatOutput(a.lawName, a.number, a.text, a.drivePdfUrl)
                }
            }
        }

        // الحالة 4: بحث سياقي وموضوعي
        val cleanTerms = query.split(" ")
            .map { it.trim() }
            .filter { it.length > 1 && !it.all { ch -> ch.isDigit() } && it !in listOf("قانون", "القانون", "كامل", "كاملا", "السوري", "مادة", "المادة", "ماده", "الماده") }

        val allArticles = loadAllArticles(laws)
        val pool = if (identifiedLawId != null) allArticles.filter { it.lawId == identifiedLawId } else allArticles

        val scored = pool.mapNotNull { art ->
            val nText = norm(art.text)
            var score = 0
            for (term in cleanTerms) {
                val nt = norm(term)
                if (nt.isNotEmpty() && nText.contains(nt)) score += 3
            }
            if (score > 0) Pair(art, score) else null
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) {
            return "⚠️ لم يتم العثور على نص مطابق لهذا البحث."
        }

        return scored.take(25).joinToString("\n\n───────────────────────\n\n") { (a, _) ->
            formatOutput(a.lawName, a.number, a.text, a.drivePdfUrl)
        }
    }

    private suspend fun fetchArticleFromLaw(n: String, lawId: String, laws: List<LawMeta>): String? {
        val db = FirebaseFirestore.getInstance()
        return try {
            val lawMeta = laws.find { it.id == lawId } ?: return null
            val doc = db.collection("laws").document(lawId).collection("articles").document(n).get().await()
            if (doc.exists()) {
                val text = pickText(doc.data ?: emptyMap()) ?: return null
                val pdf = doc.getString("drivePdfUrl") ?: lawMeta.drivePdfUrl
                formatOutput(lawMeta.name, n, text, pdf)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchArticleFromAllLaws(n: String, laws: List<LawMeta>): String? {
        val db = FirebaseFirestore.getInstance()
        val results = mutableListOf<String>()
        for (law in laws) {
            try {
                val doc = db.collection("laws").document(law.id).collection("articles").document(n).get().await()
                if (doc.exists()) {
                    val text = pickText(doc.data ?: emptyMap()) ?: continue
                    val pdf = doc.getString("drivePdfUrl") ?: law.drivePdfUrl
                    results.add(formatOutput(law.name, n, text, pdf))
                }
            } catch (_: Exception) {}
        }
        return if (results.isNotEmpty()) results.joinToString("\n\n═══════════════════════\n\n") else null
    }

    private fun formatOutput(lawName: String, number: String, text: String, pdfUrl: String?): String {
        val sb = StringBuilder()
        sb.append("📖 $lawName - المادة $number:\n\n")
        sb.append(text)
        if (!pdfUrl.isNullOrBlank()) {
            sb.append("\n\n🔗 رابط المستند: $pdfUrl")
        }
        return sb.toString()
    }
}
KOTLIN_LAWS

# 2. تحديث واجهة البحث وتشغيل روابط Drive عبر المتصفح (SearchActivity.kt)
cat << 'KOTLIN_SEARCH' > app/src/main/java/com/maen/almustashar/SearchActivity.kt
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

    private fun searchDriveFiles(query: String) {
        lifecycleScope.launch {
            try {
                if (cachedLaws.isEmpty()) {
                    cachedLaws = LawsRepository.loadLawsList()
                }

                fun normalize(s: String) = s.lowercase()
                    .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                    .replace("ة", "ه").replace("ى", "ي")
                    .replace("السوري", "")
                    .replace(Regex("[^a-zA-Z0-9\\u0621-\\u064A]"), "")

                val nq = normalize(query)
                val matchedFiles = cachedLaws.filter { law ->
                    val nl = normalize(law.name)
                    nl.contains(nq) || nq.contains(nl)
                }.map { law ->
                    DriveLawFile(
                        id = law.id,
                        name = law.name,
                        mimeType = "application/pdf",
                        webViewLink = law.drivePdfUrl ?: defaultDriveFolder,
                        webContentLink = law.drivePdfUrl
                    )
                }

                progressBar.visibility = View.GONE

                if (matchedFiles.isNotEmpty()) {
                    driveAdapter.submitList(matchedFiles)
                    rvDriveResults.visibility = View.VISIBLE
                } else {
                    tvEmpty.text = "لم يتم العثور على ملف يطابق: \"$query\"\nيمكنك فتح أرشيف Drive العام مباشرة:"
                    tvEmpty.visibility = View.VISIBLE

                    searchActions.visibility = View.VISIBLE
                    btnOpenPdf.text = "📂 فتح مجلد الأرشيف في المتصفح"
                    btnOpenPdf.setOnClickListener {
                        openDirectInBrowserOnly(defaultDriveFolder)
                    }
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvEmpty.text = "تعذر إتمام البحث في الأرشيف: ${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun openDirectInBrowserOnly(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pm = packageManager
            val resolveInfos = pm.queryIntentActivities(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val nonDriveBrowser = resolveInfos.firstOrNull { 
                !it.activityInfo.packageName.contains("com.google.android.apps.docs") &&
                !it.activityInfo.packageName.contains("drive")
            }

            if (nonDriveBrowser != null) {
                browserIntent.setPackage(nonDriveBrowser.activityInfo.packageName)
                startActivity(browserIntent)
            } else {
                startActivity(Intent.createChooser(browserIntent, "فتح الرابط عبر المتصفح"))
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
KOTLIN_SEARCH

# 3. تحديث مهايئ عناصر درايف (DriveLawAdapter.kt)
cat << 'KOTLIN_ADAPTER' > app/src/main/java/com/maen/almustashar/DriveLawAdapter.kt
package com.maen.almustashar

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DriveLawAdapter : ListAdapter<DriveLawFile, DriveLawAdapter.DriveViewHolder>(DiffCallback) {

    class DriveViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvDriveFileName)
        val tvType: TextView = itemView.findViewById(R.id.tvDriveFileType)
        val ivIcon: ImageView = itemView.findViewById(R.id.ivDriveFileIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DriveViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_drive_law, parent, false)
        return DriveViewHolder(view)
    }

    override fun onBindViewHolder(holder: DriveViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.name
        holder.tvType.text = "مستند قانوني PDF"

        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            val url = item.webViewLink ?: item.webContentLink ?: "https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"
            
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val pm = context.packageManager
                val resolveInfos = pm.queryIntentActivities(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
                val nonDrive = resolveInfos.firstOrNull { 
                    !it.activityInfo.packageName.contains("com.google.android.apps.docs") &&
                    !it.activityInfo.packageName.contains("drive")
                }
                if (nonDrive != null) {
                    browserIntent.setPackage(nonDrive.activityInfo.packageName)
                    context.startActivity(browserIntent)
                } else {
                    context.startActivity(Intent.createChooser(browserIntent, "فتح عبر المتصفح"))
                }
            } catch (_: Exception) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DriveLawFile>() {
        override fun areItemsTheSame(oldItem: DriveLawFile, newItem: DriveLawFile): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DriveLawFile, newItem: DriveLawFile): Boolean = oldItem == newItem
    }
}
KOTLIN_ADAPTER

# 4. الرفع المباشر إلى GitHub
git add app/src/main/java/com/maen/almustashar/LawsRepository.kt \
        app/src/main/java/com/maen/almustashar/SearchActivity.kt \
        app/src/main/java/com/maen/almustashar/DriveLawAdapter.kt
git commit -m "fix(final): comprehensive search engine and drive browser bypass"
git push

echo "============================================="
echo "✅ تم التحديث والرفع بنجاح! يبدأ البناء الآن."
echo "============================================="
