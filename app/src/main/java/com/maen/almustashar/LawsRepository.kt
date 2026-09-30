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

    // نصوص ومواد أساسية معتمدة مسبقاً (قانون العقوبات والتشريعات الرئيسية)
    private val extraLawArticles = listOf(
        Triple("65", "قانون العقوبات السوري العام", "العقوبات الجنائية العادية هي: الإعدام، الأشغال الشاقة المؤبدة، الاعتقال المؤبد، الأشغال الشاقة المؤقتة، الاعتقال المؤقت."),
        Triple("66", "قانون العقوبات السوري العام", "العقوبات الجنائية السياسية هي: الاعتقال المؤبد، الاعتقال المؤقت، الإقامة الجبرية، التجريد المدني."),
        Triple("148", "قانون العقوبات السوري العام", "تطبق القوانين السورية على جميع الجرائم المقترفة في الأراضي السورية أياً كانت جنسية الفاعل."),
        Triple("625", "قانون العقوبات السوري العام", "يعاقب بالحبس من ستة أشهر إلى ثلاث سنوات كل من أقدم على سرقة مال غيره دون ظرف مشدد."),
        Triple("641", "قانون العقوبات السوري العام", "يعاقب بالحبس من ثلاثة أشهر إلى سنتين وبالغرامة كل من حمل الغير على تسليمه مالاً بطرق احتيالية أو اتخاذ اسم كاذب أو صفة غير صحيحة.")
    )

    fun searchRelevantLaws(context: Context?, query: String): String {
        val rawQ = query.trim()
        if (rawQ.isBlank()) return "يرجى كتابة رقم المادة، اسم القانون، أو موضوع البحث."

        val normQ = normalize(rawQ)
        val numMatch = Regex("""\b(\d+)\b""").find(normQ)
        val targetNumber = numMatch?.value

        val stopWords = setOf("في", "من", "على", "الى", "إلى", "عن", "مع", "أو", "او", "ما", "هو", "هي", "قانون", "مادة", "المادة")
        val keywords = normQ.split(" ").filter { it.length > 1 && !stopWords.contains(it) }

        val matches = mutableListOf<Pair<Int, String>>()

        // أولاً: فحص المواد المدمجة المباشرة
        for (item in extraLawArticles) {
            var score = 0
            val normTitle = normalize(item.second)
            val normText = normalize(item.third)

            if (targetNumber != null && item.first == targetNumber) {
                score += 50
                if (normQ.contains("عقوب") && normTitle.contains("عقوب")) score += 30
            } else {
                for (w in keywords) {
                    if (normText.contains(w)) score += 10
                    if (normTitle.contains(w)) score += 8
                }
            }

            if (score > 0) {
                matches.add(Pair(score, "📖 [${item.second}] — المادة (${item.first})\n\nالنص النافذ:\n${item.third}"))
            }
        }

        // ثانياً: فحص قاعدة القوانين من ملف assets
        if (context != null) {
            try {
                val inputStream = context.assets.open("laws_data.json")
                val reader = InputStreamReader(inputStream, "UTF-8")
                val jsonText = reader.readText()
                reader.close()

                val jsonArray = JSONArray(jsonText)
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

                    if (targetNumber != null && artNum == targetNumber) {
                        score += 50
                        for (w in keywords) {
                            if (normTitle.contains(w) || normCategory.contains(w)) score += 15
                        }
                    } else {
                        // بحث موضوعي بالمعنى العام
                        for (w in keywords) {
                            if (normText.contains(w)) score += 8
                            if (normTitle.contains(w)) score += 6
                            if (normCategory.contains(w)) score += 5
                        }
                        if (kwArray != null) {
                            for (k in 0 until kwArray.length()) {
                                val kw = normalize(kwArray.getString(k))
                                for (w in keywords) {
                                    if (kw.contains(w)) score += 7
                                }
                            }
                        }
                    }

                    if (score > 0) {
                        matches.add(Pair(score, "📖 [${lawTitle}] — المادة (${artNum})\nالتصنيف: ${category}\n\nالنص النافذ:\n${text}"))
                    }
                }
            } catch (_: Exception) {}
        }

        matches.sortByDescending { it.first }
        if (matches.isNotEmpty()) {
            return matches.take(5).map { it.second }.joinToString("\n\n─────────────────────────────\n\n")
        }

        return "لم يتم العثور على مادة مطابقة تماماً للمدخلات.\nيمكنك الاستعانة بـ [استشارة سريعة] للحصول على تحليل تفصيلي من المستشار الذكي."
    }

    fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
