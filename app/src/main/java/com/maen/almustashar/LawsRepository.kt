package com.maen.almustashar

import android.content.Context
import org.json.JSONArray
import java.io.InputStreamReader

object LawsRepository {

    fun searchLaw(context: Context, query: String): String {
        val q = query.trim().lowercase()
        if (q.isBlank()) return "يرجى إدخال عبارة البحث."

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
            // في حال حدوث أي خطأ في القراءة
        }

        // إرشاد تلقائي في حال عدم وجود مطابقة حرفية مباشرة
        if (q.contains("بينات") || q.contains("اثبات") || q.contains("يمين") || q.contains("سند")) {
            return "قانون البينات السوري رقم 359 وتعديلاته: ينظم طرق الإثبات وأدلتها (الكتابة، الشهادة، القرائن، الإقرار، اليمين، والمعاينة والخبرة)."
        }
        if (q.contains("محام") || q.contains("نقابة") || q.contains("أتعاب") || q.contains("وكالة")) {
            return "قانون تنظيم مهنة المحاماة رقم 30 لعام 2010: ينظم حقوق المحامي وحصانته المهنية (المادة 56)، والتزاماته وحفظ الأسرار وتقدير الأتعاب."
        }
        if (q.contains("عامل") || q.contains("موظف") || q.contains("وظيفة") || q.contains("تعيين") || q.contains("عقوبة مسلكية")) {
            return "قانون العاملين الأساسي في الدولة رقم 50 لعام 2004: ينظم قواعد التعيين والترقية والإجازات والواجبات، والعقوبات المسلكية وإنهاء الخدمة."
        }
        if (q.contains("تأمين") || q.contains("تقاعد") || q.contains("إصابة عمل") || q.contains("معاش")) {
            return "قانون التأمينات الاجتماعية رقم 92 وقانون المعاشات: ينظم تأمين الشيخوخة والعجز والوفاة وإصابات العمل وشروط استحقاق الرواتب التقاعدية."
        }

        return "لم يتم العثور على نص مطابق مباشرة في قاعدة القوانين المحلية.\nيمكنك الاستعانة بنافذة [الاستشارة القانونية] للتحليل المعمق أو [بحث قانوني عام] لمطالعة أحدث المراجع."
    }
}
