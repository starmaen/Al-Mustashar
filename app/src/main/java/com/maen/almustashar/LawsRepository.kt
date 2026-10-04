package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    data class LawMeta(val id: String, val name: String, val category: String?)

    data class Article(
        val lawId: String,
        val lawName: String,
        val number: String,
        val text: String,
        val keywords: List<String> = emptyList()
    )

    private var cachedLaws: List<LawMeta>? = null
    private var lastLawsTime: Long = 0

    private var cachedArticles: List<Article>? = null
    private var lastArticlesTime: Long = 0

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

    // جلب قائمة القوانين ديناميكياً من قاعدة البيانات (أي قانون جديد يُضاف يظهر تلقائياً)
    suspend fun loadLawsList(): List<LawMeta> {
        val now = System.currentTimeMillis()
        cachedLaws?.let { if (now - lastLawsTime < TTL) return it }
        return try {
            val db = FirebaseFirestore.getInstance()
            val snap = db.collection("laws").get().await()
            val list = snap.documents.map {
                LawMeta(it.id, it.getString("name") ?: it.id, it.getString("category"))
            }.sortedBy { it.name }
            cachedLaws = list
            lastLawsTime = now
            list
        } catch (_: Exception) {
            cachedLaws ?: emptyList()
        }
    }

    // تحديد القانون من نص السؤال الحر عبر مطابقة اسم القانون الفعلي (وليس قائمة ثابتة)
    private fun detectLawId(question: String, laws: List<LawMeta>): String? {
        val qn = normalizeArabic(question)
        var best: LawMeta? = null
        var bestScore = 0
        for (law in laws) {
            val nameWords = normalizeArabic(law.name)
                .split(" ", "(", ")")
                .filter { it.length >= 3 && !isStop(it) }
            var score = 0
            for (w in nameWords) {
                if (qn.contains(w)) score++
            }
            if (score > bestScore) {
                bestScore = score
                best = law
            }
        }
        return if (bestScore > 0) best?.id else null
    }

    // استخراج نص المادة من أي حقل موجود فعلياً، مع أولوية للنص الحالي المعدّل
    private fun pickText(data: Map<String, Any?>): String? {
        val keys = listOf("currentText", "effectiveText", "text", "content", "originalText")
        for (k in keys) {
            val v = data[k] as? String
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    // التوافق مع أي استدعاء قديم للدالة بدون تحديد قانون
    suspend fun searchRelevantLaws(question: String, limit: Int = 6): String =
        searchRelevantLaws(question, null, limit)

    suspend fun searchRelevantLaws(
        question: String,
        selectedLawId: String?,
        limit: Int = 6
    ): String {
        val laws = loadLawsList()
        if (laws.isEmpty()) return "⚠️ لم يتم العثور على أي قوانين في قاعدة البيانات."

        val num = extractNumber(question)
        val targetLawId = selectedLawId ?: detectLawId(question, laws)

        if (num != null) {
            val res = fetchByNumber(num, targetLawId, laws)
            if (res != null) return res
            return if (targetLawId != null) {
                val lawName = laws.find { it.id == targetLawId }?.name ?: targetLawId
                "⚠️ المادة $num غير موجودة ضمن $lawName."
            } else {
                "⚠️ المادة $num غير موجودة في أي من القوانين المتاحة حاليًا."
            }
        }

        val all = loadAllArticles(laws)
        if (all.isEmpty()) return "⚠️ لم يتم العثور على نتائج (قاعدة البيانات فارغة أو تعذر الاتصال)."

        val kws = normalizeDigits(question)
            .split(" ", "،", "؟", "?", ".", ",", "\n", "\t", ":", ";", "\"", "'")
            .map { it.trim() }
            .filter { it.length >= 2 && !isStop(it) }
            .distinct()

        if (kws.isEmpty()) return "⚠️ لم يتم التعرف على كلمات بحث واضحة بالسؤال."

        val scored = all.mapNotNull { a ->
            if (targetLawId != null && a.lawId != targetLawId) return@mapNotNull null
            var s = 0
            for (k in kws) {
                if (a.text.contains(k, true)) s += 5
                if (a.keywords.any { it.contains(k, true) }) s += 3
                if (a.lawName.contains(k, true)) s += 2
            }
            if (s > 0) a to s else null
        }.sortedByDescending { it.second }.take(limit)

        if (scored.isEmpty()) {
            return if (targetLawId != null) {
                val lawName = laws.find { it.id == targetLawId }?.name ?: targetLawId
                "⚠️ لم يتم العثور على نص مطابق لهذا السؤال ضمن $lawName."
            } else {
                "⚠️ لم يتم العثور على نص مطابق لهذا السؤال ضمن القوانين المتاحة حاليًا."
            }
        }

        return scored.joinToString("\n\n") { (a, _) -> "📖 ${a.lawName} - المادة ${a.number}:\n${a.text}" }
    }

    private suspend fun fetchByNumber(n: String, targetLawId: String?, laws: List<LawMeta>): String? {
        val db = FirebaseFirestore.getInstance()
        return try {
            if (targetLawId != null) {
                val lawMeta = laws.find { it.id == targetLawId } ?: return null
                val doc = db.collection("laws").document(targetLawId)
                    .collection("articles").document(n).get().await()
                if (!doc.exists()) return null
                val text = pickText(doc.data ?: emptyMap())
                return if (!text.isNullOrBlank()) "📖 ${lawMeta.name} - المادة $n:\n\n$text" else null
            }

            val hits = mutableListOf<String>()
            for (law in laws) {
                val doc = db.collection("laws").document(law.id)
                    .collection("articles").document(n).get().await()
                if (doc.exists()) {
                    val text = pickText(doc.data ?: emptyMap())
                    if (!text.isNullOrBlank()) {
                        hits.add("📖 ${law.name} - المادة $n:\n\n$text")
                    }
                }
            }
            if (hits.isEmpty()) null else hits.joinToString("\n\n───────────────────────\n\n")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadAllArticles(laws: List<LawMeta>): List<Article> {
        val now = System.currentTimeMillis()
        cachedArticles?.let { if (now - lastArticlesTime < TTL) return it }
        val res = mutableListOf<Article>()
        try {
            val db = FirebaseFirestore.getInstance()
            for (law in laws) {
                val snap = db.collection("laws").document(law.id).collection("articles").get().await()
                for (doc in snap.documents) {
                    val data = doc.data ?: continue
                    val txt = pickText(data) ?: continue
                    if (txt.isBlank()) continue
                    val kws = (data["keywords"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    val numStr = (data["number"] as? Long)?.toString()
                        ?: (data["number"] as? String)
                        ?: doc.id
                    res.add(Article(law.id, law.name, numStr, txt, kws))
                }
            }
            cachedArticles = res
            lastArticlesTime = now
        } catch (_: Exception) {}
        return res
    }

    private fun isStop(w: String) = setOf(
        "من", "في", "على", "عن", "إلى", "التي", "الذي", "هذا", "هذه", "ذلك", "تلك",
        "أريد", "أحتاج", "أطلب", "نص", "رقم", "هو", "هي", "ما", "لا", "مع", "بين",
        "عند", "حتى", "قد", "كان", "لكن", "أو", "ثم", "كل", "بعض", "مادة", "المادة",
        "ماده", "الماده", "القانون", "قانون"
    ).contains(w)

    fun clearCache() {
        cachedArticles = null
        lastArticlesTime = 0
        cachedLaws = null
        lastLawsTime = 0
    }
}
