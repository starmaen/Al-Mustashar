package com.maen.almustashar

import android.content.Context
import org.json.JSONArray
import java.io.InputStreamReader

object LawsRepository {

    private fun normalize(text: String): String {
        return text.trim().lowercase()
            .replace(Regex("[أإآ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .replace("١", "1").replace("٢", "2").replace("٣", "3").replace("٤", "4").replace("٥", "5")
            .replace("٦", "6").replace("٧", "7").replace("٨", "8").replace("٩", "9").replace("٠", "0")
            .replace(Regex("[\\p{Punct}\\s]+"), " ")
    }

    private val penalCodeArticles = listOf(
        Triple("65", "قانون العقوبات السوري العام", "العقوبات الجنائية العادية هي: الإعدام، الأشغال الشاقة المؤبدة، الاعتقال المؤبد، الأشغال الشاقة المؤقتة، الاعتقال المؤقت."),
        Triple("66", "قانون العقوبات السوري العام", "العقوبات الجنائية السياسية هي: الاعتقال المؤبد، الاعتقال المؤقت، الإقامة الجبرية، التجريد المدني."),
        Triple("148", "قانون العقوبات السوري العام", "تطبق القوانين السورية على جميع الجرائم المقترفة في الأراضي السورية أياً كانت جنسية الفاعل."),
        Triple("625", "قانون العقوبات السوري العام", "يعاقب بالحبس من ستة أشهر إلى ثلاث سنوات كل من أقدم على سرقة مال غيره دون ظرف مشدد."),
        Triple("641", "قانون العقوبات السوري العام", "يعاقب بالحبس من ثلاثة أشهر إلى سنتين وبالغرامة كل من حمل الغير على تسليمه مالاً بطرق احتيالية أو اتخاذ اسم كاذب أو صفة غير صحيحة.")
    )

    fun searchRelevantLaws(context: Context?, query: String): String {
        val rawQ = query.trim()
        if (rawQ.isBlank()) return "يرجى كتابة رقم المادة أو الموضوع للبحث."

        val normQ = normalize(rawQ)

        // استخراج رقم المادة الصريح (مثال: المادة 33 أو مادة 65)
        val articleMatch = Regex("""(?:ماده|الماده)?\s*(\d+)""").find(normQ)
        val targetArticle = articleMatch?.groupValues?.get(1)

        val stopWords = setOf("في", "من", "على", "الى", "إلى", "عن", "مع", "أو", "او", "ما", "هو", "هي", "قانون", "مادة", "المادة")
        val keywords = normQ.split(" ").filter { it.length > 1 && !stopWords.contains(it) }

        val matches = mutableListOf<String>()

        // 1. إذا طلب المستخدم رقم مادة محدد -> يجب مطابقة رقم المادة حصراً
        if (targetArticle != null) {
            // فحص مواد العقوبات
            for (item in penalCodeArticles) {
                if (item.first == targetArticle) {
                    val normTitle = normalize(item.second)
                    if (normQ.contains("عقوب") || normQ.contains("جزائ") || keywords.isEmpty() || keywords.any { normTitle.contains(it) }) {
                        matches.add("📖 [${item.second}] — المادة (${item.first})\n\nالنص النافذ:\n${item.third}")
                    }
                }
            }

            // فحص ملف الأصول
            if (context != null) {
                try {
                    val inputStream = context.assets.open("laws_data.json")
                    val reader = InputStreamReader(inputStream, "UTF-8")
                    val jsonText = reader.readText()
                    reader.close()

                    val jsonArray = JSONArray(jsonText)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val artNum = obj.optString("article_number", "")
                        if (artNum == targetArticle) {
                            val lawTitle = obj.optString("law_title", "")
                            val category = obj.optString("category", "")
                            val text = obj.optString("text", "")
                            val normTitle = normalize(lawTitle)

                            if (keywords.isEmpty() || keywords.any { normTitle.contains(it) || normalize(category).contains(it) }) {
                                matches.add("📖 [${lawTitle}] — المادة (${artNum})\nالتصنيف: $category\n\nالنص النافذ:\n$text")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (matches.isNotEmpty()) {
                return matches.joinToString("\n\n─────────────────────────────\n\n")
            } else {
                return "⚠️ المادة ($targetArticle) غير متوفرة حالياً في قاعدة البيانات المحلية المختصرة.\nيمكنك الاستعانة بـ [استشارة سريعة] لتحليل الواقعة واستخراج النص الكامل عبر المستشار الذكي."
            }
        }

        // 2. بحث موضوعي عام (عند عدم إدخال رقم مادة صريح)
        if (context != null) {
            try {
                val inputStream = context.assets.open("laws_data.json")
                val reader = InputStreamReader(inputStream, "UTF-8")
                val jsonText = reader.readText()
                reader.close()

                val jsonArray = JSONArray(jsonText)
                val scoredMatches = mutableListOf<Pair<Int, String>>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val lawTitle = obj.optString("law_title", "")
                    val artNum = obj.optString("article_number", "")
                    val category = obj.optString("category", "")
                    val text = obj.optString("text", "")
                    val kwArray = obj.optJSONArray("keywords")

                    val normTitle = normalize(lawTitle)
                    val normCategory = normalize(category)
                    val normText = normalize(text)

                    var score = 0
                    for (w in keywords) {
                        if (normText.contains(w)) score += 6
                        if (normTitle.contains(w)) score += 5
                        if (normCategory.contains(w)) score += 4
                    }
                    if (kwArray != null) {
                        for (k in 0 until kwArray.length()) {
                            val kw = normalize(kwArray.getString(k))
                            for (w in keywords) {
                                if (kw.contains(w)) score += 5
                            }
                        }
                    }

                    if (score > 0) {
                        scoredMatches.add(Pair(score, "📖 [${lawTitle}] — المادة (${artNum})\nالتصنيف: $category\n\nالنص النافذ:\n$text"))
                    }
                }

                scoredMatches.sortByDescending { it.first }
                if (scoredMatches.isNotEmpty()) {
                    return scoredMatches.take(4).map { it.second }.joinToString("\n\n─────────────────────────────\n\n")
                }
            } catch (_: Exception) {}
        }

        return "لم يتم العثور على نتائج مطابقة لعبارة البحث في القاعدة المحلية."
    }

    fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
