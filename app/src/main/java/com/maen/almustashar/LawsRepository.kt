package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {
    private var cached: List<Article>? = null
    private var lastTime: Long = 0
    private const val TTL = 600_000L

    data class Article(
        val law: String,
        val lawId: String,
        val number: String,
        val text: String,
        val keywords: List<String> = emptyList()
    )

    private fun normalizeDigits(s: String): String {
        val ar = "٠١٢٣٤٥٦٧٨٩"; val fa = "۰۱۲۳۴۵۶۷۸۹"; var r = s
        for (i in 0..9) {
            r = r.replace(ar[i], ('0' + i)).replace(fa[i], ('0' + i))
        }
        return r
    }

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

    private fun detectLawId(q: String): String? {
        val s = q.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ة", "ه")
        return when {
            s.contains("جزاي") || s.contains("اصول الجزاي") -> "criminal_procedure"
            s.contains("اصول مدني") || s.contains("محاكمات مدني") -> "civil_procedure"
            s.contains("مدني") -> "civil_code"
            s.contains("محاماه") || s.contains("محامي") -> "lawyers_law"
            s.contains("عقوبات") -> "penal_code"
            s.contains("احوال شخصي") -> "personal_status"
            s.contains("بينات 2014") || s.contains("بينات جديد") -> "evidence_law_2014"
            s.contains("بينات") -> "evidence_law"
            else -> null
        }
    }

    // التوافق مع أي استدعاء قديم للدالة
    suspend fun searchRelevantLaws(question: String, limit: Int = 6): String =
        searchRelevantLaws(question, null, limit)

    // البحث القانوني المباشر من Firestore فقط
    suspend fun searchRelevantLaws(
        question: String,
        selectedLawId: String?,
        limit: Int = 6
    ): String {
        val num = extractNumber(question)
        val targetLawId = selectedLawId ?: detectLawId(question)

        if (num != null) {
            val res = fetchByNumber(num, targetLawId)
            if (res != null) return res
            return "⚠️ المادة $num غير موجودة في قاعدة البيانات الحالية."
        }

        val all = loadAll()
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
                if (a.law.contains(k, true)) s += 2
            }
            if (s > 0) a to s else null
        }.sortedByDescending { it.second }.take(limit)

        if (scored.isEmpty()) {
            return "⚠️ لم يتم العثور على نص مطابق لهذا السؤال ضمن القوانين المتاحة حاليًا."
        }

        return scored.joinToString("\n\n") { (a, _) -> "📖 ${a.law} - المادة ${a.number}:\n${a.text}" }
    }

    private suspend fun fetchByNumber(n: String, targetLawId: String?): String? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val hits = mutableListOf<String>()

            if (targetLawId != null) {
                val lawSnapshot = db.collection("laws").doc(targetLawId).get().await()
                val lawName = lawSnapshot.getString("name") ?: targetLawId
                val singleArticle = db.collection("laws").doc(targetLawId).collection("articles").document(n).get().await()
                if (singleArticle.exists()) {
                    val t = singleArticle.getString("text") ?: singleArticle.getString("content")
                    val nn = singleArticle.getLong("number")?.toString() ?: singleArticle.getString("number") ?: n
                    if (!t.isNullOrEmpty()) {
                        return "📖 $lawName - المادة $nn:\n\n$t"
                    }
                }
            }

            // عند اختيار قانون محدد، لا نبحث في قوانين أخرى
            if (targetLawId != null) return null

            val lawsSnap = db.collection("laws").get().await()
            for (lawSnapshot in lawsSnap.documents) {
                val lawName = lawSnapshot.getString("name") ?: lawSnapshot.id
                val loopArticle = lawSnapshot.reference.collection("articles").document(n).get().await()
                if (loopArticle.exists()) {
                    val t = loopArticle.getString("text") ?: loopArticle.getString("content")
                    val nn = loopArticle.getLong("number")?.toString() ?: loopArticle.getString("number") ?: n
                    if (!t.isNullOrEmpty()) {
                        hits.add("📖 $lawName - المادة $nn:\n\n$t")
                    }
                }
            }

            if (hits.isEmpty()) null else hits.joinToString("\n\n───────────────────────\n\n")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadAll(): List<Article> {
        val now = System.currentTimeMillis()
        cached?.let { if (now - lastTime < TTL) return it }
        val res = mutableListOf<Article>()
        try {
            val db = FirebaseFirestore.getInstance()
            val lawsSnap = db.collection("laws").get().await()
            for (lawSnapshot in lawsSnap.documents) {
                val lawName = lawSnapshot.getString("name") ?: lawSnapshot.id
                val snap = lawSnapshot.reference.collection("articles").get().await()
                if (snap.isEmpty) continue
                for (articleSnapshot in snap.documents) {
                    val num = articleSnapshot.getLong("number")?.toString() ?: articleSnapshot.getString("number") ?: articleSnapshot.id
                    val txt = articleSnapshot.getString("text") ?: articleSnapshot.getString("content") ?: ""
                    val kws = (articleSnapshot.get("keywords") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    if (txt.isNotEmpty()) res.add(Article(lawName, lawSnapshot.id, num, txt, kws))
                }
            }
            cached = res
            lastTime = now
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
        cached = null
        lastTime = 0
    }
}
