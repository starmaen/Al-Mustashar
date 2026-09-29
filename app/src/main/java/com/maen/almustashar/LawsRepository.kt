package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    private var cache: List<Article>? = null
    private var cacheTime: Long = 0
    private const val TTL = 300_000L

    data class Amendment(
        val year: String = "",
        val law: String = "",
        val change: String = "",
        val effectiveFrom: String = ""
    )

    data class Article(
        val lawId: String,
        val lawName: String,
        val category: String,
        val number: String,
        val text: String,
        val originalText: String,
        val status: String,
        val amendments: List<Amendment>,
        val keywords: List<String>
    )

    private fun normalize(s: String): String {
        val ar = "٠١٢٣٤٥٦٧٨٩"; val fa = "۰۱۲۳۴۵۶۷۸۹"; var r = s
        for (i in 0..9) { r = r.replace(ar[i], '0' + i); r = r.replace(fa[i], '0' + i) }
        return r
    }

    private fun extractNumber(q: String): String? {
        val n = normalize(q)
        val pats = listOf(
            Regex("""المادة\s*رقم\s*[:\(]?\s*(\d+)"""),
            Regex("""نص\s+المادة\s*[:\(]?\s*(\d+)"""),
            Regex("""المادة\s*[:\(]?\s*(\d+)\s*\)?"""),
            Regex("""\bمادة\s*[:\(]?\s*(\d+)"""),
            Regex("""article\s*[:\(]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        )
        for (p in pats) { p.find(n)?.let { return it.groupValues[1] } }
        return null
    }

    // ============ الدالة الرئيسية ============
    suspend fun searchRelevantLaws(question: String, limit: Int = 6): String {
        val num = extractNumber(question)
        if (num != null) {
            val direct = fetchArticleByNumber(num)
            if (direct.isNotEmpty()) return direct
        }
        return searchByKeywords(question, limit)
    }

    // ============ البحث برقم المادة في كل القوانين ============
    private suspend fun fetchArticleByNumber(num: String): String {
        val db = FirebaseFirestore.getInstance()
        val results = mutableListOf<String>()

        try {
            val lawsSnapshot = db.collection("laws").get().await()
            for (lawDoc in lawsSnapshot.documents) {
                val lawName = lawDoc.getString("name") ?: lawDoc.id
                val category = lawDoc.getString("category") ?: ""

                for (col in listOf("articles", "مقالات", "المواد")) {
                    try {
                        val doc = lawDoc.reference.collection(col).document(num).get().await()
                        if (doc.exists()) {
                            val block = buildArticleBlock(lawName, category, num, doc)
                            if (block.isNotEmpty()) results.add(block)
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return if (results.isEmpty()) {
            "⚠️ لم أجد المادة $num في القوانين المرفوعة."
        } else {
            results.joinToString("\n\n")
        }
    }

    private fun buildArticleBlock(
        lawName: String,
        category: String,
        num: String,
        doc: com.google.firebase.firestore.DocumentSnapshot
    ): String {
        val text = doc.getString("text") ?: doc.getString("currentText") ?: ""
        val original = doc.getString("originalText") ?: ""
        val status = doc.getString("status") ?: "سارية"

        if (text.isEmpty() && original.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("📖 $lawName")
        if (category.isNotEmpty()) sb.append(" ($category)")
        sb.append(" - المادة $num:\n")

        if (original.isNotEmpty() && original != text) {
            sb.append("\n【 النص الأصلي 】\n")
            sb.append(original).append("\n")
        }

        if (text.isNotEmpty()) {
            sb.append("\n【 النص النافذ 】\n")
            sb.append(text).append("\n")
        }

        try {
            val am = doc.get("amendments") as? List<*>
            if (!am.isNullOrEmpty()) {
                sb.append("\n【 التعديلات 】\n")
                for (a in am) {
                    if (a is Map<*, *>) {
                        val y = a["year"] ?: ""
                        val l = a["law"] ?: ""
                        val c = a["change"] ?: ""
                        sb.append("🔹 $y - $l: $c\n")
                    }
                }
            }
        } catch (_: Exception) {}

        if (status.isNotEmpty() && status != "سارية") {
            sb.append("\n⚠️ الحالة: $status\n")
        }

        return sb.toString()
    }

    // ============ البحث بكلمات ============
    private suspend fun searchByKeywords(question: String, limit: Int): String {
        val all = loadAll()
        if (all.isEmpty()) return ""

        val kws = normalize(question)
            .split(" ", "،","؟","?",".",",","\n","\t",":",";","\"","'")
            .map { it.trim() }
            .filter { it.length >= 3 && !isStop(it) }
            .distinct()

        if (kws.isEmpty()) return ""

        val scored = all.map { a ->
            var s = 0
            val txt = "${a.text} ${a.originalText} ${a.keywords.joinToString(" ")}"
            for (k in kws) if (txt.contains(k, true)) s += 5
            a to s
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)

        if (scored.isEmpty()) return ""

        return scored.joinToString("\n\n") { (a, _) ->
            "📖 ${a.lawName} (${a.category}) - المادة ${a.number}:\n${a.text}"
        }
    }

    // ============ تحميل كل المواد (Cache) ============
    private suspend fun loadAll(): List<Article> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cacheTime < TTL) return it }

        val result = mutableListOf<Article>()
        try {
            val db = FirebaseFirestore.getInstance()
            for (lawDoc in db.collection("laws").get().await()) {
                val lawName = lawDoc.getString("name") ?: lawDoc.id
                val category = lawDoc.getString("category") ?: ""

                for (col in listOf("articles", "مقالات", "المواد")) {
                    try {
                        val snap = lawDoc.reference.collection(col).get().await()
                        if (snap.isEmpty) continue
                        for (d in snap.documents) {
                            val number = d.getLong("number")?.toString()
                                ?: d.getString("number") ?: d.id
                            val text = d.getString("text")
                                ?: d.getString("currentText") ?: ""
                            val original = d.getString("originalText") ?: ""
                            val status = d.getString("status") ?: "سارية"
                            val kws = (d.get("keywords") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                            val ams = mutableListOf<Amendment>()
                            (d.get("amendments") as? List<*>)?.forEach { a ->
                                if (a is Map<*, *>) ams.add(Amendment(
                                    a["year"]?.toString() ?: "",
                                    a["law"]?.toString() ?: "",
                                    a["change"]?.toString() ?: "",
                                    a["effectiveFrom"]?.toString() ?: ""
                                ))
                            }
                            if (text.isNotEmpty() || original.isNotEmpty()) {
                                result.add(Article(lawDoc.id, lawName, category, number, text, original, status, ams, kws))
                            }
                        }
                        if (snap.size() > 0) break
                    } catch (_: Exception) {}
                }
            }
            cache = result; cacheTime = now
        } catch (_: Exception) {}
        return result
    }

    private fun isStop(w: String) = setOf(
        "من","في","على","عن","إلى","التي","الذي","هذا","هذه","ذلك","تلك",
        "أريد","أحتاج","أطلب","نص","رقم","هو","هي","ما","لا","مع","بين",
        "عند","حتى","قد","كان","لكن","أو","ثم","كل","بعض","مادة","المادة"
    ).contains(w)

    fun clearCache() { cache = null; cacheTime = 0 }
}
