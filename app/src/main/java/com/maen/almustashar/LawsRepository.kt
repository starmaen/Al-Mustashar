package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    data class LawArticle(
        val id: String = "",
        val number: Int = 0,
        val lawName: String = "",
        val category: String = "",
        val originalText: String = "",
        val effectiveText: String = "",
        val text: String = "",
        val isAmended: Boolean = false,
        val amendments: List<Map<String, Any>> = emptyList(),
        val keywords: List<String> = emptyList()
    )

    private val cachedLaws = mutableListOf<LawArticle>()

    private suspend fun loadAll(): List<LawArticle> {
        if (cachedLaws.isNotEmpty()) return cachedLaws
        val db = FirebaseFirestore.getInstance()
        val collections = listOf(
            "penal_code",
            "civil_code",
            "personal_status",
            "civil_procedure",
            "criminal_procedure"
        )

        for (col in collections) {
            try {
                val snapshot = db.collection("laws").document(col)
                    .collection("articles").get().await()

                for (doc in snapshot.documents) {
                    val num = doc.getLong("number")?.toInt()
                        ?: doc.getString("number")?.toIntOrNull()
                        ?: doc.id.toIntOrNull()
                        ?: 0

                    val lawName = doc.getString("lawName") ?: when (col) {
                        "penal_code" -> "قانون العقوبات السوري"
                        "civil_code" -> "القانون المدني السوري"
                        "personal_status" -> "قانون الأحوال الشخصية السوري"
                        "civil_procedure" -> "قانون أصول المحاكمات المدنية (القانون 1 لعام 2016)"
                        "criminal_procedure" -> "قانون أصول المحاكمات الجزائية (المرسوم 112 لعام 1950 وتعديلاته)"
                        else -> "التشريع السوري"
                    }

                    val cat = doc.getString("category") ?: when (col) {
                        "penal_code" -> "عقوبات"
                        "civil_code" -> "مدني"
                        "personal_status" -> "أحوال شخصية"
                        "civil_procedure" -> "أصول مدنية"
                        "criminal_procedure" -> "أصول جزائية"
                        else -> "عام"
                    }

                    val original = doc.getString("originalText") ?: ""
                    val effective = doc.getString("effectiveText") ?: doc.getString("text") ?: ""
                    val mainText = if (effective.isNotBlank()) effective else original
                    val isAmended = doc.getBoolean("isAmended") ?: false

                    @Suppress("UNCHECKED_CAST")
                    val amendments = (doc.get("amendments") as? List<Map<String, Any>>) ?: emptyList()

                    @Suppress("UNCHECKED_CAST")
                    val kws = (doc.get("keywords") as? List<String>) ?: emptyList()

                    cachedLaws.add(
                        LawArticle(
                            id = doc.id,
                            number = num,
                            lawName = lawName,
                            category = cat,
                            originalText = original,
                            effectiveText = effective,
                            text = mainText,
                            isAmended = isAmended,
                            amendments = amendments,
                            keywords = kws
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        return cachedLaws
    }

    private fun normalize(str: String): String {
        return str
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .trim()
            .lowercase()
    }

    private fun isStop(w: String): Boolean {
        val stops = setOf(
            "من", "في", "على", "الى", "إلى", "عن", "مع", "ما", "هو", "هي", "هذا", "هذه", "تلك", "ذلك",
            "قانون", "القانون", "الماده", "المادة", "ماده", "مادة", "المواد", "مواد", "المتعلقه", "المتعلقة",
            "احكام", "أحكام", "حكم", "نص", "رقم", "سنة", "لسنة", "عام", "سوريا", "السوري", "السورية",
            "شان", "بشان", "شأن", "بشأن", "حول", "كيف", "متى", "هل", "بين", "او", "أو", "و"
        )
        return w in stops
    }

    suspend fun searchRelevantLaws(query: String, limit: Int = 3): String {
        val all = loadAll()
        if (all.isEmpty()) return ""

        val normQuery = normalize(query)

        // 1. البحث الصريح برقم المادة مع اسم القانون
        val articleRegex = Regex("""(?:ماده|الماده|مادة|المادة)\s*(\d+)""")
        val match = articleRegex.find(normQuery)
        val targetNumber = match?.groupValues?.get(1)?.toIntOrNull()
            ?: normQuery.filter { it.isDigit() }.toIntOrNull()

        if (targetNumber != null && targetNumber > 0) {
            val byNumber = all.filter { it.number == targetNumber }
            if (byNumber.isNotEmpty()) {
                val matched = if (normQuery.contains("عقوب")) {
                    byNumber.firstOrNull { it.category == "عقوبات" } ?: byNumber.first()
                } else if (normQuery.contains("اصول جزائ") || normQuery.contains("اجراءات جزائ")) {
                    byNumber.firstOrNull { it.category == "أصول جزائية" } ?: byNumber.first()
                } else if (normQuery.contains("اصول مدن") || normQuery.contains("محاكمات مدن")) {
                    byNumber.firstOrNull { it.category == "أصول مدنية" } ?: byNumber.first()
                } else if (normQuery.contains("مدن")) {
                    byNumber.firstOrNull { it.category == "مدني" } ?: byNumber.first()
                } else if (normQuery.contains("احوال") || normQuery.contains("شخصي")) {
                    byNumber.firstOrNull { it.category == "أحوال شخصية" } ?: byNumber.first()
                } else {
                    byNumber.first()
                }
                return formatArticleDetailed(matched)
            }
        }

        // 2. توجيه القوانين التخصصية التي لم تُرفع بعد
        val specialLawsTerms = mapOf(
            "مخدر" to "قانون المخدرات السوري (القانون رقم 2 لعام 1993 وتعديلاته)",
            "مخدرات" to "قانون المخدرات السوري (القانون رقم 2 لعام 1993 وتعديلاته)",
            "مخدره" to "قانون المخدرات السوري (القانون رقم 2 لعام 1993 وتعديلاته)",
            "سلاح" to "قانون الأسلحة والذخائر السوري (المرسوم التشريعي 51 لعام 2001 وتعديلاته)",
            "اسلحه" to "قانون الأسلحة والذخائر السوري (المرسوم التشريعي 51 لعام 2001 وتعديلاته)",
            "اسلحة" to "قانون الأسلحة والذخائر السوري (المرسوم التشريعي 51 لعام 2001 وتعديلاته)",
            "سير" to "قانون السير والمركبات السوري",
            "مركبات" to "قانون السير والمركبات السوري",
            "معلوماتيه" to "قانون مكافحة الجرائم المعلوماتية (القانون رقم 20 لعام 2022)"
        )

        for ((term, lawTitle) in specialLawsTerms) {
            if (normQuery.contains(term)) {
                return "ℹ️️ تنبيه وإرشاد قانوني:\nمسألة ($term) ينظمها في سوريا تشريع خاص وهو:\n[$lawTitle].\n\nنصوص هذا القانون الخاص قيد الإدراج حالياً في قاعدة البيانات.\nيمكنك الحصول على التحليل القانوني والعقوبة فوراً عبر نافذة [الاستشارة القانونية] أو [بحث قانوني عام]."
            }
        }

        // 3. البحث بالكلمات المفتاحية الجوهرية
        val rawKws = normQuery
            .split(" ", "،", "؟", "?", ".", ",", "\n", "\t", ":", ";", "\"", "'")
            .map { it.trim() }
            .filter { it.length >= 3 && !isStop(it) }
            .distinct()

        if (rawKws.isEmpty()) {
            return "⚠️ يرجى كتابة اسم موضوع محدد أو رقم المادة للبحث."
        }

        val scored = all.mapNotNull { a ->
            val fullText = normalize("${a.lawName} ${a.text} ${a.originalText} ${a.keywords.joinToString(" ")}")
            var matchCount = 0
            for (k in rawKws) {
                if (fullText.contains(k)) matchCount++
            }
            val minMatches = if (rawKws.size >= 3) 2 else 1
            if (matchCount >= minMatches) a to matchCount else null
        }.sortedByDescending { it.second }
            .take(limit)

        if (scored.isEmpty()) {
            return "⚠️ لم يتم العثور على مواد قانونية مطابقة لهذه العبارة في قاعدة البيانات المرفوعة."
        }

        return scored.joinToString("\n\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n") { (a, _) ->
            formatArticleDetailed(a)
        }
    }

    private fun formatArticleDetailed(a: LawArticle): String {
        val amendText = if (a.isAmended && a.amendments.isNotEmpty()) {
            a.amendments.joinToString("\n") { m ->
                val law = m["law"] ?: ""
                val year = m["year"] ?: ""
                val change = m["change"] ?: ""
                "• $law ($year) — $change"
            }
        } else if (a.isAmended) {
            "• معدّلة بموجب التشريعات اللاحقة."
        } else {
            "• لم تُعدَّل"
        }

        val orig = if (a.originalText.isNotBlank()) a.originalText else a.text
        val eff = if (a.effectiveText.isNotBlank()) a.effectiveText else a.text

        return """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⚖️ المادة ${a.number} — ${a.lawName}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📜 [1] النص الأصلي:
$orig

📝 [2] التعديلات:
$amendText

✅ [3] النص النافذ المعتمد أصولاً:
$eff

🔖 الحالة: سارية
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()
    }
}
