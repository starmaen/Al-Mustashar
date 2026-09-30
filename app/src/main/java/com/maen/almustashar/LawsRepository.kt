package com.maen.almustashar

import android.content.Context
import org.json.JSONArray
import java.io.InputStreamReader

object LawsRepository {

    // دالة البحث الرئيسية التي تستقبل السياق
    fun searchRelevantLaws(context: Context?, query: String): String {
        val q = query.trim().lowercase()
        if (q.isBlank()) return "يرجى إدخال عبارة البحث."

        if (context != null) {
            try {
                val assetManager = context.assets
                val inputStream = assetManager.open("laws_data.json")
                val reader = InputStreamReader(inputStream, "UTF-8")
                val jsonText = reader.readText()
                reader.close()

                val jsonArray = JSONArray(jsonText)
                val matchedArticles = mutableListOf<String>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val lawTitle = obj.optString("law_title", "")
                    val artNum = obj.optString("article_number", "")
                    val category = obj.optString("category", "")
                    val text = obj.optString("text", "")
                    val keywordsArray = obj.optJSONArray("keywords")

                    var keywordMatch = false
                    if (keywordsArray != null) {
                        for (k in 0 until keywordsArray.length()) {
                            val kw = keywordsArray.getString(k).lowercase()
                            if (q.contains(kw) || kw.contains(q)) {
                                keywordMatch = true
                                break
                            }
                        }
                    }

                    val titleOrTextMatch = lawTitle.lowercase().contains(q) ||
                            text.lowercase().contains(q) ||
                            q.contains("المادة $artNum") ||
                            q.contains("مادة $artNum") ||
                            q == artNum

                    if (keywordMatch || titleOrTextMatch) {
                        val formatted = "📖 [${lawTitle}] — المادة (${artNum})\n" +
                                "التصنيف: ${category}\n" +
                                "النص النافذ:\n${text}\n"
                        matchedArticles.add(formatted)
                    }
                }

                if (matchedArticles.isNotEmpty()) {
                    return matchedArticles.joinToString("\n─────────────────────────────\n")
                }
            } catch (e: Exception) {
                // متابعة إلى القواعد الاحتياطية في حال تعذر قراءة الملف
            }
        }

        // قواعد التوجيه الاحتياطية السريعة (تضمن الإجابة حتى لو استُدعي دون context)
        return getFallbackLawGuidance(q)
    }

    // دالة إضافية زائدة (Overload) في حال كان الاستدعاء يقبل query فقط
    fun searchRelevantLaws(query: String): String {
        return searchRelevantLaws(null, query)
    }

    // دالة إضافية للاسم البديل searchLaw
    fun searchLaw(context: Context?, query: String): String {
        return searchRelevantLaws(context, query)
    }

    private fun getFallbackLawGuidance(q: String): String {
        if (q.contains("بينات") || q.contains("اثبات") || q.contains("إثبات") || q.contains("يمين") || q.contains("سند") || q.contains("شهادة")) {
            return "قانون البينات السوري رقم 359 وتعديلاته: ينظم طرق الإثبات وأدلتها (الكتابة، الشهادة، القرائن، الإقرار، اليمين الحاسمة، والمعاينة والخبرة)."
        }
        if (q.contains("محام") || q.contains("نقابة") || q.contains("أتعاب") || q.contains("وكالة") || q.contains("حصانة")) {
            return "قانون تنظيم مهنة المحاماة رقم 30 لعام 2010: ينظم حقوق المحامي وحصانته المهنية (المادة 56)، والتزاماته وحفظ الأسرار وتقدير الأتعاب أمام مجلس الفرع."
        }
        if (q.contains("عامل") || q.contains("موظف") || q.contains("وظيفة") || q.contains("تعيين") || q.contains("عقوبة مسلكية") || q.contains("عزل")) {
            return "قانون العاملين الأساسي في الدولة رقم 50 لعام 2004: ينظم شروط التعيين، الترفيع، الإجازات، والعقوبات المسلكية الخفيفة والشديدة، وحالات انتهاء الخدمة."
        }
        if (q.contains("تأمين") || q.contains("تقاعد") || q.contains("إصابة عمل") || q.contains("معاش") || q.contains("شيخوخة")) {
            return "قانون التأمينات الاجتماعية رقم 92 لعام 1959 وتعديلاته وقانون المعاشات: ينظم تأمين الشيخوخة والعجز والوفاة وإصابات العمل وشروط استحقاق وتوزيع الرواتب التقاعدية."
        }
        if (q.contains("أصول") || q.contains("محاكمات") || q.contains("دعوى") || q.contains("تبليغ") || q.contains("طعن")) {
            return "قانون أصول المحاكمات السوري: ينظم قواعد الاختصاص القضائي، إجراءات قيد الدعاوى وتبليغ الخصوم، ومواعيد وطرق الطعن بالأحكام."
        }
        if (q.contains("عقوبات") || q.contains("جرم") || q.contains("جناية") || q.contains("جنحة") || q.contains("سرقة") || q.contains("احتيال")) {
            return "قانون العقوبات السوري العام وتعديلاته: يحدد الجرائم الجنائية والجنحية والمخالفات، وأركان الجريمة والمسؤولية الجزائية والعقوبات المقررة."
        }

        return "لم يتم العثور على نص مطابق مباشرة في قاعدة القوانين المدمجة.\nيمكنك الاستعانة بنافذة [الاستشارة القانونية] للتحليل المعمق أو [بحث قانوني عام] لمطالعة أحدث المراجع."
    }
}
