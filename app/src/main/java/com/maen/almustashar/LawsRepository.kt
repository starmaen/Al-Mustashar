package com.maen.almustashar

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.io.InputStreamReader

object LawsRepository {

    private fun normalize(text: String): String {
        return text.trim()
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .replace(Regex("[\\p{Punct}&&[^0-9]]"), "")
            .replace(Regex("\\s+"), " ")
    }

    suspend fun searchRelevantLaws(context: Context?, query: String): String {
        val rawQ = query.trim()
        if (rawQ.isEmpty()) return "يرجى إدخال نص البحث أو رقم المادة أو الموضوع المطلوب."

        val normQ = normalize(rawQ)
        val numMatch = Regex("""\b(\d+)\b""").find(normQ)
        val targetArticle = numMatch?.value

        val stopWords = setOf("في", "من", "على", "الى", "إلى", "عن", "مع", "أو", "او", "ما", "هو", "هي", "قانون", "مادة", "المادة", "نص", "احكام", "كل", "جميع")
        val searchKeywords = normQ.split(" ").filter { it.length > 2 && !stopWords.contains(it) }

        val collectedResults = mutableListOf<String>()

        // 1. البحث الشامل في Firebase Firestore (دون أي تقييد لأسماء القوانين)
        try {
            val firestore = FirebaseFirestore.getInstance()
            val collection = firestore.collection("laws")

            val snapshot = if (targetArticle != null) {
                // استخراج رقم المادة من كافة القوانين الموجودة حالياً أو المضافة مستقبلاً
                collection.whereEqualTo("article_number", targetArticle).get().await()
            } else if (searchKeywords.isNotEmpty()) {
                val firstKey = searchKeywords.first()
                collection.whereArrayContains("keywords", firstKey).limit(30).get().await()
            } else {
                collection.limit(30).get().await()
            }

            if (snapshot != null && !snapshot.isEmpty) {
                for (doc in snapshot.documents) {
                    val artNum = doc.getString("article_number").orEmpty()
                    val lawTitle = doc.getString("law_title").orEmpty()
                    val text = doc.getString("text").orEmpty()
                    val normTitle = normalize(lawTitle)
                    val normText = normalize(text)

                    val card = "📜 **$lawTitle** — المادة ($artNum):\n\"$text\""

                    if (targetArticle != null && artNum == targetArticle) {
                        collectedResults.add(card)
                    } else if (searchKeywords.isNotEmpty()) {
                        val matchesCount = searchKeywords.count { normText.contains(it) || normTitle.contains(it) }
                        if (matchesCount > 0) {
                            collectedResults.add(card)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // الاستمرار إلى المحلي عند انقطاع الاتصال
        }

        // 2. البحث الاحتياطي في الذاكرة المحلية (laws_data.json)
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
                    val lawTitle = obj.optString("law_title", "")
                    val text = obj.optString("text", "")
                    val normTitle = normalize(lawTitle)
                    val normText = normalize(text)

                    val card = "📜 **$lawTitle** — المادة ($artNum):\n\"$text\""

                    if (targetArticle != null && artNum == targetArticle) {
                        if (!collectedResults.any { it.contains(lawTitle) && it.contains("المادة ($artNum)") }) {
                            collectedResults.add(card)
                        }
                    } else if (targetArticle == null && searchKeywords.isNotEmpty()) {
                        val matchesCount = searchKeywords.count { normText.contains(it) || normTitle.contains(it) }
                        if (matchesCount >= 1 && !collectedResults.any { it.contains(lawTitle) && it.contains("المادة ($artNum)") }) {
                            collectedResults.add(card)
                        }
                    }
                }
            } catch (_: Exception) {
                // صامت
            }
        }

        // إذا وُجدت المواد في قاعدة البيانات أو الملف المحلي:
        if (collectedResults.isNotEmpty()) {
            return "🏛️ **المستندات المستخرجة من قاعدة البيانات الرسمية بالتطبيق:**\n\n" +
                    collectedResults.joinToString("\n\n────────────────\n\n")
        }

        // 3. في حال عدم وجود المادة في قاعدة البيانات:
        // الاستخراج عبر البحث التوثيقي الرسمي مع إلزام ذكر المصدر
        val strictLawPrompt = """
المطلوب استخراج النصوص التشريعية السورية الحرفية فقط للبحث التالي:
"$rawQ"

تنبيه وإلزام بالهيكلية الرسمية التالية:
ابدأ الرد حصراً بهذه العبارة التوضيحية:
"⚠️ **ملاحظة توثيقية:** لم يُعثر على هذا النص في قاعدة البيانات المحملة محلياً بالتطبيق، وتم استخراجه بالاستناد إلى المراجع التشريعية السورية المعتمدة عبر الإنترنت."

ثم اذكر لكل مادة بدقة متناهية:
1. 📜 **السند التشريعي:** اسم القانون كاملاً، رقم القانون أو المرسوم، وسنة صدوره وتعديلاته.
2. 🏛️ **مصدر التوثيق:** حدد المصدر الرسمي الصريح (الجريدة الرسمية السورية / منشورات وزارة العدل / أبحاث نقابة المحامين / موقع مجلس الشعب).
3. ⚖️ **رقم المادة ونصها الحرفي الصريح** دون أي تصرف أو تحريف.
4. يمنع التخمين أو التأليف منعاً باتاً. إذا لم تجد نصاً تشريعياً رسمياً سارياً، صرّح بوضوح: "لم أجد نصاً تشريعياً نافذاً بهذا الخصوص في المراجع الرسمية المعتمدة".
""".trimIndent()

        val aiResult = AIClient.fetchLawArticleFromAI(strictLawPrompt)
        return if (aiResult.isNotBlank()) {
            aiResult
        } else {
            "لم يتم العثور على أي نصوص تشريعية مطابقة في المراجع الرسمية المعتمدة."
        }
    }

    suspend fun searchRelevantLaws(query: String): String = searchRelevantLaws(null, query)
    suspend fun searchLaw(context: Context?, query: String): String = searchRelevantLaws(context, query)
}
