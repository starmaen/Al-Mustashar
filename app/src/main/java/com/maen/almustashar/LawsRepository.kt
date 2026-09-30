package com.maen.almustashar

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    suspend fun searchLaws(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "يرجى كتابة نص أو رقم مادة للبحث."

        val numMatch = Regex("""\d+""").find(q)?.value

        return try {
            val matchedDocs = mutableListOf<DocumentSnapshot>()

            if (numMatch != null) {
                // البحث برقم المادة كنص داخل articles
                val snap1 = firestore.collectionGroup("articles")
                    .whereEqualTo("article_number", numMatch).get().await()
                matchedDocs.addAll(snap1.documents)

                // البحث بحقل number إن وجد
                if (matchedDocs.isEmpty()) {
                    val snap2 = firestore.collectionGroup("articles")
                        .whereEqualTo("number", numMatch).get().await()
                    matchedDocs.addAll(snap2.documents)
                }

                // البحث برقم المادة كقيمة عددية
                val nVal = numMatch.toLongOrNull()
                if (matchedDocs.isEmpty() && nVal != null) {
                    val snap3 = firestore.collectionGroup("articles")
                        .whereEqualTo("article_number", nVal).get().await()
                    matchedDocs.addAll(snap3.documents)
                }
            } else {
                // البحث الموضوعي بالكلمات المفتاحية
                val snapKw = firestore.collectionGroup("articles")
                    .whereArrayContains("keywords", q).limit(15).get().await()
                matchedDocs.addAll(snapKw.documents)
            }

            if (matchedDocs.isNotEmpty()) {
                val sb = StringBuilder()
                for (doc in matchedDocs) {
                    val lawTitle = doc.getString("law_title")
                        ?: doc.reference.parent.parent?.id
                        ?: "تشريع سوري"
                    val art = doc.get("article_number")?.toString()
                        ?: doc.get("number")?.toString()
                        ?: (numMatch ?: "")
                    val text = doc.getString("text")
                        ?: doc.getString("content")
                        ?: ""

                    sb.append("🏛️ [قاعدة البيانات — ").append(lawTitle).append("]\n")
                    sb.append("📖 المادة (").append(art).append(")\n\n")
                    sb.append("النص النافذ:\n").append(text).append("\n\n")
                    sb.append("───────────────────────\n\n")
                }
                sb.toString().trimEnd()
            } else {
                val prompt = "أنت مرجع قانوني سوري معتمد. استخرج النص الحرفي والتوثيق الرسمي بدقة للطلب: $q"
                AIClient.generateResponse(prompt)
            }
        } catch (e: Exception) {
            try {
                AIClient.generateResponse("أنت مرجع قانوني سوري معتمد. استخرج النص الرسمي للطلب: $q")
            } catch (_: Exception) {
                "⚠️ تعذر جلب المادة حالياً: ${e.localizedMessage}"
            }
        }
    }

    fun searchRelevantLaws(context: Context?, query: String): String {
        return kotlinx.coroutines.runBlocking { searchLaws(query) }
    }

    fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
