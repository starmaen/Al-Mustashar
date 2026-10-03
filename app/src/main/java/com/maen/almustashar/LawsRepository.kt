package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * النسخة الموحّدة الوحيدة لمنطق البحث القانوني.
 * تُستخدم من AIClient (الاستشارة السريعة) ومن SearchActivity (البحث في القوانين)
 * بنفس التوقيع: suspend fun searchRelevantLaws(query: String): String
 */
object LawsRepository {
    private var cached: List<Article>? = null
    private var lastTime: Long = 0
    private const val TTL = 300_000L

    data class Article(val law: String, val number: String, val text: String, val keywords: List<String> = emptyList())

    private fun normalizeDigits(s: String): String {
        val ar = "٠١٢٣٤٥٦٧٨٩"; val fa = "۰۱۲۳۴۵۶۷۸۹"; var r = s
        for (i in 0..9) { r = r.replace(ar[i], ('0' + i)); r = r.replace(fa[i], ('0' + i)) }
        return r
    }

    // يحلل الاستعلام لاستخراج رقم المادة واسم القانون (إن وجد)
    private fun parseQuery(q: String): Pair<String?, String?> {
        val n = normalizeDigits(q)
        val patterns = listOf(
            Regex("""(?:المادة|الماده)\s*رقم\s*[:\(]?\s*(\d+)"""),
            Regex("""نص\s+(?:المادة|الماده)\s*[:\(]?\s*(\d+)"""),
            Regex("""(?:المادة|الماده)\s*[:\(]?\s*(\d+)\s*\)?"""),
            Regex("""\b(?:مادة|ماده)\s*[:\(]?\s*(\d+)"""),
            Regex("""article\s*[:\(]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        )

        var foundNumber: String? = null
        var matchedText: String? = null

        for (p in patterns) {
            val match = p.find(n)
            if (match != null) {
                foundNumber = match.groupValues[1]
                matchedText = match.value
                break
            }
        }

        if (foundNumber == null) {
            val bare = n.trim()
            if (bare.matches(Regex("""\d+"""))) {
                foundNumber = bare
                matchedText = bare
            }
        }

        if (foundNumber != null && matchedText != null) {
            val remaining = n.replace(matchedText, "").trim()
            val words = remaining.split(" ", "،", "؟", "?", ".", ",", "\n", "\t", ":", ";", "\"", "'")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !isStop(it) }

            val lawNameQuery = if (words.isNotEmpty()) words.joinToString(" ") else null
            return Pair(foundNumber, lawNameQuery)
        }

        return Pair(null, null)
    }

    /**
     * الدالة الوحيدة المعتمدة للبحث. تُستدعى من داخل coroutine (lifecycleScope.launch
     * أو withContext(Dispatchers.IO)) في كل الشاشات.
     */
    suspend fun searchRelevantLaws(question: String, limit: Int = 6): String {
        val (num, lawNameQuery) = parseQuery(question)
        if (num != null) {
            val d = fetchByNumber(num, lawNameQuery)
            if (d != null) return d
            val targetLawInfo = if (lawNameQuery != null) " في قانون $lawNameQuery" else ""
            return "⚠️ المادة $num غير موجودة في قاعدة البيانات الحالية$targetLawInfo."
        }

        val all = loadAll()
        if (all.isEmpty()) return "⚠️ لم يتم العثور على نتائج (قاعدة البيانات فارغة أو تعذر الاتصال)."

        val kws = normalizeDigits(question)
            .split(" ", "،", "؟", "?", ".", ",", "\n", "\t", ":", ";", "\"", "'")
            .map { it.trim() }
            .filter { it.length >= 2 && !isStop(it) }
            .distinct()

        if (kws.isEmpty()) return "⚠️ لم يتم التعرف على كلمات بحث واضحة بالسؤال."

        val scored = all.map { a ->
            var s = 0
            for (k in kws) {
                if (a.text.contains(k, true)) s += 5
                if (a.keywords.any { it.contains(k, true) }) s += 3
                if (a.law.contains(k, true)) s += 2
            }
            a to s
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit)

        if (scored.isEmpty()) {
            return "⚠️ لم يتم العثور على نص مطابق لهذا السؤال ضمن القوانين المتاحة حاليًا."
        }
        return scored.joinToString("\n\n") { (a, _) -> "📖 ${a.law} - المادة ${a.number}:\n${a.text}" }
    }

    // يبحث عن رقم المادة داخل القوانين، مع إمكانية تحديد قانون معين للاستعلام
    private suspend fun fetchByNumber(n: String, lawNameQuery: String? = null): String? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val cols = listOf("articles", "مقالات", "المواد")
            val hits = mutableListOf<String>()
            for (lawDoc in db.collection("laws").get().await()) {
                val lawName = lawDoc.getString("name") ?: lawDoc.id

                if (lawNameQuery != null) {
                    val normalizedLawName = normalizeDigits(lawName).lowercase()
                    val normalizedQuery = normalizeDigits(lawNameQuery).lowercase()
                    val queryWords = normalizedQuery.split("\\s+".toRegex()).filter { it.length > 1 }
                    val matches = queryWords.isNotEmpty() && queryWords.all { normalizedLawName.contains(it) }
                    if (!matches) continue
                }

                for (c in cols) {
                    try {
                        val doc = lawDoc.reference.collection(c).document(n).get().await()
                        if (doc.exists()) {
                            val t = doc.getString("text") ?: doc.getString("content")
                            val nn = doc.getLong("number")?.toString() ?: doc.getString("number") ?: n
                            if (!t.isNullOrEmpty()) {
                                hits.add("📖 $lawName - المادة $nn:\n\n$t")
                                break
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            if (hits.isEmpty()) null else hits.joinToString("\n\n───────────────────────\n\n")
        } catch (_: Exception) { null }
    }

    private suspend fun loadAll(): List<Article> {
        val now = System.currentTimeMillis()
        cached?.let { if (now - lastTime < TTL) return it }
        val res = mutableListOf<Article>()
        try {
            val db = FirebaseFirestore.getInstance()
            for (lawDoc in db.collection("laws").get().await()) {
                val lawName = lawDoc.getString("name") ?: lawDoc.id
                for (c in listOf("articles", "مقالات", "المواد")) {
                    try {
                        val snap = lawDoc.reference.collection(c).get().await()
                        if (snap.isEmpty) continue
                        for (d in snap.documents) {
                            val num = d.getLong("number")?.toString() ?: d.getString("number") ?: d.id
                            val txt = d.getString("text") ?: d.getString("content") ?: ""
                            val kws = (d.get("keywords") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                            if (txt.isNotEmpty()) res.add(Article(lawName, num, txt, kws))
                        }
                        if (snap.size() > 0) break
                    } catch (_: Exception) {}
                }
            }
            cached = res; lastTime = now
        } catch (_: Exception) {}
        return res
    }

    private fun isStop(w: String) = setOf(
        "من", "في", "على", "عن", "إلى", "التي", "الذي", "هذا", "هذه", "ذلك", "تلك",
        "أريد", "أحتاج", "أطلب", "نص", "رقم", "هو", "هي", "ما", "لا", "مع", "بين",
        "عند", "حتى", "قد", "كان", "لكن", "أو", "ثم", "كل", "بعض", "مادة", "المادة",
        "ماده", "الماده", "القانون", "قانون"
    ).contains(w)

    fun clearCache() { cached = null; lastTime = 0 }
}