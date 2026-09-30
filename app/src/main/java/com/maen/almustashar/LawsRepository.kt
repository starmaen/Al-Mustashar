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

    suspend fun searchRelevantLaws(context: Context?, query: String): String {
        val rawQ = query.trim()
        if (rawQ.isBlank()) return "يرجى كتابة رقم المادة، اسم القانون، أو موضوع البحث."

        val normQ = normalize(rawQ)
        val numMatch = Regex("""\b(\d+)\b""").find(normQ)
        val targetArticle = numMatch?.value

        val stopWords = setOf("في", "من", "على", "الى", "إلى", "عن", "مع", "أو", "او", "ما", "هو", "هي", "قانون", "مادة", "المادة")
        val keywords = normQ.split(" ").filter { it.length > 1 && !stopWords.contains(it) }

        // 1. البحث في الملف المحلي أولاً
        if (context != null) {
            try {
                val inputStream = context.assets.open("laws_data.json")
                val reader = InputStreamReader(inputStream, "UTF-8")
                val jsonText = reader.readText()
                reader.close()

                val jsonArray = JSONArray(jsonText)
                val matches = mutableListOf<String>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val artNum = obj.optString("article_number", "")
                    val lawTitle = obj.optString("law_title", "")
                    val category = obj.optString("category", "")
                    val text = obj.optString("text", "")
                    val normTitle = normalize(lawTitle)

                    if (targetArticle != null) {
                        // مطابقة رقم المادة حصراً
                        if (artNum == targetArticle) {
                            if (keywords.isEmpty() || keywords.any { normTitle.contains(it) || normalize(category).contains(it) }) {
                                matches.add("📖 [${lawTitle}] — المادة (${artNum})\nالتصنيف: $category\n\nالنص النافذ:\n$text")
                            }
                        }
                    } else {
                        // بحث موضوعي عام
                        if (keywords.any { normalize(text).contains(it) || normTitle.contains(it) }) {
                            matches.add("📖 [${lawTitle}] — المادة (${artNum})\nالتصنيف: $category\n\nالنص النافذ:\n$text")
                        }
                    }
                }

                if (matches.isNotEmpty()) {
                    return matches.take(3).joinToString("\n\n─────────────────────────────\n\n")
                }
            } catch (_: Exception) {}
        }

        // 2. إذا لم تكن المادة في الملف المحلي (مثل المادة 4 محاماة أو 65 عقوبات) -> جلبها مباشرة عبر الذكاء الاصطناعي القانوني
        val aiResult = AIClient.fetchLawArticleFromAI(rawQ)
        return aiResult
    }

    suspend fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    suspend fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
