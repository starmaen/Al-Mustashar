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
        return r.replace('ة', 'ه')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
    }

    private fun extractNumber(q: String): String? {
        val n = normalize(q)
        val pats = listOf(
            Regex("""الماده\s*رقم\s*[:\(]?\s*(\d+)"""),
            Regex("""نص\s+الماده\s*[:\(]?\s*(\d+)"""),
            Regex("""الماده\s*[:\(]?\s*(\d+)"""),
            Regex("""\bماده\s*[:\(]?\s*(\d+)"""),
            Regex("""\bرقم\s*[:\(]?\s*(\d+)"""),
            Regex("""article\s*[:\(]?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d+)\b""")
        )
        for (p in pats) {
            p.find(n)?.let { return it.groupValues[1] }
        }
        return null
    }

    // ============ الدالة الرئيسية ============
    suspend fun searchRelevantLaws(question: String, limit: Int = 6): String {
        val num = extractNumber(question)
        if (num != null) {
            val direct = fetchArticleByNumber(num, question)
            if (direct.isNotBlank() && !direct.startsWith("⚠️")) {
                return direct
            }
        }
        return searchByKeywords(question, limit)
    }

    // ============ البحث برقم المادة في القوانين ============
    private suspend fun fetchArticleByNumber(num: String, question: String = ""): String {
        val db = FirebaseFirestore.getInstance()
        val results = mutableListOf<String>()
        val qNorm = normalize(question)

        try {
            val lawsSnapshot = db.collection("laws").get().await()
            for (lawDoc in lawsSnapshot.documents) {
                val lawId = lawDoc.id
                val lawName = lawDoc.getString("name") ?: lawId
                val category = lawDoc.getString("category") ?: ""

                // إذا حدد المستخدم قانوناً معيناً، نعطي الأولوية أو نقتصر عليه
                val matchesLaw = when {
                    qNorm.contains("عقوبات") || qNorm.contains("جزائي") -> lawId.contains("penal") || category.contains("جزائي")
                    qNorm.contains("مدني") -> lawId.contains("civil") || category.contains("مدني")
                    qNorm.contains("احوال") || qNorm.contains("شخصي") -> lawId.contains("personal") || category.contains("احوال")
                    else -> true
                }

                for (col in listOf("articles", "المواد", "مقالات")) {
                    try {
                        val doc = lawDoc.reference.collection(col).document(num).get().await()
                        if (doc.exists()) {
                            val block = buildArticleBlock(lawName, category, num, doc)
                            if (block.isNotEmpty()) {
                                if (matchesLaw) {
                                    results.add(0, block) // الأولوية للقانون المطابق
                                } else {
                                    results.add(block)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return if (results.isEmpty()) {
            ""
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
        val effective = doc.getString("effectiveText") ?: doc.getString("text") ?: doc.getString("currentText") ?: ""
        val original = doc.getString("originalText") ?: effective
        val year = doc.getLong("year")?.toString() ?: "1949"

        if (effective.isEmpty() && original.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("⚖️ المادة $num — $lawName\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("📜 [1] النص الأصلي ($year):\n")
        sb.append(original.trim()).append("\n\n")

        sb.append("📝 [2] التعديلات:\n")
        val isAmended = doc.getBoolean("isAmended") ?: false
        if (isAmended) {
            sb.append("• تم تعديل هذه المادة وفق القوانين النافذة اللاحقة.\n\n")
        } else {
            sb.append("• لا توجد تعديلات (النص باقٍ على أصله).\n\n")
        }

        sb.append("✅ [3] النص النافذ المعتمد أصولاً:\n")
        sb.append(effective.trim())

        return sb.toString()
    }

        private fun isStop(w: String): Boolean {
        val stops = setOf(
            "من", "في", "على", "الى", "إلى", "عن", "مع", "ما", "هو", "هي", "هذا", "هذه", "تلك", "ذلك",
            "قانون", "القانون", "الماده", "المادة", "ماده", "مادة", "المواد", "مواد", "المتعلقه", "المتعلقة",
            "احكام", "أحكام", "حكم", "نص", "رقم", "سنة", "لسنة", "عام", "سوريا", "السوري", "السورية",
            "شأن", "بشأن", "حول", "كيف", "متى", "هل", "بين", "أو", "و"
        )
        return w in stops
    }

        private suspend fun searchByKeywords(question: String, limit: Int): String {
        val all = loadAll()
        if (all.isEmpty()) return ""

        val rawKws = normalize(question)
            .split(" ", "،", "؟", "?", ".", ",", "
", "	", ":", ";", "\"", "'")
            .map { it.trim() }
            .filter { it.length >= 3 && !isStop(it) }
            .distinct()

        if (rawKws.isEmpty()) return ""

        // احتساب التطابق فقط على الكلمات الجوهرية
        val scored = all.map { a ->
            var s = 0
            val fullText = normalize("${a.lawName} ${a.text} ${a.originalText} ${a.keywords.joinToString(" ")}")
            var matchedKeywordsCount = 0
            for (k in rawKws) {
                if (fullText.contains(k)) {
                    s += 10
                    matchedKeywordsCount++
                }
            }
            // استبعاد أي مادة لا تحتوي على الكلمات الأساسية فعلياً
            val finalScore = if (matchedKeywordsCount > 0) s else 0
            a to finalScore
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)

        if (scored.isEmpty()) {
            return "⚠️ لم يتم العثور على مواد قانونية تحتوي على هذه الكلمات في قاعدة البيانات المرفوعة."
        }

        return scored.joinToString("

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

") { (a, _) ->
            "📖 ${a.lawName} (${a.category}) - المادة ${a.number}:
${a.text}"
        }
    }

    // ============ تحميل كل المواد (Cache) ============
    private suspend fun loadAll(): List<Article> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cacheTime < TTL) return it }

        val result = mutableListOf<Article>()
        try {
            val db = FirebaseFirestore.getInstance()
            val lawsSnapshot = db.collection("laws").get().await()
            for (lawDoc in lawsSnapshot.documents) {
                val lawId = lawDoc.id
                val lawName = lawDoc.getString("name") ?: lawId
                val category = lawDoc.getString("category") ?: ""

                val articlesSnapshot = lawDoc.reference.collection("articles").get().await()
                for (artDoc in articlesSnapshot.documents) {
                    val num = artDoc.getString("id") ?: artDoc.getLong("number")?.toString() ?: artDoc.id
                    val eff = artDoc.getString("effectiveText") ?: artDoc.getString("text") ?: ""
                    val orig = artDoc.getString("originalText") ?: eff
                    val status = artDoc.getString("status") ?: "سارية"
                    @Suppress("UNCHECKED_CAST")
                    val kws = artDoc.get("keywords") as? List<String> ?: emptyList()

                    result.add(
                        Article(
                            lawId = lawId,
                            lawName = lawName,
                            category = category,
                            number = num,
                            text = eff,
                            originalText = orig,
                            status = status,
                            amendments = emptyList(),
                            keywords = kws
                        )
                    )
                }
            }
            cache = result
            cacheTime = now
        } catch (_: Exception) {}

        return result
    }
}
