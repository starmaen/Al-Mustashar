package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * النسخة الموحّدة المحدثة لمنطق البحث القانوني فائق السرعة والدقة.
 */
object LawsRepository {
    private var cached: List<Article>? = null
    private var lastTime: Long = 0
    private const val TTL = 600_000L // 10 دقائق

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
        val normalized = q.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ة", "ه")
        return when {
            normalized.contains("جزاي") || normalized.contains("اصول الجزاي") -> "criminal_procedures_law_112"
            normalized.contains("مدني") && !normalized.contains("اصول") -> "civil_code"
            normalized.contains("اصول مدني") || normalized.contains("محاكمات مدني") -> "civil_procedure"
            normalized.contains("محاماه") || normalized.contains("محامي") -> "lawyers_law"
            normalized.contains("عقوبات") -> "penal_code"
            normalized.contains("احوال شخصي") -> "personal_status"
            normalized.contains("بينات 2014") || normalized.contains("بينات جديد") -> "evidence_law_2014"
            normalized.contains("بينات") -> "evidence_law"
            else -> null
        }
    }

    suspend fun searchRelevantLaws(question: String, limit: Int = 6): String {
        val num = extractNumber(question)
        val lawId = detectLawId(question)

        // 1. إذا كان السؤال يحتوي على رقم مادة
        if (num != null) {
            val d = fetchByNumber(num, lawId)
            if (d != null) return d
            return "⚠️ المادة $num غير موجودة في قاعدة البيانات الحالية."
        }

        // 2. البحث النصي الموضوعي
        val all = loadAll()
        if (all.isEmpty()) return "⚠️ لم يتم العثور على نتائج (قاعدة البيانات فارغة أو تعذر الاتصال)."

        val kws = normalizeDigits(question)
            .split(" ", "،", "؟", "?", ".", ",", "\n", "\t", ":", ";", "\"", "'")
            .map { it.trim() }
            .filter { it.length >= 2 && !isStop(it) }
            .distinct()

        if (kws.isEmpty()) return "⚠️ لم يتم التعرف على كلمات بحث واضحة بالسؤال."

        val scored = all.mapNotNull { a ->
            if (lawId != null && a.lawId != lawId) return@mapNotNull null
            var s = 0
            for (k in kws) {
                if (a.text.contains(k, true)) s += 5
                if (a.keywords.any { it.contains(k, true) }) s += 3
                if (a.law.contains(k, true)) s += 2
            }
            if (s > 0) a to s else null
        }.sortedByDescending { it.second }.take(limit)

        if (scored.isEmpty()) {
            return "⚠️️ لم يتم العثور على نص مطابق لهذا السؤال ضمن القوانين المتاحة حاليًا."
        }

        return scored.joinToString("\n\n") { (a, _) -> "📖 ${a.law} - المادة ${a.number}:\n${a.text}" }
    }

    private suspend fun fetchByNumber(n: String, targetLawId: String?): String? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val hits = mutableListOf<String>()

            if (targetLawId != null) {
                val doc = db.collection("laws").doc(targetLawId).collection("articles").document(n).get().await()
                if (doc.exists()) {
                    val lawDoc = db.collection("laws").doc(targetLawId).get().await()
                    val lawName = lawDoc.getString("name") ?: targetLawId
                    val t = doc.getString("text") ?: doc.getString("content")
                    val nn = doc.getLong("number")?.toString() ?: doc.getString("number") ?: n
                    if (!t.isNullOrEmpty()) {
                        return "📖 $lawName - المادة $nn:\n\n$t"
                    }
                }
            }

            for (lawDoc in db.collection("laws").get().await()) {
                val lawName = lawDoc.getString("name") ?: lawDoc.id
                val doc = lawDoc.reference.collection("articles").document(n).get().await()
                if (doc.exists()) {
                    val t = doc.getString("text") ?: doc.getString("content")
                    val nn = doc.getLong("number")?.toString() ?: doc.getString("number") ?: n
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
            for (lawDoc in db.collection("laws").get().await()) {
                val lawName = lawDoc.getString("name") ?: lawDoc.id
                val snap = lawDoc.reference.collection("articles").get().await()
                if (snap.isEmpty) continue
                for (d in snap.documents) {
                    val num = d.getLong("number")?.toString() ?: d.getString("number") ?: d.id
                    val txt = d.getString("text") ?: d.getString("content") ?: ""
                    val kws = (d.get("keywords") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    if (txt.isNotEmpty()) res.add(Article(lawName, lawDoc.id, num, txt, kws))
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
