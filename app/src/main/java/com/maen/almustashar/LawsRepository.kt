package com.maen.almustashar

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.regex.Pattern

object LawsRepository {

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val collection by lazy { firestore.collection("laws") }

    suspend fun searchLaws(rawQuery: String): String {
        val query = rawQuery.trim()
        if (query.isBlank()) return "يرجى إدخال كلمة بحث أو رقم مادة."

        val norm = normalizeArabic(query)
        val extractedArt = extractArticleNumber(norm)
        val remainingWords = extractRemainingWords(norm)

        return try {
            val matchedDocs = mutableListOf<DocumentSnapshot>()

            // 1. الاحتمال الأول: وجود رقم مادة محدد
            if (extractedArt != null) {
                // بحث برقم المادة كنص
                val textSnap = collection.whereEqualTo("article_number", extractedArt).get().await()
                matchedDocs.addAll(textSnap.documents)

                // بحث برقم المادة كقيمة عددية إن لم يُعثر عليها كنص
                val numVal = extractedArt.toLongOrNull()
                if (numVal != null) {
                    val numSnap = collection.whereEqualTo("article_number", numVal).get().await()
                    for (d in numSnap.documents) {
                        if (matchedDocs.none { it.id == d.id }) matchedDocs.add(d)
                    }
                }

                // إذا كتب المستخدم اسم القانون مع رقم المادة (مثال: المادة 40 محاماة)
                if (remainingWords.isNotEmpty() && matchedDocs.isNotEmpty()) {
                    val filtered = matchedDocs.filter { doc ->
                        val title = normalizeArabic(doc.getString("law_title") ?: "")
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

            // 2. الاحتمال الثاني: البحث الموضوعي بالكلمات المفتاحية
            val searchTokens = norm.split(" ").filter { it.length > 2 }
            for (token in searchTokens) {
                val kwSnap = collection.whereArrayContains("keywords", token).limit(20).get().await()
                for (d in kwSnap.documents) {
                    if (matchedDocs.none { it.id == d.id }) matchedDocs.add(d)
                }
                if (matchedDocs.size >= 15) break
            }

            // 3. الاحتمال الثالث: البحث في عناوين القوانين مباشرة
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

    private fun normalizeArabic(text: String): String {
        return text.replace("[\u064B-\u0652]".toRegex(), "") // إزالة التشكيل
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ة", "ه").replace("ى", "ي")
            .replace("[^\\w\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
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
        return cleaned.split(" ").filter { it.length > 2 }
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
}
