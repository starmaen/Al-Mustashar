package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    data class LawMeta(
        val id: String,
        val name: String,
        val drivePdfUrl: String?
    )

    data class ArticleMeta(
        val lawId: String,
        val lawName: String,
        val number: String,
        val text: String,
        val drivePdfUrl: String?
    )

    private var lawsCache: List<LawMeta>? = null

    suspend fun loadLawsList(): List<LawMeta> {
        lawsCache?.let { return it }
        val db = FirebaseFirestore.getInstance()
        val snapshot = db.collection("laws").get().await()
        val list = snapshot.documents.map { doc ->
            LawMeta(
                id = doc.id,
                name = doc.getString("name") ?: doc.id,
                drivePdfUrl = doc.getString("drivePdfUrl")
            )
        }
        lawsCache = list
        return list
    }

    private suspend fun loadAllArticles(laws: List<LawMeta>): List<ArticleMeta> {
        val db = FirebaseFirestore.getInstance()
        val result = mutableListOf<ArticleMeta>()
        for (law in laws) {
            val articlesSnap = db.collection("laws").document(law.id).collection("articles").get().await()
            for (art in articlesSnap.documents) {
                val text = pickText(art.data ?: emptyMap()) ?: continue
                val pdf = art.getString("drivePdfUrl") ?: law.drivePdfUrl
                result.add(
                    ArticleMeta(
                        lawId = law.id,
                        lawName = law.name,
                        number = art.id,
                        text = text,
                        drivePdfUrl = pdf
                    )
                )
            }
        }
        return result
    }

    private fun normDigits(s: String): String {
        val eastern = "٠١٢٣٤٥٦٧٨٩"
        var r = s
        for (i in eastern.indices) r = r.replace(eastern[i], "0123456789"[i])
        return r
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
        val query = normDigits(rawQuery.trim())
        val laws = loadLawsList()

        fun norm(s: String): String {
            return s.lowercase()
                .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ٱ", "ا")
                .replace("ة", "ه").replace("ى", "ي").replace("ؤ", "و").replace("ئ", "ي")
                .replace("السوري", "").replace("سوري", "")
                .replace(Regex("[^a-zA-Z0-9\u0621-\u064A]"), "")
        }

        val normQ = norm(query)
        val hasLawWord = query.contains("قانون") || query.contains("القانون")
        val hasArticleWord = query.contains("ماده") || query.contains("مادة") ||
            query.contains("الماده") || query.contains("المادة")
        val wantsFull = query.contains("كامل") || query.contains("كاملا") || query.contains("الكامل")

        val stopWords = setOf(
            "قانون", "القانون", "السوري", "سوري", "كامل", "كاملا", "الكامل",
            "مادة", "المادة", "ماده", "الماده", "رقم", "عام", "لعام", "سنة", "لسنة", "من", "في"
        )
        val coreWords = query.split(Regex("\\s+")).map { it.trim() }
            .filter { it.length > 1 && !it.all { ch -> ch.isDigit() } && it !in stopWords }

        // 1. التعرف على القانون (قوي: احتواء كامل — ضعيف: كلمة جوهرية للتضييق فقط)
        var identifiedLawId = targetLawId
        var strongLawMatch = targetLawId != null
        if (identifiedLawId == null && normQ.isNotEmpty()) {
            for (law in laws) {
                val nl = norm(normDigits(law.name))
                if (nl.isNotEmpty() && (normQ.contains(nl) || nl.contains(normQ))) {
                    identifiedLawId = law.id
                    strongLawMatch = true
                    break
                }
            }
            if (identifiedLawId == null) {
                var best: LawMeta? = null
                var bestLen = 0
                for (law in laws) {
                    val nl = norm(normDigits(law.name))
                    for (w in coreWords) {
                        val nw = norm(w)
                        if (nw.length >= 4 && nl.contains(nw) && nw.length > bestLen) {
                            bestLen = nw.length
                            best = law
                        }
                    }
                }
                identifiedLawId = best?.id
            }
        }

        // 2. استخراج رقم المادة: المجاور لكلمة مادة أولاً، ثم الرقم الحر
        //    (بعد حذف اسم القانون والسنوات حتى لا يُلتقط رقم القانون أو السنة)
        val articleAdjRegex = Regex("(?:الماد[ةه]|ماد[ةه])\\s*\\(?\\s*(\\d{1,4})\\s*\\)?")
        var extractedNumber = articleAdjRegex.find(query)?.groupValues?.get(1)
        if (extractedNumber == null) {
            val رقمAdj = Regex("رقم\\s*\\(?\\s*(\\d{1,4})\\s*\\)?").find(query)?.groupValues?.get(1)
            // "رقم" مع كلمة قانون وبدون كلمة مادة = رقم القانون نفسه (84) وليس مادة
            if (!(رقمAdj != null && hasLawWord && !hasArticleWord)) {
                extractedNumber = رقمAdj
            }
        }
        if (extractedNumber == null) {
            var tmp = query
            laws.find { it.id == identifiedLawId }?.let { tmp = tmp.replace(it.name, " ") }
            tmp = tmp.replace(Regex("(19|20)\\d{2}"), " ")
            extractedNumber = Regex("(\\d{1,4})").find(tmp)?.value
        }

        // الحالة 1: رقم + اسم قانون محدد
        if (!extractedNumber.isNullOrEmpty() && identifiedLawId != null) {
            val res = fetchArticleFromLaw(extractedNumber, identifiedLawId, laws)
            if (!res.isNullOrBlank()) return res
        }

        // الحالة 2: رقم فقط (جلب المادة من جميع القوانين)
        if (!extractedNumber.isNullOrEmpty() && (hasArticleWord || query.length <= 6)) {
            val res = fetchArticleFromAllLaws(extractedNumber, laws)
            if (!res.isNullOrBlank()) return res
        }

        // الحالة 3: عرض القانون كاملاً — فقط عند تطابق قوي على الاسم وبدون رقم مادة
        //    (كلمات: القانون كذا / القانون كذا كاملاً — بأي ترتيب)
        val isFullLawRequest = identifiedLawId != null && extractedNumber.isNullOrEmpty() &&
            (strongLawMatch || wantsFull || targetLawId != null)

        if (identifiedLawId != null && isFullLawRequest) {
            val allArticles = loadAllArticles(laws).filter { it.lawId == identifiedLawId }
                .sortedBy { it.number.toIntOrNull() ?: 9999 }
            if (allArticles.isNotEmpty()) {
                return allArticles.joinToString("\n\n───────────────────────\n\n") { a ->
                    formatOutput(a.lawName, a.number, a.text, a.drivePdfUrl)
                }
            }
        }

        // الحالة 4: بحث سياقي وموضوعي
        val cleanTerms = query.split(" ")
            .map { it.trim() }
            .filter { it.length > 1 && !it.all { ch -> ch.isDigit() } && it !in listOf("قانون", "القانون", "كامل", "كاملا", "السوري", "مادة", "المادة", "ماده", "الماده") }

        val allArticles = loadAllArticles(laws)
        val pool = if (identifiedLawId != null) allArticles.filter { it.lawId == identifiedLawId } else allArticles

        val scored = pool.mapNotNull { art ->
            val nText = norm(art.text)
            var score = 0
            for (term in cleanTerms) {
                val nt = norm(term)
                if (nt.isNotEmpty() && nText.contains(nt)) score += 3
            }
            if (score > 0) Pair(art, score) else null
        }.sortedByDescending { it.second }

        // إن كان التضييق بقانون (مطابقة ضعيفة) ولم يعطِ شيئاً — أعد البحث في كل القوانين
        val finalScored = if (scored.isEmpty() && identifiedLawId != null && !strongLawMatch) {
            allArticles.mapNotNull { art ->
                val nText = norm(art.text)
                var score = 0
                for (term in cleanTerms) {
                    val nt = norm(term)
                    if (nt.isNotEmpty() && nText.contains(nt)) score += 3
                }
                if (score > 0) Pair(art, score) else null
            }.sortedByDescending { it.second }
        } else scored

        if (finalScored.isEmpty()) {
            return "⚠️ لم يتم العثور على نص مطابق لهذا البحث."
        }

        return finalScored.take(25).joinToString("\n\n───────────────────────\n\n") { (a, _) ->
            formatOutput(a.lawName, a.number, a.text, a.drivePdfUrl)
        }
    }

    private suspend fun fetchArticleFromLaw(n: String, lawId: String, laws: List<LawMeta>): String? {
        val db = FirebaseFirestore.getInstance()
        return try {
            val lawMeta = laws.find { it.id == lawId } ?: return null
            val doc = db.collection("laws").document(lawId).collection("articles").document(n).get().await()
            if (doc.exists()) {
                val text = pickText(doc.data ?: emptyMap()) ?: return null
                val pdf = doc.getString("drivePdfUrl") ?: lawMeta.drivePdfUrl
                formatOutput(lawMeta.name, n, text, pdf)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchArticleFromAllLaws(n: String, laws: List<LawMeta>): String? {
        val db = FirebaseFirestore.getInstance()
        val results = mutableListOf<String>()
        for (law in laws) {
            try {
                val doc = db.collection("laws").document(law.id).collection("articles").document(n).get().await()
                if (doc.exists()) {
                    val text = pickText(doc.data ?: emptyMap()) ?: continue
                    val pdf = doc.getString("drivePdfUrl") ?: law.drivePdfUrl
                    results.add(formatOutput(law.name, n, text, pdf))
                }
            } catch (_: Exception) {}
        }
        return if (results.isNotEmpty()) results.joinToString("\n\n═══════════════════════\n\n") else null
    }

    private fun formatOutput(lawName: String, number: String, text: String, pdfUrl: String?): String {
        val sb = StringBuilder()
        sb.append("📖 $lawName - المادة $number:\n\n")
        sb.append(text)
        if (!pdfUrl.isNullOrBlank()) {
            sb.append("\n\n🔗 رابط المستند: $pdfUrl")
        }
        return sb.toString()
    }
}
