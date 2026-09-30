package com.maen.almustashar

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.io.InputStreamReader
import java.util.regex.Pattern

object LawsRepository {

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val collection by lazy { firestore.collection("laws") }

    private fun normalize(text: String): String {
        return text.trim().lowercase()
            .replace("[\u064B-\u0652]".toRegex(), "")
            .replace(Regex("[أإآ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .replace("١", "1").replace("٢", "2").replace("٣", "3").replace("٤", "4").replace("٥", "5")
            .replace("٦", "6").replace("٧", "7").replace("٨", "8").replace("٩", "9").replace("٠", "0")
            .replace(Regex("[\\p{Punct}\\s]+"), " ")
            .trim()
    }

    private fun extractArticleNumber(norm: String): String? {
        val pattern = Pattern.compile("(?:ماده|مادة|الماده|المادة)?\\s*(\\d+)")
        val matcher = pattern.matcher(norm)
        return if (matcher.find()) {
            matcher.group(1)
        } else if (norm.matches(Regex("^\\d+$"))) {
            norm
        } else {
            null
        }
    }

    private fun extractRemainingWords(norm: String): List<String> {
        val cleaned = norm.replace("(?:ماده|مادة|الماده|المادة)".toRegex(), "")
            .replace("\\d+".toRegex(), "")
            .trim()
        val stopWords = setOf("في", "من", "على", "الى", "الي", "عن", "مع", "قانون", "تشريع")
        return cleaned.split(" ").filter { it.length > 2 && !stopWords.contains(it) }
    }

    suspend fun searchLaws(rawQuery: String): String {
        val query = rawQuery.trim()
        if (query.isBlank()) return "يرجى إدخال كلمة بحث أو رقم مادة."

        val norm = normalize(query)
        val extractedArt = extractArticleNumber(norm)
        val remainingWords = extractRemainingWords(norm)

        return try {
            val matchedDocs = mutableListOf<DocumentSnapshot>()

            // 1. البحث الصريح برقم المادة
            if (extractedArt != null) {
                // محاولة المطابقة كنص
                val textSnap = collection.whereEqualTo("article_number", extractedArt).get().await()
                matchedDocs.addAll(textSnap.documents)

                // محاولة المطابقة كرقم عددي
                val numVal = extractedArt.toLongOrNull()
                if (numVal != null) {
                    val numSnap = collection.whereEqualTo("article_number", numVal).get().await()
                    for (d in numSnap.documents) {
                        if (matchedDocs.none { it.id == d.id }) matchedDocs.add(d)
                    }
                }

                // فلترة باسم القانون إذا تم تحديده في البحث
                if (remainingWords.isNotEmpty() && matchedDocs.isNotEmpty()) {
                    val filtered = matchedDocs.filter { doc ->
                        val title = normalize(doc.getString("law_title") ?: "")
                        remainingWords.any { word -> title.contains(word) }
                    }
                    if (filtered.isNotEmpty()) {
                        return formatResults(filtered)
                    }
                }

                if (matchedDocs.isNotEmpty()) {
                    return formatResults(matchedDocs)
                }
            }

            // 2. البحث بالمصطلحات والكلمات المفتاحية
            val searchTokens = norm.split(" ").filter { it.length > 2 }
            for (token in searchTokens) {
                val kwSnap = collection.whereArrayContains("keywords", token).limit(20).get().await()
                for (d in kwSnap.documents) {
                    if (matchedDocs.none { it.id == d.id }) matchedDocs.add(d)
                }
                if (matchedDocs.size >= 15) break
            }

            // 3. البحث في عنوان القانون
            if (matchedDocs.isEmpty()) {
                val titleSnap = collection
                    .whereGreaterThanOrEqualTo("law_title", query)
                    .whereLessThanOrEqualTo("law_title", query + "\uf8ff")
                    .limit(15).get().await()
                matchedDocs.addAll(titleSnap.documents)
            }

            if (matchedDocs.isEmpty()) {
                "⚠️ لم يتم العثور على أي مادة أو قانون يطابق: \"$query\" في قاعدة البيانات."
            } else {
                formatResults(matchedDocs)
            }
        } catch (e: Exception) {
            "⚠️ تعذر الاتصال بقاعدة البيانات: ${e.localizedMessage}"
        }
    }

    private fun formatResults(docs: List<DocumentSnapshot>): String {
        val sb = StringBuilder()
        for (doc in docs) {
            val lawTitle = doc.getString("law_title") ?: "تشريع سوري"
            val art = doc.get("article_number")?.toString() ?: ""
            val text = doc.getString("text") ?: ""

            sb.append("📖 [").append(lawTitle).append("] — المادة (").append(art).append(")\n\n")
            sb.append("النص النافذ:\n")
            sb.append(text).append("\n\n")
            sb.append("───────────────────────\n\n")
        }
        return sb.toString().trimEnd()
    }

    // دوال التوافق مع نداءات الواجهة القديمة
    fun searchRelevantLaws(context: Context?, query: String): String {
        return kotlinx.coroutines.runBlocking { searchLaws(query) }
    }

    fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
