package com.maen.almustashar

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    suspend fun searchLaws(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "يرجى إدخال نص للبحث أو رقم مادة."

        val numMatch = Regex("""\d+""").find(q)?.value

        return try {
            val querySnapshot = if (numMatch != null) {
                val textQuery = firestore.collectionGroup("articles")
                    .whereEqualTo("article_number", numMatch)
                    .get().await()

                if (!textQuery.isEmpty) {
                    textQuery
                } else {
                    val numVal = numMatch.toLongOrNull()
                    if (numVal != null) {
                        firestore.collectionGroup("articles")
                            .whereEqualTo("article_number", numVal)
                            .get().await()
                    } else {
                        textQuery
                    }
                }
            } else {
                firestore.collectionGroup("articles")
                    .whereArrayContains("keywords", q)
                    .limit(20)
                    .get().await()
            }

            if (querySnapshot.isEmpty) {
                "⚠️ لم يتم العثور على أي مادة مطابقة في قاعدة البيانات."
            } else {
                val sb = StringBuilder()
                for (doc in querySnapshot.documents) {
                    val lawTitle = doc.getString("law_title")
                        ?: doc.reference.parent.parent?.id
                        ?: "تشريع سوري"
                    val art = doc.get("article_number")?.toString() ?: (numMatch ?: "")
                    val text = doc.getString("text") ?: doc.getString("content") ?: ""

                    sb.append("📖 [").append(lawTitle).append("] — المادة (").append(art).append(")\n\n")
                    sb.append("النص النافذ:\n")
                    sb.append(text).append("\n\n")
                    sb.append("───────────────────────\n\n")
                }
                sb.toString().trimEnd()
            }
        } catch (e: Exception) {
            "⚠️ تعذر جلب البيانات: ${e.localizedMessage}"
        }
    }

    fun searchRelevantLaws(context: Context?, query: String): String {
        return kotlinx.coroutines.runBlocking { searchLaws(query) }
    }

    fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
