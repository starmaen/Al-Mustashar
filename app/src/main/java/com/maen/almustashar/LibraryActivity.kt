package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
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

    // أقسام المكتبة: تصفية حسب القسم بدل العرض العشوائي المكدس
    private var activeSection: String? = null
    private var visibleBooks: List<LibraryCache.BookMeta> = emptyList()
    private var libAdapter: LibraryAdapter? = null
    private val sectionChips = mutableMapOf<String?, Int>()

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

        sectionChips[null] = R.id.chipAll
        sectionChips["مراجع قانونية"] = R.id.chipRef
        sectionChips["قوانين واتفاقات دولية"] = R.id.chipLaws
        sectionChips["اجتهادات قضائية"] = R.id.chipCases
        sectionChips["كتب منوعة"] = R.id.chipMisc
        for ((section, id) in sectionChips) {
            findViewById<Button>(id).setOnClickListener { setSectionFilter(section) }
        }

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
            libAdapter = LibraryAdapter()
            expList.setAdapter(libAdapter)
            applySectionFilter()
            setView(LibView.LIST)
        }
    }

    private fun applySectionFilter() {
        visibleBooks = if (activeSection == null) books else books.filter { it.section == activeSection }
        libAdapter?.notifyDataSetChanged()
        paintChips()
    }

    private fun setSectionFilter(section: String?) {
        activeSection = section
        applySectionFilter()
        setView(LibView.LIST)
    }

    private fun paintChips() {
        val on = 0xFF0F2042.toInt()
        val off = 0xFFE2E8F0.toInt()
        for ((section, id) in sectionChips) {
            try {
                val b = findViewById<Button>(id)
                val selected = (section == activeSection)
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected) on else off)
                b.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF0F2042.toInt())
            } catch (_: Exception) {
            }
        }
    }

    // القفز من ترشيح موضوعي إلى فهرس الكتاب مباشرة
    private fun jumpToBook(bookId: String) {
        activeSection = null
        applySectionFilter()
        setView(LibView.LIST)
        val idx = visibleBooks.indexOfFirst { it.id == bookId }
        if (idx >= 0) {
            try {
                expList.expandGroup(idx)
                expList.smoothScrollToPosition(idx)
            } catch (_: Exception) {
            }
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

    private fun showHits(hits: List<LibraryCache.Passage>, suggestions: List<LibraryCache.BookSuggestion> = emptyList()) {
        currentList = hits
        currentIndex = -1
        passageFromHits = true
        // قائمة منظمة: كتب مقترحة حول الموضوع أولاً، ثم نتائج قابلة للضغط
        val sb = SpannableStringBuilder()
        if (suggestions.isNotEmpty()) {
            sb.append("📚 كتب مقترحة حول موضوعك:\n\n")
            suggestions.forEachIndexed { i, s ->
                val header = "${i + 1}. 📕 ${s.book.title}\n   [${s.book.section} • ${s.book.type} • ${s.book.chunksCount} مقطع]\n"
                val start = sb.length
                sb.append(header)
                val bid = s.book.id
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        jumpToBook(bid)
                    }
                }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
            }
            sb.append("───────────────────────\n\n")
        }
        if (hits.isEmpty()) {
            sb.append("لا مقاطع مطابقة حرفياً — جرّب صياغة أخرى أو افتح فهرس كتاب مقترح.")
        }
        hits.forEachIndexed { i, p ->
            val header = "${i + 1}. 📖 ${p.bookTitle} — مقطع ${p.n}" +
                (if (p.section.isNotBlank()) "\n   📑 ${p.section}" else "") +
                (if (p.heading.isNotBlank()) "\n   ▪ ${p.heading}" else "") + "\n"
            val start = sb.length
            sb.append(header)
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    showPassage(i)
                }
            }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val snippet = p.text.take(250).trim() + "…\n   (اضغط العنوان للعرض الكامل)\n\n───────────────────────\n\n"
            sb.append(snippet)
        }
        tvResult.text = sb
        tvResult.movementMethod = LinkMovementMethod.getInstance()
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
        // بحث بالصفحة: "صفحة 12" — أقرب عنوان فهرس مرقم في كل كتاب
        val page = LibraryCache.extractPageNumber(q)
        if (page > 0) {
            showPageResults(page)
            return
        }
        val suggestions = LibraryCache.suggestBooks(books, passages, q, 4)
        if (hits.isEmpty() && suggestions.isEmpty()) {
            tvEmpty.text = "لا نتائج في المكتبة لـ \"$q\""
            tvEmpty.visibility = View.VISIBLE
            scrollResult.visibility = View.GONE
            // الرجوع من هنا يعيد قائمة المراجع لا واجهة التطبيق
            libView = LibView.HITS
            currentList = emptyList()
            backCallback.isEnabled = true
        } else {
            showHits(hits, suggestions)
        }
    }

    private fun showPageResults(page: Int) {
        data class Row(val bookId: String, val bookTitle: String, val entry: LibraryCache.TocEntry, val chunkIdx: Int)
        val rows = mutableListOf<Row>()
        for (b in books) {
            val paged = b.toc.filter { it.page > 0 }
            if (paged.isEmpty()) continue
            var best = paged[0]
            for (e in paged) {
                if (e.page <= page && e.page >= best.page) best = e
            }
            if (best.page > page) best = paged.minByOrNull { kotlin.math.abs(it.page - page) } ?: best
            rows.add(Row(b.id, b.title, best, resolveTocToChunk(b.id, best.title)))
        }
        if (rows.isEmpty()) {
            tvEmpty.text = "لا ترقيم صفحات في فهارس الكتب — جرّب البحث بالكلمة أو المعنى."
            tvEmpty.visibility = View.VISIBLE
            scrollResult.visibility = View.GONE
            libView = LibView.HITS
            currentList = emptyList()
            backCallback.isEnabled = true
            return
        }
        currentList = emptyList()
        currentIndex = -1
        passageFromHits = false
        val sb = SpannableStringBuilder()
        sb.append("📄 صفحة $page — أقرب عناوين الفهارس:\n\n")
        rows.forEachIndexed { i, r ->
            val header = "${i + 1}. 📖 ${r.bookTitle}\n   📑 ${r.entry.title}  (ص ${r.entry.page})\n"
            val start = sb.length
            sb.append(header)
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val siblings = headings[r.bookId] ?: emptyList()
                    if (siblings.isEmpty()) return
                    currentList = siblings
                    passageFromHits = false
                    showPassage(r.chunkIdx.coerceIn(siblings.indices))
                }
            }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n")
        }
        tvResult.text = sb
        tvResult.movementMethod = LinkMovementMethod.getInstance()
        tvEmpty.visibility = View.GONE
        scrollResult.visibility = View.VISIBLE
        scrollResult.scrollTo(0, 0)
        setView(LibView.HITS)
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

    // أطفال المجموعة: فهرس الكتاب إن وُجد (منظم)، وإلا المقاطع (احتياط)
    private fun bookToc(bookId: String): List<LibraryCache.TocEntry> =
        books.find { it.id == bookId }?.toc ?: emptyList()

    private fun resolveTocToChunk(bookId: String, title: String): Int {
        val siblings = headings[bookId] ?: return 0
        if (siblings.isEmpty()) return 0
        val nt = LibraryCache.norm(title)
        if (nt.isNotEmpty()) {
            for (i in siblings.indices) {
                val s = siblings[i]
                if (LibraryCache.norm(s.heading).contains(nt) ||
                    LibraryCache.norm(s.text.take(200)).contains(nt) ||
                    nt.contains(LibraryCache.norm(s.heading))) {
                    return i
                }
            }
        }
        return 0
    }

    private inner class LibraryAdapter : BaseExpandableListAdapter() {
        override fun getGroupCount(): Int = visibleBooks.size
        override fun getChildrenCount(groupPosition: Int): Int {
            val toc = bookToc(visibleBooks[groupPosition].id)
            if (toc.isNotEmpty()) return toc.size
            // بلا حد: كل مقاطع المرجع (كانت 60 فقط فتختفي البقية)
            return headings[visibleBooks[groupPosition].id]?.size ?: 0
        }
        override fun getGroup(groupPosition: Int): Any = visibleBooks[groupPosition]
        override fun getChild(groupPosition: Int, childPosition: Int): Any {
            val toc = bookToc(visibleBooks[groupPosition].id)
            if (toc.isNotEmpty()) return toc[childPosition]
            return headings[visibleBooks[groupPosition].id]!![childPosition]
        }
        override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()
        override fun getChildId(groupPosition: Int, childPosition: Int): Long = childPosition.toLong()
        override fun hasStableIds(): Boolean = false
        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean = true

        override fun getGroupView(groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup?): View {
            val b = visibleBooks[groupPosition]
            val v = convertView ?: LayoutInflater.from(this@LibraryActivity)
                .inflate(android.R.layout.simple_expandable_list_item_1, parent, false)
            (v.findViewById<TextView>(android.R.id.text1)).text =
                (if (isExpanded) "📂 " else "📁 ") + b.title +
                    "  [${b.section} • ${b.type} • ${b.chunksCount} مقطع" +
                    (if (b.toc.isNotEmpty()) " • ${b.toc.size} عنوان فهرس" else "") + "]"
            return v
        }

        override fun getChildView(groupPosition: Int, childPosition: Int, isLastChild: Boolean, convertView: View?, parent: ViewGroup?): View {
            val bookId = visibleBooks[groupPosition].id
            val toc = bookToc(bookId)
            val v = convertView ?: LayoutInflater.from(this@LibraryActivity)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            if (toc.isNotEmpty()) {
                val entry = toc[childPosition]
                (v.findViewById<TextView>(android.R.id.text1)).text =
                    "📑 ${entry.title}" + (if (entry.page > 0) "  (ص ${entry.page})" else "")
                v.setOnClickListener {
                    val siblings = headings[bookId] ?: emptyList()
                    if (siblings.isEmpty()) {
                        Toast.makeText(this@LibraryActivity, "لا مقاطع بعد لهذا المرجع", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    currentList = siblings
                    passageFromHits = false
                    showPassage(resolveTocToChunk(bookId, entry.title))
                }
            } else {
                val p = headings[bookId]!![childPosition]
                (v.findViewById<TextView>(android.R.id.text1)).text =
                    "▪ " + (if (p.section.isNotBlank()) p.section + " / " else "") +
                        p.heading.ifBlank { "مقطع ${p.n}" }
                v.setOnClickListener {
                    val siblings = headings[bookId] ?: emptyList()
                    currentList = siblings
                    passageFromHits = false
                    showPassage(childPosition)
                }
            }
            return v
        }
    }
}
