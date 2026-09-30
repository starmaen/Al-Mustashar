package com.maen.almustashar

import android.content.Context
import org.json.JSONArray
import java.io.InputStreamReader

object LawsRepository {

    private fun normalizeArabic(text: String): String {
        return text.trim().lowercase()
            .replace(Regex("[أإآ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .replace(Regex("[\\p{Punct}\\s]+"), " ")
    }

    fun searchRelevantLaws(context: Context?, query: String): String {
        val rawQ = query.trim()
        if (rawQ.isBlank()) return "يرجى إدخال عبارة البحث."

        val normQ = normalizeArabic(rawQ)
        val stopWords = setOf("في", "من", "على", "الى", "إلى", "عن", "مع", "أو", "او", "ما", "هو", "هي", "المتعلقه", "المتعلقة", "بشان", "بخصوص", "قانون")
        val queryWords = normQ.split(" ").filter { it.length > 1 && !stopWords.contains(it) }

        // استخراج رقم المادة إن وجد (مثل: مادة 65)
        val articleMatch = Regex("""(?:ماده|الماده)\s*(\d+)""").find(normQ)
        val targetArticle = articleMatch?.groupValues?.get(1)

        if (context != null) {
            try {
                val inputStream = context.assets.open("laws_data.json")
                val reader = InputStreamReader(inputStream, "UTF-8")
                val jsonText = reader.readText()
                reader.close()

                val jsonArray = JSONArray(jsonText)
                val matchedArticles = mutableListOf<Pair<Int, String>>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val lawTitle = obj.optString("law_title", "")
                    val artNum = obj.optString("article_number", "")
                    val category = obj.optString("category", "")
                    val text = obj.optString("text", "")

                    val normTitle = normalizeArabic(lawTitle)
                    val normText = normalizeArabic(text)

                    var score = 0

                    // إذا حُدد رقم مادة، يجب أن يطابق رقم المادة في السجل
                    if (targetArticle != null) {
                        if (artNum == targetArticle) {
                            score += 50
                            // فحص مطابقة اسم القانون (مثلاً العقوبات)
                            for (w in queryWords) {
                                if (normTitle.contains(w)) score += 30
                            }
                        }
                    } else {
                        // مطابقة عامة
                        if (normTitle.contains(normQ) || normText.contains(normQ)) {
                            score += 25
                        }
                        for (w in queryWords) {
                            if (normTitle.contains(w)) score += 8
                            if (normText.contains(w)) score += 4
                        }
                    }

                    if (score > 0) {
                        val formatted = "📖 [${lawTitle}] — المادة (${artNum})\n" +
                                "التصنيف: ${category}\n" +
                                "النص النافذ:\n${text}\n"
                        matchedArticles.add(Pair(score, formatted))
                    }
                }

                matchedArticles.sortByDescending { it.first }
                if (matchedArticles.isNotEmpty()) {
                    return matchedArticles.take(5).map { it.second }.joinToString("\n─────────────────────────────\n")
                }
            } catch (e: Exception) {
                // تجاهل والذهاب للبديل
            }
        }
        return getFallbackLawGuidance(normQ)
    }

    fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)

    private fun getFallbackLawGuidance(q: String): String {
        if (q.contains("عقوب") || q.contains("جرم") || q.contains("سرق") || q.contains("احتيال") || q.contains("سلاح")) {
            return "قانون العقوبات السوري العام رقم 148 وتعديلاته: يحدد الجرائم الواقعة على الأشخاص والأموال وأمن الدولة، والعقوبات الجنائية والجنحية وموانع العقاب."
        }
        if (q.contains("اجاز") || q.contains("عامل") || q.contains("موظف") || q.contains("خدمه") || q.contains("تعيين") || q.contains("مسلكي")) {
            return "قانون العاملين الأساسي في الدولة رقم 50 لعام 2004: ينظم شروط التعيين، الترفيع، الإجازات الإدارية والمرضية، والعقوبات المسلكية وحالات انتهاء الخدمة."
        }
        if (q.contains("بينات") || q.contains("اثبات") || q.contains("يمين") || q.contains("سند") || q.contains("شهود")) {
            return "قانون البينات السوري رقم 359 وتعديلاته: ينظم طرق الإثبات وأدلتها (الكتابة، الشهادة، القرائن، الإقرار، اليمين الحاسمة، والمعاينة والخبرة)."
        }
        return "لم يتم العثور على مادة مطابقة تماماً في القاعدة المحلية. يمكنك الضغط على [استشارة سريعة] للحصول على السند القانوني المفصل من الذكاء الاصطناعي."
    }
}
