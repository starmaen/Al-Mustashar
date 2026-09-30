package com.maen.almustashar

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.regex.Pattern

object LawsRepository {

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    suspend fun searchLaws(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "يرجى كتابة نص أو رقم مادة للبحث."

        val numMatch = Regex("""\d+""").find(q)?.value

        return try {
            val matchedDocs = mutableListOf<DocumentSnapshot>()

            // 1. البحث في المجموعة الفرعية articles عبر كل القوانين
            if (numMatch != null) {
                // محاولة برقم المادة كنص
                val snap1 = firestore.collectionGroup("articles")
                    .whereEqualTo("article_number", numMatch).get().await()
                matchedDocs.addAll(snap1.documents)

                // محاولة بحقل number إن وجد
                if (matchedDocs.isEmpty()) {
                    val snap2 = firestore.collectionGroup("articles")
                        .whereEqualTo("number", numMatch).get().await()
                    matchedDocs.addAll(snap2.documents)
                }

                // محاولة كرقم عددي
                val nVal = numMatch.toLongOrNull()
                if (matchedDocs.isEmpty() && nVal != null) {
                    val snap3 = firestore.collectionGroup("articles")
                        .whereEqualTo("article_number", nVal).get().await()
                    matchedDocs.addAll(snap3.documents)
                }
            } else {
                // بحث موضوعي في الكلمات المفتاحية لمجموعة articles
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
                        ?: numMatch 
                        ?: ""
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
                // الملاذ الاحتياطي الذكي إذا كانت المادة غير مضافة بعد في المنصة
                val prompt = "أنت مرجع قانوني سوري معتمد. استخرج النص الحرفي والتوثيق الرسمي بدقة للطلب: $q"
                AIClient.generateResponse(prompt)
            }
        } catch (e: Exception) {
            // في حال حدوث أي خطأ في الاتصال، يتم التحويل للذكاء حتى لا تبقى الشاشة فارغة
            try {
                AIClient.generateResponse("أنت مرجع قانوني سوري معتمد. استخرج النص الرسمي للطلب: $q")
            } catch (_: Exception) {
                "⚠️️ تعذر جلب المادة حالياً: ${e.localizedMessage}"
            }
        }
    }

    fun searchRelevantLaws(context: Context?, query: String): String {
        return kotlinx.coroutines.runBlocking { searchLaws(query) }
    }

    fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
