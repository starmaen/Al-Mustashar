package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseExpandableListAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 📚 المكتبة القانونية: مراجع قابلة للطي + بحث وسؤال محصور بها حصراً
// التنقل بأسلوب المتصفح: قائمة ← نتائج ← مقطع، والرجوع يعود للتبويب السابق.
class LibraryActivity : AppCompatActivity() {

    private enum class LibView { LIST, HITS, PASSAGE, ASK }

    private lateinit var etSearch: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var expList: ExpandableListView
    private lateinit var scrollResult: View
    private lateinit var tvResult: TextView
    private lateinit var navRow: View
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var tvPosition: TextView
    private lateinit var backCallback: OnBackPressedCallback

    private var libView = LibView.LIST
    private var passageFromHits = false
    private var currentList: List<LibraryCache.Passage> = emptyList()
    private var currentIndex: Int = -1

    private var books: List<LibraryCache.BookMeta> = emptyList()
    private var passages: List<LibraryCache.Passage> = emptyList()
    private var headings: Map<String, List<LibraryCache.Passage>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_library)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح المكتبة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        etSearch = findViewById(R.id.etLibrarySearch)
        progress = findViewById(R.id.libraryProgress)
        tvEmpty = findViewById(R.id.tvLibraryEmpty)
        expList = findViewById(R.id.expLibrary)
        scrollResult = findViewById(R.id.scrollLibraryResult)
        tvResult = findViewById(R.id.tvLibraryResult)
        navRow = findViewById(R.id.libraryNavRow)
        btnPrev = findViewById(R.id.btnLibraryPrev)
        btnNext = findViewById(R.id.btnLibraryNext)
        tvPosition = findViewById(R.id.tvLibraryPosition)

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                goBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        btnPrev.setOnClickListener { stepPassage(-1) }
        btnNext.setOnClickListener { stepPassage(1) }

        findViewById<Button>(R.id.btnLibrarySearch).setOnClickListener { doSearch() }
        findViewById<Button>(R.id.btnLibraryAsk).setOnClickListener { doAsk() }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        findViewById<Button>(R.id.btnLibraryCopy).setOnClickListener {
            val t = tvResult.text.toString()
            if (t.isNotEmpty()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Library", t))
                Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnLibraryShare).setOnClickListener {
            val t = tvResult.text.toString()
            if (t.isNotEmpty()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, t)
                }, "مشاركة من المكتبة"))
            }
        }

        loadLibrary()
    }

    private fun loadLibrary() {
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val pool = withContext(Dispatchers.IO) {
                try {
                    LibraryCache.syncIfNeeded(applicationContext)
                } catch (_: Exception) {
                }
                try {
                    LibraryCache.loadPool(applicationContext)
                } catch (_: Exception) {
                    null
                }
            }
            progress.visibility = View.GONE
            if (pool == null) {
                tvEmpty.text = "لا مراجع بعد — ضع كتباً في مجلد المكتبة على Drive واتصل بالإنترنت مرة واحدة."
                tvEmpty.visibility = View.VISIBLE
                return@launch
            }
            books = pool.first
            passages = pool.second
            headings = passages.groupBy { it.bookId }
            expList.setAdapter(LibraryAdapter())
            setView(LibView.LIST)
        }
    }

    // ——— التنقل بأسلوب المتصفح ———
    private fun setView(v: LibView) {
        libView = v
        backCallback.isEnabled = (v != LibView.LIST)
        if (v != LibView.PASSAGE) {
            navRow.visibility = View.GONE
        }
        when (v) {
            LibView.LIST -> {
                scrollResult.visibility = View.GONE
                tvEmpty.visibility = View.GONE
                expList.visibility = View.VISIBLE
            }
            else -> {
                expList.visibility = View.GONE
            }
        }
    }

    private fun goBack() {
        when (libView) {
            LibView.PASSAGE -> {
                if (passageFromHits && currentList.isNotEmpty()) {
                    showHits(currentList)
                } else {
                    setView(LibView.LIST)
                }
            }
            LibView.HITS, LibView.ASK -> setView(LibView.LIST)
            LibView.LIST -> { /* يعالجها النظام (خروج) */ }
        }
    }

    private fun passageHeader(p: LibraryCache.Passage): String {
        val sb = StringBuilder()
        sb.append("📖 ${p.bookTitle} [${p.type}] — مقطع ${p.n}")
        if (p.section.isNotBlank()) sb.append("\n📑 ${p.section}")
        if (p.heading.isNotBlank()) sb.append("\n▪ ${p.heading}")
        sb.append("\n\n")
        return sb.toString()
    }

    private fun showHits(hits: List<LibraryCache.Passage>) {
        currentList = hits
        currentIndex = -1
        passageFromHits = true
        tvResult.text = hits.joinToString("\n\n───────────────────────\n\n") { p ->
            passageHeader(p) + p.text
        }
        try {
            Linkify.addLinks(tvResult, Linkify.WEB_URLS)
        } catch (_: Exception) {
        }
        tvEmpty.visibility = View.GONE
        scrollResult.visibility = View.VISIBLE
        scrollResult.scrollTo(0, 0)
        setView(LibView.HITS)
    }

    private fun showPassage(index: Int) {
        if (index < 0 || index >= currentList.size) return
        currentIndex = index
        val p = currentList[index]
        tvResult.text = passageHeader(p) + p.text +
            "\n\n───────────────────────\nالنتيجة ${index + 1} من ${currentList.size}"
        try {
            Linkify.addLinks(tvResult, Linkify.WEB_URLS)
        } catch (_: Exception) {
        }
        tvPosition.text = "${index + 1} / ${currentList.size}"
        btnPrev.isEnabled = index > 0
        btnNext.isEnabled = index < currentList.size - 1
        navRow.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        scrollResult.visibility = View.VISIBLE
        scrollResult.scrollTo(0, 0)
        setView(LibView.PASSAGE)
        navRow.visibility = View.VISIBLE
    }

    private fun stepPassage(delta: Int) {
        showPassage(currentIndex + delta)
    }

    private fun doSearch() {
        val q = etSearch.text?.toString()?.trim() ?: ""
        if (q.isEmpty()) {
            Toast.makeText(this, "اكتب كلمة البحث داخل المكتبة", Toast.LENGTH_SHORT).show()
            return
        }
        if (passages.isEmpty()) {
            Toast.makeText(this, "المكتبة فارغة حالياً", Toast.LENGTH_SHORT).show()
            return
        }
        val hits = LibraryCache.search(passages, q, 15)
        expList.visibility = View.GONE
        if (hits.isEmpty()) {
            tvEmpty.text = "لا نتائج في المكتبة لـ \"$q\""
            tvEmpty.visibility = View.VISIBLE
            scrollResult.visibility = View.GONE
            // الرجوع من هنا يعيد قائمة المراجع لا واجهة التطبيق
            libView = LibView.HITS
            currentList = emptyList()
            backCallback.isEnabled = true
        } else {
            showHits(hits)
        }
    }

    private fun doAsk() {
        val q = etSearch.text?.toString()?.trim() ?: ""
        if (q.isEmpty()) {
            Toast.makeText(this, "اكتب سؤالك أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (passages.isEmpty()) {
            Toast.makeText(this, "المكتبة فارغة حالياً", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        scrollResult.visibility = View.GONE
        expList.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val hits = LibraryCache.search(passages, q, 6)
                if (hits.isEmpty()) {
                    progress.visibility = View.GONE
                    tvEmpty.text = "لا يوجد في المكتبة ما يجيب عن \"$q\""
                    tvEmpty.visibility = View.VISIBLE
                    libView = LibView.HITS
                    currentList = emptyList()
                    backCallback.isEnabled = true
                    return@launch
                }
                val refs = hits.joinToString("\n\n") { p ->
                    "[${p.bookTitle} — مقطع ${p.n}]: ${p.text.take(600)}"
                }.take(3500)
                val answer = AIClient.askLibraryQuestion(q, refs)
                progress.visibility = View.GONE
                tvResult.text = answer + "\n\n───────────────────────\n📌 المصادر من المكتبة حصراً:\n" +
                    hits.joinToString("\n") { "• ${it.bookTitle} — مقطع ${it.n}" }
                tvEmpty.visibility = View.GONE
                scrollResult.visibility = View.VISIBLE
                scrollResult.scrollTo(0, 0)
                setView(LibView.ASK)
            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvEmpty.text = "تعذر إتمام السؤال: ${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
                libView = LibView.HITS
                backCallback.isEnabled = true
            }
        }
    }

    private inner class LibraryAdapter : BaseExpandableListAdapter() {
        override fun getGroupCount(): Int = books.size
        override fun getChildrenCount(groupPosition: Int): Int {
            // بلا حد: القائمة تعرض كل مقاطع المرجع (كانت 60 فقط فتختفي البقية)
            return headings[books[groupPosition].id]?.size ?: 0
        }
        override fun getGroup(groupPosition: Int): Any = books[groupPosition]
        override fun getChild(groupPosition: Int, childPosition: Int): Any =
            headings[books[groupPosition].id]!![childPosition]
        override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()
        override fun getChildId(groupPosition: Int, childPosition: Int): Long = childPosition.toLong()
        override fun hasStableIds(): Boolean = false
        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean = true

        override fun getGroupView(groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup?): View {
            val b = books[groupPosition]
            val v = convertView ?: LayoutInflater.from(this@LibraryActivity)
                .inflate(android.R.layout.simple_expandable_list_item_1, parent, false)
            (v.findViewById<TextView>(android.R.id.text1)).text =
                (if (isExpanded) "📂 " else "📁 ") + b.title + "  [${b.type} • ${b.chunksCount} مقطع]"
            return v
        }

        override fun getChildView(groupPosition: Int, childPosition: Int, isLastChild: Boolean, convertView: View?, parent: ViewGroup?): View {
            val p = headings[books[groupPosition].id]!![childPosition]
            val v = convertView ?: LayoutInflater.from(this@LibraryActivity)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            (v.findViewById<TextView>(android.R.id.text1)).text =
                "▪ " + (if (p.section.isNotBlank()) p.section + " / " else "") +
                    p.heading.ifBlank { "مقطع ${p.n}" }
            v.setOnClickListener {
                val siblings = headings[books[groupPosition].id] ?: emptyList()
                currentList = siblings
                passageFromHits = false
                showPassage(childPosition)
            }
            return v
        }
    }
}
