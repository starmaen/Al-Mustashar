package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    data class LawMeta(
        val id: String,
        val name: String,
        val category: String?,
        val drivePdfUrl: String? = null
    )

    data class Article(
        val lawId: String,
        val lawName: String,
        val number: String,
        val text: String,
        val keywords: List<String> = emptyList(),
        val drivePdfUrl: String? = null
    )

    private var cachedLaws: List<LawMeta>? = null
    private var lastLawsTime: Long = 0
    private const val TTL = 600_000L

    private fun normalizeDigits(s: String): String {
        val ar = "٠١٢٣٤٥٦٧٨٩"; val fa = "۰۱۲۳۴۵۶۷۸۹"; var r = s
        for (i in 0..9) {
            r = r.replace(ar[i], ('0' + i)).replace(fa[i], ('0' + i))
        }
        return r
    }

    private fun normalizeArabic(s: String): String =
        s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ة", "ه").replace("ى", "ي")

    private fun extractNumber(q: String): String? {
        val n = normalizeDigits(q)
        val patterns = listOf(
            Regex("""(?:المادة|الماده)\s*رقم\s*[:\(]?\s*(\d+)"""),
            Regex("""نص\s+(?:المادة|الماده)\s*[:\(]?\s*(\d+)"""),
            Regex("""(?:المادة|الماده)\s*[:\(]?\s*(\d+)\s*\)?"""),
            Regex("""\b(?:مادة|ماده)\s*[:\(]?\s*(\d+)"""),
            Regex("""article\s*[:\(]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            p.find(n)?.let { return it.groupValues[1] }
        }
        val bare = n.trim()
        if (bare.matches(Regex("""\d+"""))) return bare
        return null
    }

    suspend fun loadLawsList(): List<LawMeta> {
        val now = System.currentTimeMillis()
        cachedLaws?.let { if (now - lastLawsTime < TTL) return it }
        return try {
            val db = FirebaseFirestore.getInstance()
            val snap = db.collection("laws").get().await()
            val list = snap.documents.map {
                val title = it.getString("name") ?: it.getString("shortTitle") ?: it.getString("title") ?: it.id
                LawMeta(it.id, title, it.getString("category"), it.getString("drivePdfUrl"))
            }.sortedBy { it.name }
            cachedLaws = list
            lastLawsTime = now
            list
        } catch (_: Exception) {
            cachedLaws ?: emptyList()
        }
    }

    private fun detectLawId(question: String, laws: List<LawMeta>): String? {
        val qn = normalizeArabic(question)
        var best: LawMeta? = null
        var bestScore = 0
        for (law in laws) {
            val words = normalizeArabic(law.name).split(" ", "(", ")").filter { it.length >= 3 && !isStop(it) }
            var score = 0
            for (w in words) {
                if (qn.contains(w)) score++
            }
            if (score > bestScore) {
                bestScore = score
                best = law
            }
        }
        return if (bestScore > 0) best?.id else null
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

        // مطابقة القانون بالاسم تلقائياً
        var effectiveLawId = targetLawId
        if (effectiveLawId == null && query.isNotEmpty()) {
            val matchedLaw = laws.find { law ->
                query.contains(law.name, ignoreCase = true) ||
                law.name.contains(query.replace("كامل", "").replace("كاملا", "").trim(), ignoreCase = true)
            }
            if (matchedLaw != null) {
                effectiveLawId = matchedLaw.id
            }
        }

        // 1. عرض القانون كاملاً إذا:
        // - تم اختيار قانون والبحث فارغ
        // - أو تم كتابة كلمة كامل/كاملا
        // - أو كتب المستخدم اسم القانون مباشرة (مثل: القانون المدني) دون أرقام أو كلمات تخصصية
        val hasDigits = query.any { it.isDigit() }
        val isExplicitLawNameOnly = effectiveLawId != null && !hasDigits &&
            (query.isEmpty() || query.contains("كامل") || query.contains("كاملا") ||
             laws.find { it.id == effectiveLawId }?.name?.let { query.replace(" ", "").contains(it.replace(" ", "")) } == true)

        if (effectiveLawId != null && isExplicitLawNameOnly) {
            val allArticles = loadAllArticles(laws).filter { it.lawId == effectiveLawId }
                .sortedBy { it.number.toIntOrNull() ?: 9999 }
            if (allArticles.isNotEmpty()) {
                return allArticles.joinToString("\n\n───────────────────────\n\n") { a ->
                    formatOutput(a.lawName, a.number, a.text, a.drivePdfUrl)
                }
            }
        }

        // 2. البحث برقم المادة
        val digits = query.filter { it.isDigit() }
        if (digits.isNotEmpty() && (query.length <= 5 || query.contains("مادة") || query.contains("المادة"))) {
            val res = fetchByNumber(digits, effectiveLawId, laws)
            if (!res.isNullOrBlank()) return res
        }

        // 3. البحث الموضوعي / السياقي
        val cleanTerms = query.split(" ")
            .map { it.trim() }
            .filter { it.length > 1 && !it.all { ch -> ch.isDigit() } && it !in listOf("قانون", "القانون", "كامل", "كاملا") }

        val allArticles = loadAllArticles(laws)
        val pool = if (effectiveLawId != null) allArticles.filter { it.lawId == effectiveLawId } else allArticles

        val scored = pool.mapNotNull { a ->
            var score = 0
            for (term in cleanTerms) {
                if (a.text.contains(term, ignoreCase = true)) score += 4
                if (a.keywords.any { it.contains(term, ignoreCase = true) }) score += 6
                if (a.lawName.contains(term, ignoreCase = true)) score += 2
            }
            if (score > 0) Pair(a, score) else null
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) {
            return "⚠️ لم يتم العثور على نص مطابق لهذا البحث."
        }

        return scored.take(30).joinToString("\n\n───────────────────────\n\n") { (a, _) ->
            formatOutput(a.lawName, a.number, a.text, a.drivePdfUrl)
        }
    }

    private suspend fun fetchByNumber(n: String, targetLawId: String?, laws: List<LawMeta>): String? {
        val db = FirebaseFirestore.getInstance()
        return try {
            if (targetLawId != null) {
                val lawMeta = laws.find { it.id == targetLawId } ?: return null
                val doc = db.collection("laws").document(targetLawId).collection("articles").document(n).get().await()
                if (!doc.exists()) return null
                val text = pickText(doc.data ?: emptyMap()) ?: return null
                val pdf = doc.getString("drivePdfUrl") ?: lawMeta.drivePdfUrl
                return formatOutput(lawMeta.name, n, text, pdf)
            }

            val hits = mutableListOf<String>()
            for (law in laws) {
                val doc = db.collection("laws").document(law.id).collection("articles").document(n).get().await()
                if (doc.exists()) {
                    val text = pickText(doc.data ?: emptyMap())
                    if (!text.isNullOrBlank()) {
                        val pdf = doc.getString("drivePdfUrl") ?: law.drivePdfUrl
                        hits.add(formatOutput(law.name, n, text, pdf))
                    }
                }
            }
            if (hits.isEmpty()) null else hits.joinToString("\n\n───────────────────────\n\n")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadAllArticles(laws: List<LawMeta>): List<Article> {
        val res = mutableListOf<Article>()
        try {
            val db = FirebaseFirestore.getInstance()
            for (law in laws) {
                val snap = db.collection("laws").document(law.id).collection("articles").get().await()
                for (doc in snap.documents) {
                    val data = doc.data ?: continue
                    val txt = pickText(data) ?: continue
                    val kws = (data["keywords"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    val pdf = doc.getString("drivePdfUrl") ?: law.drivePdfUrl
                    res.add(Article(law.id, law.name, doc.id, txt, kws, pdf))
                }
            }
        } catch (_: Exception) {}
        return res
    }

    private fun formatOutput(name: String, num: String, text: String, pdf: String?): String {
        val pdfStr = if (!pdf.isNullOrBlank()) "\n\n🔗 رابط ملف القانون الأصلي (Drive):\n$pdf" else ""
        return "📖 $name - المادة $num:\n\n$text$pdfStr"
    }

    private fun isStop(w: String) = setOf("من", "في", "على", "عن", "إلى", "قانون", "مادة", "المادة", "رقم").contains(w)
}
