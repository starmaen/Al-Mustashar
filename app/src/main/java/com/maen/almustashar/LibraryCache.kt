package com.maen.almustashar

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// التخزين المحلي للمكتبة القانونية (نصوص فقط) + بحث محصور بها
object LibraryCache {
    data class BookMeta(
        val id: String,
        val title: String,
        val type: String,
        val driveUrl: String?,
        val chunksCount: Int,
        val toc: List<TocEntry> = emptyList()
    )

    data class TocEntry(
        val title: String,
        val page: Int = 0
    )

    data class Passage(
        val bookId: String,
        val bookTitle: String,
        val type: String,
        val n: Int,
        val heading: String,
        val text: String,
        val driveUrl: String?,
        val section: String = ""
    )

    private val MIRRORS = listOf(
        "https://starmaen.github.io/Al-Mustashar/data/library/",
        "https://cdn.jsdelivr.net/gh/starmaen/Al-Mustashar@main/data/library/"
    )

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "library").apply { mkdirs() }

    private fun encodeUrl(url: String): String {
        val sb = StringBuilder()
        for (ch in url) {
            if (ch.code in 0..127) sb.append(ch)
            else for (b in ch.toString().toByteArray(Charsets.UTF_8)) sb.append("%").append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun fetchText(ep: String): String? {
        for (base in MIRRORS) {
            try {
                val conn = URL(encodeUrl(base + ep)).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) return conn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun syncIfNeeded(ctx: Context): Boolean {
        return try {
            val d = dir(ctx)
            val indexText = fetchText("index.json") ?: return File(d, "index.json").exists()
            val idx = JSONObject(indexText)
            val books = idx.optJSONArray("books") ?: org.json.JSONArray()
            for (i in 0 until books.length()) {
                val b = books.getJSONObject(i)
                val file = b.optString("file")
                if (file.isEmpty()) continue
                val local = File(d, file)
                var need = !local.exists()
                if (!need) {
                    try {
                        if (JSONObject(local.readText()).optInt("chunks_count", -1) != b.optInt("chunks_count", -2)) need = true
                    } catch (_: Exception) {
                        need = true
                    }
                }
                if (need) fetchText(file)?.let { local.writeText(it) }
            }
            File(d, "index.json").writeText(indexText)
            true
        } catch (_: Exception) {
            File(dir(ctx), "index.json").exists()
        }
    }

    fun loadPool(ctx: Context): Pair<List<BookMeta>, List<Passage>>? {
        return try {
            val d = dir(ctx)
            val idxF = File(d, "index.json")
            if (!idxF.exists()) return null
            val idx = JSONObject(idxF.readText())
            val arr = idx.optJSONArray("books") ?: return null
            val books = mutableListOf<BookMeta>()
            val passages = mutableListOf<Passage>()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val id = b.optString("id")
                val file = b.optString("file")
                if (id.isEmpty() || file.isEmpty()) continue
                val lf = File(d, file)
                if (!lf.exists()) continue
                val title = b.optString("title", id)
                val type = b.optString("type", "مرجع")
                var driveUrl: String? = null
                try {
                    val bj = JSONObject(lf.readText())
                    driveUrl = bj.optString("drive_url").ifBlank { null }
                    val chArr = bj.optJSONArray("chunks") ?: org.json.JSONArray()
                    val tocArr = bj.optJSONArray("toc") ?: org.json.JSONArray()
                    val toc = mutableListOf<TocEntry>()
                    for (k in 0 until tocArr.length()) {
                        try {
                            val o = tocArr.getJSONObject(k)
                            val t = o.optString("t")
                            if (t.length >= 3) toc.add(TocEntry(t, o.optInt("p", 0)))
                        } catch (_: Exception) {
                        }
                    }
                    books.add(BookMeta(id, title, type, driveUrl, chArr.length(), toc))
                    for (j in 0 until chArr.length()) {
                        val c = chArr.getJSONObject(j)
                        val text = c.optString("text")
                        if (text.length < 40) continue
                        passages.add(
                            Passage(id, title, type, c.optInt("n", j + 1), c.optString("heading"), text, driveUrl, c.optString("section"))
                        )
                    }
                } catch (_: Exception) {
                }
            }
            if (books.isEmpty()) null else Pair(books, passages)
        } catch (_: Exception) {
            null
        }
    }

    fun norm(s: String): String {
        return s.lowercase()
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ٱ", "ا")
            .replace("ة", "ه").replace("ى", "ي").replace("ؤ", "و").replace("ئ", "ي")
            .replace(Regex("[^a-zA-Z0-9\u0621-\u064A]"), "")
    }

    // بحث محصور بالمكتبة: عبارة دقيقة أولاً، ثم تكرار المصطلحات، ثم الترويسات
    fun search(pool: List<Passage>, rawQuery: String, limit: Int = 15): List<Passage> {
        val terms = rawQuery.split(Regex("\\s+")).map { norm(it) }
            .filter { it.length > 2 && it !in setOf("كتاب", "الكتاب", "قانون", "القانون", "مادة", "المادة") }
        if (terms.isEmpty()) return emptyList()
        val phrase = norm(rawQuery)
        return pool.mapNotNull { p ->
            val nt = norm(p.text + " " + p.heading + " " + p.section + " " + p.bookTitle)
            var score = 0
            if (phrase.length > 3 && nt.contains(phrase)) score += 10
            for (t in terms) {
                var c = 0
                var idx = nt.indexOf(t)
                while (idx >= 0 && c < 3) {
                    c++
                    score += 3
                    idx = nt.indexOf(t, idx + 1)
                }
            }
            if (p.heading.isNotBlank()) {
                val nh = norm(p.heading)
                for (t in terms) if (nh.contains(t)) score += 2
            }
            if (score > 0) Pair(p, score) else null
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
