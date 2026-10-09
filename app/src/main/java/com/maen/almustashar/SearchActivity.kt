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

    private fun normalizeArabic(s: String): String {
        if (s.isEmpty()) return ""
        var r = s
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF\\u061C]"), "")
            .replace(Regex("[\\u0600-\\u0605\\u06DD\\u070F]"), "")
        val digits = "\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669"
        for (i in digits.indices) r = r.replace(digits[i], "0123456789"[i])
        return r
            .replace('\u0622', '\u0627').replace('\u0623', '\u0627').replace('\u0625', '\u0627').replace('\u0671', '\u0627')
            .replace('\u0629', '\u0647')
            .replace('\u0649', '\u064A')
            .replace('\u0624', '\u0648').replace('\u0626', '\u064A')
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace(Regex("[\\u060C\\u061B\\u061F.,;:!?\\(\\)\\[\\]\\{\\}\"'\u00AB\u00BB\u2013\u2014\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun stripAl(w: String): String {
        if (w.length < 4) return w
        val prefixes = arrayOf("\u0648\u0627\u0644", "\u0641\u0627\u0644", "\u0628\u0627\u0644", "\u0643\u0627\u0644", "\u0644\u0644", "\u0627\u0644")
        for (p in prefixes) {
            if (w.startsWith(p) && w.length > p.length + 1) return w.substring(p.length)
        }
        return w
    }

    private fun normalizeText(s: String): String {
        return normalizeArabic(s).split(" ").joinToString(" ") { stripAl(it) }
    }

    private fun normalizeQueryWords(q: String): List<String> {
        return normalizeArabic(q).split(" ")
            .map { stripAl(it) }
            .filter { it.length >= 3 && !it.all { c -> c.isDigit() } }
    }

    private fun cleanArticleText(text: String, num: Int): String {
        var t = text.trim()
        val patterns = listOf(
            Regex("^\\s*(?:\u0627\u0644\u0645\u0627\u062f\u0629|\u0627\u0644\u0645\u0627\u062f\u0647|\u0645\u0627\u062f\u0629|\u0645\u0627\u062f\u0647)\\s*\\(?\\s*${num}\\s*\\)?\\s*[\\-:./]?\\s*"),
            Regex("^\\s*${num}\\s*[\\-:./]?\\s*")
        )
        for (p in patterns) t = p.replace(t, "")
        return t.trim()
    }

    private fun encodeUrl(url: String): String {
        val parts = url.split("/")
        val result = StringBuilder()
        for ((i, part) in parts.withIndex()) {
            if (i > 0) result.append("/")
            if (part.isEmpty()) continue
            try {
                result.append(java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20"))
            } catch (e: Exception) {
                result.append(part)
            }
        }
        return result.toString()
    }

    private fun fetchJson(ep: String): org.json.JSONObject? {
        val mirrors = listOf(
            "https://starmaen.github.io/Al-Mustashar/data/laws/",
            "https://cdn.jsdelivr.net/gh/starmaen/Al-Mustashar@main/data/laws/"
        )
        for (base in mirrors) {
            try {
                val full = base + ep
                val encoded = encodeUrl(full)
                val conn = java.net.URL(encoded).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().readText()
                    return org.json.JSONObject(text)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    private fun searchDriveFiles(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            android.widget.Toast.makeText(this, "\u064a\u0631\u062c\u0649 \u0625\u062f\u062e\u0627\u0644 \u0646\u0635 \u0627\u0644\u0628\u062d\u062b", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        progressBar.visibility = android.view.View.VISIBLE
        tvEmpty.visibility = android.view.View.GONE
        scrollResults.visibility = android.view.View.GONE
        rvDriveResults.visibility = android.view.View.GONE
        searchActions.visibility = android.view.View.GONE

        Thread {
            val result: String = try {
                if (cachedLawsIndex == null) cachedLawsIndex = fetchJson("index.json")
                val index = cachedLawsIndex
                if (index == null) {
                    "\u0641\u0634\u0644 \u0627\u0644\u0627\u062a\u0635\u0627\u0644 \u0628\u0627\u0644\u0628\u064a\u0627\u0646\u0627\u062a"
                } else {
                    val laws = index.optJSONArray("laws") ?: org.json.JSONArray()
                    val q = normalizeArabic(trimmed)
                    val sb = java.lang.StringBuilder()

                    if (q == "\u0642\u0627\u0646\u0648\u0646" || q == "\u0627\u0644\u0642\u0627\u0646\u0648\u0646" || q == "\u0627\u0644\u0642\u0648\u0627\u0646\u064a\u0646" || q == "\u0642\u0648\u0627\u0646\u064a\u0646") {
                        sb.append("\ud83d\udcda \u0627\u0644\u0642\u0648\u0627\u0646\u064a\u0646 \u0627\u0644\u0645\u062a\u0627\u062d\u0629 (").append(laws.length()).append("):\n\n")
                        for (i in 0 until laws.length()) {
                            val l = laws.getJSONObject(i)
                            sb.append("\ud83d\udcdc ").append(l.optString("name"))
                            val yr = l.optString("year")
                            if (yr.isNotEmpty() && yr != "\u063a\u064a\u0631-\u0645\u062d\u062f\u062f") sb.append(" \u2014 ").append(yr)
                            sb.append("\n   [").append(l.optString("type")).append("] | \u0627\u0644\u0645\u0648\u0627\u062f: ").append(l.optInt("articles_count")).append("\n\n")
                        }
                        sb.toString().trim()
                    } else {
                        val numRegex = Regex("(?:\\u0627\\u0644\\u0645\\u0627\\u062f\\u0647|\\u0627\\u0644\\u0645\\u0627\\u062f\\u0629|\\u0645\\u0627\\u062f\\u0647|\\u0645\\u0627\\u062f\\u0629|\\u0631\\u0642\\u0645)\\s*\\(?\\s*(\\d+)\\s*\\)?")
                        val numOnly = Regex("^(\\d+)$")
                        var targetNum = numRegex.find(q)?.groupValues?.get(1)?.toIntOrNull()
                        if (targetNum == null) targetNum = numOnly.find(q)?.groupValues?.get(1)?.toIntOrNull()

                        var textQ = q.replace(numRegex, " ").replace(Regex("\\s+"), " ").trim()
                        if (targetNum != null && (textQ.isEmpty() || textQ == q)) textQ = ""

                        var specificLawId: String? = null
                        val lawRefRegex = Regex("(?:\\u0645\\u0646|\\u0641\\u064a)\\s+(?:\\u0642\\u0627\\u0646\\u0648\\u0646|\\u0627\\u0644\\u0642\\u0627\\u0646\\u0648\\u0646)\\s+(.+)")
                        val lawRefMatch = lawRefRegex.find(textQ)
                        if (lawRefMatch != null) {
                            val lawNameQ = lawRefMatch.groupValues[1].trim()
                            textQ = textQ.substring(0, lawRefMatch.range.first).trim()
                            val lawWords = normalizeQueryWords(lawNameQ)
                            for (i in 0 until laws.length()) {
                                val l = laws.getJSONObject(i)
                                val normName = normalizeText(l.optString("name"))
                                if (lawWords.isNotEmpty() && lawWords.all { normName.contains(it) }) {
                                    specificLawId = l.optString("id")
                                    break
                                }
                            }
                        } else {
                            val startLawMatch = Regex("^(?:\\u0642\\u0627\\u0646\\u0648\\u0646|\\u0627\\u0644\\u0642\\u0627\\u0646\\u0648\\u0646)\\s+(.+)").find(textQ)
                            if (startLawMatch != null) {
                                val lawNameQ = startLawMatch.groupValues[1].trim()
                                val lawWords = normalizeQueryWords(lawNameQ)
                                for (i in 0 until laws.length()) {
                                    val l = laws.getJSONObject(i)
                                    val normName = normalizeText(l.optString("name"))
                                    if (lawWords.isNotEmpty() && lawWords.all { normName.contains(it) }) {
                                        specificLawId = l.optString("id")
                                        textQ = ""
                                        break
                                    }
                                }
                            }
                        }

                        val searchWords = if (textQ.isNotEmpty()) normalizeQueryWords(textQ) else emptyList()

                        val lawsToSearch = mutableListOf<org.json.JSONObject>()
                        for (i in 0 until laws.length()) {
                            val l = laws.getJSONObject(i)
                            if (specificLawId != null) {
                                if (l.optString("id") == specificLawId) lawsToSearch.add(l)
                            } else {
                                lawsToSearch.add(l)
                            }
                        }

                        var count = 0
                        for (law in lawsToSearch) {
                            val lawId = law.optString("id")
                            val fileName = law.optString("file")
                            val lawName = law.optString("name")
                            val lawYear = law.optString("year")

                            var lawJson = cachedLawsMap[lawId]
                            if (lawJson == null) {
                                lawJson = fetchJson(fileName)
                                if (lawJson != null) cachedLawsMap[lawId] = lawJson
                            }
                            if (lawJson == null) continue
                            val arts = lawJson.optJSONArray("articles") ?: continue

                            if (specificLawId != null && targetNum == null && searchWords.isEmpty()) {
                                sb.append("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\n")
                                sb.append("\ud83d\udcdc ").append(lawName)
                                if (lawYear.isNotEmpty() && lawYear != "\u063a\u064a\u0631-\u0645\u062d\u062f\u062f") sb.append(" (").append(lawYear).append(")")
                                sb.append("\n").append(law.optString("type")).append(" | \u0627\u0644\u0645\u0648\u0627\u062f: ").append(arts.length()).append("\n")
                                sb.append("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\n\n")
                                for (j in 0 until arts.length()) {
                                    val art = arts.getJSONObject(j)
                                    val num = art.optInt("number")
                                    sb.append("\u25aa \u0627\u0644\u0645\u0627\u062f\u0629 ").append(num).append("\n")
                                    sb.append(cleanArticleText(art.optString("text"), num)).append("\n\n")
                                    count++
                                }
                                continue
                            }

                            for (j in 0 until arts.length()) {
                                val art = arts.getJSONObject(j)
                                val artNum = art.optInt("number")
                                val artText = art.optString("text")

                                var matches = false
                                if (targetNum != null && searchWords.isEmpty()) {
                                    matches = (artNum == targetNum)
                                } else if (searchWords.isNotEmpty()) {
                                    val normText = normalizeText(artText)
                                    matches = searchWords.all { normText.contains(it) }
                                }

                                if (matches) {
                                    count++
                                    sb.append("\ud83d\udcdc ").append(lawName)
                                    if (lawYear.isNotEmpty() && lawYear != "\u063a\u064a\u0631-\u0645\u062d\u062f\u062f") sb.append(" (").append(lawYear).append(")")
                                    sb.append("\n\u25aa \u0627\u0644\u0645\u0627\u062f\u0629 ").append(artNum).append("\n")
                                    sb.append(cleanArticleText(artText, artNum)).append("\n")
                                    sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n")
                                    if (count >= 50) break
                                }
                            }
                            if (count >= 50) break
                        }

                        if (count == 0) "\u0644\u0627 \u062a\u0648\u062c\u062f \u0646\u062a\u0627\u0626\u062c \u0645\u0637\u0627\u0628\u0642\u0629 \u0644\u0640 \"" + trimmed + "\""
                        else sb.toString().trim()
                    }
                }
            } catch (e: Exception) {
                "\u062e\u0637\u0623: ${e.localizedMessage ?: "\u063a\u064a\u0631 \u0645\u0639\u0631\u0648\u0641"}"
            }

            runOnUiThread {
                progressBar.visibility = android.view.View.GONE
                if (result.startsWith("\u0644\u0627 \u062a\u0648\u062c\u062f") || result.startsWith("\u0641\u0634\u0644") || result.startsWith("\u062e\u0637\u0623")) {
                    tvEmpty.text = result
                    tvEmpty.visibility = android.view.View.VISIBLE
                } else {
                    tvSearchResult.text = result
                    scrollResults.visibility = android.view.View.VISIBLE
                    searchActions.visibility = android.view.View.VISIBLE
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
