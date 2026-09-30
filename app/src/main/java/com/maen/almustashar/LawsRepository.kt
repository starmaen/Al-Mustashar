package com.maen.almustashar

import android.content.Context
import org.json.JSONArray
import java.io.InputStreamReader

object LawsRepository {

    // تطبيع الحروف العربية لمطابقة دقيقة دون تأثر بالهمزات
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
        // تفكيك الكلمات وتجاهل حروف الجر والكلمات الشائعة
        val stopWords = setOf("في", "من", "على", "الى", "إلى", "عن", "مع", "أو", "او", "ما", "هو", "هي", "المتعلقه", "المتعلقة", "بشان", "بخصوص")
        val queryWords = normQ.split(" ").filter { it.length > 1 && !stopWords.contains(it) }

        if (context != null) {
            try {
                val assetManager = context.assets
                val inputStream = assetManager.open("laws_data.json")
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
                    val keywordsArray = obj.optJSONArray("keywords")

                    val normTitle = normalizeArabic(lawTitle)
                    val normCategory = normalizeArabic(category)
                    val normText = normalizeArabic(text)

                    val allKeywords = mutableListOf<String>()
                    if (keywordsArray != null) {
                        for (k in 0 until keywordsArray.length()) {
                            allKeywords.add(normalizeArabic(keywordsArray.getString(k)))
                        }
                    }

                    var score = 0

                    // 1. مطابقة رقم المادة المباشر
                    if (normQ.contains("ماده $artNum") || normQ.contains("الماده $artNum") || normQ == artNum) {
                        score += 20
                    }

                    // 2. مطابقة العبارة كاملة
                    if (normTitle.contains(normQ) || normCategory.contains(normQ) || normText.contains(normQ)) {
                        score += 15
                    }

                    // 3. مطابقة الكلمات المفردة (Word Tokens)
                    for (w in queryWords) {
                        if (allKeywords.any { it.contains(w) || w.contains(it) }) {
                            score += 8
                        }
                        if (normCategory.contains(w)) {
                            score += 6
                        }
                        if (normText.contains(w)) {
                            score += 4
                        }
                        if (normTitle.contains(w)) {
                            score += 3
                        }
                    }

                    if (score > 0) {
                        val formatted = "📖 [${lawTitle}] — المادة (${artNum})\n" +
                                "التصنيف: ${category}\n" +
                                "النص النافذ:\n${text}\n"
                        matchedArticles.add(Pair(score, formatted))
                    }
                }

                // ترتيب النتائج حسب الأعلى تطابقاً
                matchedArticles.sortByDescending { it.first }

                if (matchedArticles.isNotEmpty()) {
                    return matchedArticles.take(5).map { it.second }.joinToString("\n─────────────────────────────\n")
                }
            } catch (e: Exception) {
                // المتابعة إلى الرد البديل
            }
        }

        return getFallbackLawGuidance(normQ)
    }

    fun searchRelevantLaws(query: String): String {
        return searchRelevantLaws(null, query)
    }

    fun searchLaw(context: Context?, query: String): String {
        return searchRelevantLaws(context, query)
    }

    private fun getFallbackLawGuidance(q: String): String {
        if (q.contains("اجاز") || q.contains("عامل") || q.contains("موظف") || q.contains("خدمه") || q.contains("تعيين") || q.contains("مسلكي")) {
            return "قانون العاملين الأساسي في الدولة رقم 50 لعام 2004: ينظم شروط التعيين، الترفيع، الإجازات الإدارية والمرضية والأمومة، والعقوبات المسلكية وحالات انتهاء الخدمة (المادة 132)."
        }
        if (q.contains("بينات") || q.contains("اثبات") || q.contains("يمين") || q.contains("سند") || q.contains("شهود")) {
            return "قانون البينات السوري رقم 359 وتعديلاته: ينظم طرق الإثبات وأدلتها (الكتابة، الشهادة، القرائن، الإقرار، اليمين الحاسمة، والمعاينة والخبرة)."
        }
        if (q.contains("محام") || q.contains("نقاب") || q.contains("اتعاب") || q.contains("حصان")) {
            return "قانون تنظيم مهنة المحاماة رقم 30 لعام 2010: ينظم حقوق المحامي وحصانته المهنية (المادة 56)، والتزاماته وحفظ الأسرار وتقدير الأتعاب."
        }
        if (q.contains("تامين") || q.contains("تقاعد") || q.contains("اصاب") || q.contains("معاش")) {
            return "قانون التأمينات الاجتماعية رقم 92 وقانون المعاشات: ينظم تأمين الشيخوخة والعجز والوفاة وإصابات العمل وشروط استحقاق وتوزيع الرواتب التقاعدية."
        }

        return "لم يتم العثور على مادة مطابقة مباشرة لنص البحث في قاعدة القوانين المدمجة.\nيمكنك الاستعانة بنافذة [الاستشارة القانونية] للتحليل أو [بحث قانوني عام] لمطالعة أحدث المراجع."
    }
}
