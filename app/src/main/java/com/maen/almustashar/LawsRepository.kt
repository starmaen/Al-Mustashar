package com.maen.almustashar

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    // تحويل الأرقام المشرقية (١، ٢، ٣) إلى أرقام إنجليزية (1, 2, 3)
    private fun normalizeDigits(input: String): String {
        val easternDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        var result = input
        for (i in easternDigits.indices) {
            result = result.replace(easternDigits[i], ('0' + i))
        }
        return result
    }

    suspend fun searchLaws(query: String): String {
        val originalQuery = query.trim()
        if (originalQuery.isBlank()) return "يرجى إدخال نص للبحث أو رقم مادة."

        val normalized = normalizeDigits(originalQuery)
        val numMatch = Regex("""\d+""").find(normalized)?.value

        return try {
            val results = mutableListOf<String>()

            // 1. إذا كان البحث يحتوي على رقم، نبحث عن الرقم بكل الطرق الممكنة
            if (numMatch != null) {
                val numLong = numMatch.toLongOrNull()

                // بحث كرقم كنص
                val q1 = firestore.collectionGroup("articles")
                    .whereEqualTo("article_number", numMatch)
                    .limit(10).get().await()

                // بحث كرقم كـ Long/Number
                val q2 = if (numLong != null) {
                    firestore.collectionGroup("articles")
                        .whereEqualTo("article_number", numLong)
                        .limit(10).get().await()
                } else null

                // بحث بحقل number بديل في حال كانت التسمية مختلفة
                val q3 = firestore.collectionGroup("articles")
                    .whereEqualTo("number", numMatch)
                    .limit(10).get().await()

                val docs = (q1.documents + (q2?.documents ?: emptyList()) + q3.documents).distinctBy { it.id }

                for (doc in docs) {
                    results.add(formatDoc(doc, numMatch))
                }
            }

            // 2. إذا لم تكن هناك أرقام أو لم تظهر نتائج بالرقم، نجرب البحث العام بالكلمات المفتاحية
            if (results.isEmpty()) {
                val cleanWord = originalQuery
                    .replace("المادة", "")
                    .replace("الماده", "")
                    .replace("من", "")
                    .replace("القانون", "")
                    .trim()

                if (cleanWord.isNotBlank()) {
                    val kwQuery = firestore.collectionGroup("articles")
                        .whereArrayContains("keywords", cleanWord)
                        .limit(10).get().await()

                    for (doc in kwQuery.documents) {
                        results.add(formatDoc(doc, null))
                    }
                }
            }

            if (results.isEmpty()) {
                "⚠️ لم يتم العثور على أي مادة مطابقة.\nجرّب البحث برقم المادة فقط (مثل: 4) أو بمصطلح قانوني رئيسي."
            } else {
                results.joinToString("\n\n")
            }
        } catch (e: Exception) {
            "⚠️ تعذر جلب البيانات: ${e.localizedMessage}"
        }
    }

    private fun formatDoc(doc: com.google.firebase.firestore.DocumentSnapshot, fallbackNum: String?): String {
        val lawTitle = doc.getString("law_title")
            ?: doc.getString("law")
            ?: doc.reference.parent.parent?.id
            ?: "تشريع سوري"

        val artNum = doc.get("article_number")?.toString()
            ?: doc.get("number")?.toString()
            ?: fallbackNum
            ?: ""

        val content = doc.getString("text")
            ?: doc.getString("content")
            ?: doc.getString("body")
            ?: doc.getString("article_text")
            ?: "لا يوجد نص متاح."

        return """
📖 [$lawTitle] — المادة ($artNum)

النص النافذ:
$content

───────────────────────
        """.trimIndent()
    }

    fun searchRelevantLaws(context: Context?, query: String): String {
        return kotlinx.coroutines.runBlocking { searchLaws(query) }
    }

    fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
