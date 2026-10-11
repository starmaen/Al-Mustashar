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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 📚 المكتبة القانونية: مراجع قابلة للطي + بحث وسؤال محصور بها حصراً
class LibraryActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var expList: ExpandableListView
    private lateinit var scrollResult: View
    private lateinit var tvResult: TextView

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
            expList.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }
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
        } else {
            tvResult.text = hits.joinToString("\n\n───────────────────────\n\n") { p ->
                "📖 ${p.bookTitle} [${p.type}] — مقطع ${p.n}" +
                    (if (p.heading.isNotBlank()) " (${p.heading})" else "") +
                    ":\n\n${p.text}" +
                    (if (!p.driveUrl.isNullOrBlank()) "\n\n🔗 الأصل: ${p.driveUrl}" else "")
            }
            try {
                Linkify.addLinks(tvResult, Linkify.WEB_URLS)
            } catch (_: Exception) {
            }
            tvEmpty.visibility = View.GONE
            scrollResult.visibility = View.VISIBLE
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
            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvEmpty.text = "تعذر إتمام السؤال: ${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private inner class LibraryAdapter : BaseExpandableListAdapter() {
        override fun getGroupCount(): Int = books.size
        override fun getChildrenCount(groupPosition: Int): Int {
            val h = headings[books[groupPosition].id] ?: emptyList()
            return minOf(h.size, 60)
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
                "▪ ${p.heading.ifBlank { "مقطع ${p.n}" }}"
            v.setOnClickListener {
                tvResult.text = "📖 ${p.bookTitle} — مقطع ${p.n}" +
                    (if (p.heading.isNotBlank()) " (${p.heading})" else "") +
                    ":\n\n${p.text}" +
                    (if (!p.driveUrl.isNullOrBlank()) "\n\n🔗 الأصل: ${p.driveUrl}" else "")
                try {
                    Linkify.addLinks(tvResult, Linkify.WEB_URLS)
                } catch (_: Exception) {
                }
                expList.visibility = View.GONE
                tvEmpty.visibility = View.GONE
                scrollResult.visibility = View.VISIBLE
            }
            return v
        }
    }
}
