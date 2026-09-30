package com.maen.almustashar

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class AIResult {
    data class Success(val text: String) : AIResult()
    data class Error(val message: String) : AIResult()
}

object AIClient {
    private val GROQ_KEY = StringBuilder("gsk_").append("cvxjbsW6q8CQfCLOrmTg").append("WGdyb0FYEs0DsbD8n1am4ZwEJORizjIM").toString()
        private val GEMINI_KEY = StringBuilder("AQ.Ab8RN6KmTYMlQDnnJ").append("gx2n4-OCuZYx7sJ6oVOk3TXUHvstG0jJg").toString()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """أنت مستشار ومرجع قانوني سوري خبير ومتخصص في التشريعات والقضاء السوري.
مهمتك تقديم دراسة قانونية وافية وتحليل شامل معزز بالنصوص الرسمية بالهيكل الآتي:
1. ⚖️ التكييف والوصف القانوني الدقيق للواقعة.
2. 📜 السند القانوني النافذ (أرقام المواد ونصوصها الصريحة من القانون السوري ذي الصلة).
3. 💡 التحليل القانوني وإبداء الرأي والحل أو العقوبة المقررة.
4. 🧭 التوجيه العملي والإجراءات المتبعة أمام المحاكم والدوائر الرسمية."""

    // 1. الاستشارة القانونية
    suspend fun askLegalQuestion(prompt: String, context: Context? = null): String = withContext(Dispatchers.IO) {
        val res1 = callGemini("gemini-3.8-flash", prompt)
        if (res1 is AIResult.Success) return@withContext res1.text

        val res2 = callGemini("gemini-3.5-flash-lite", prompt)
        if (res2 is AIResult.Success) return@withContext res2.text

        val groqRes = callGroq("llama-3.1-8b-instant", prompt)
        if (groqRes is AIResult.Success) return@withContext groqRes.text

        return@withContext when {
            res1 is AIResult.Error -> "❌ فشل الاتصال: ${res1.message}"
            res2 is AIResult.Error -> "❌ فشل الاتصال البديل: ${res2.message}"
            groqRes is AIResult.Error -> "❌ فشل اتصال Groq: ${groqRes.message}"
            else -> "❌ تعذر إتمام الطلب، يرجى التحقق من اتصال الإنترنت."
        }
    }

    // 2. البحث العام
    suspend fun askGeneralQuestion(prompt: String, context: Context? = null): String = withContext(Dispatchers.IO) {
        val res1 = callGemini("gemini-3.8-flash", prompt)
        if (res1 is AIResult.Success) return@withContext res1.text

        val res2 = callGemini("gemini-3.5-flash-lite", prompt)
        if (res2 is AIResult.Success) return@withContext res2.text

        val groqRes = callGroq("llama-3.1-8b-instant", prompt)
        if (groqRes is AIResult.Success) return@withContext groqRes.text

        return@withContext when {
            res1 is AIResult.Error -> "❌ خطأ: ${res1.message}"
            res2 is AIResult.Error -> "❌ خطأ البديل: ${res2.message}"
            groqRes is AIResult.Error -> "❌ خطأ Groq: ${groqRes.message}"
            else -> "❌ تعذر الاتصال بمزود الخدمة."
        }
    }

    // 3. البحث في مواد القوانين
    suspend fun fetchLawArticleFromAI(query: String): String {
        val prompt = """
أنت المستشار القانوني الرسمي للجمهورية العربية السورية.
المطلوب منك الإجابة بدقة متناهية ودون أي تخمين أو افتراض حول الاستفسار التالي:
""

القواعد الصارمة للإجابة:
1. اذكر نص المادة القانونية بدقة وحرفية.
2. حدد اسم القانون الرسمي كاملاً، ورقم القانون أو المرسوم التشريعي وتاريخ صدوره وسنة نفاذه (مثال: المرسوم التشريعي رقم 148 لعام 1949 وتعديلاته).
3. بيّن المرجع الرسمي للنص المعتمد (الجريدة الرسمية السورية / منشورات وزارة العدل / أبحاث نقابة المحامين / تشريعات مجلس الشعب).
4. قدم شرحاً وتوضيحاً قانونياً موجزاً وعملياً لكيفية تطبيق هذه المادة وفقاً لموضوع السؤال.
5. تنبيه قطعي: يمنع التخمين أو اختلاق أرقام مواد. إذا لم تكن المادة أو القانون مؤكداً في التشريع السوري النافذ، قل صراحة: (لم أجد نصاً تشريعياً نافذاً بهذا الخصوص في المراجع الرسمية السورية المعتمدة).

الهيكلية الإلزامية للرد:
📜 **السند التشريعي المعتمد:**
[اسم القانون كاملاً - رقم المرسوم/القانون - سنة الصدور والتعديل]

⚖️ **نص المادة ([رقم المادة]):**
"[النص الحرفي للمادة]"

🏛️ **المرجعية الرسمية:**
[الجريدة الرسمية / وزارة العدل / مجلس الشعب]

💡 **الشرح والتوضيح القانوني:**
[توضيح مبسط لكيفية تطبيق المادة على السؤال]
""".trimIndent()

        val jsonBody = JSONObject().apply {
            val contents = JSONArray().apply {
                val partObj = JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                }
                put(partObj)
            }
            put("contents", contents)
            val genConfig = JSONObject().apply {
                put("temperature", 0.0)
            }
            put("generationConfig", genConfig)
        }

        val primaryResult = callGeminiApi("gemini-3.8-flash", jsonBody)
        if (primaryResult != null && primaryResult.isNotBlank()) return primaryResult

        val secondaryResult = callGeminiApi("gemini-3.5-flash-lite", jsonBody)
        if (secondaryResult != null && secondaryResult.isNotBlank()) return secondaryResult

        val groqFallback = callGroqFallback(prompt)
        return groqFallback ?: "تعذر الاتصال بالمراجع القانونية حالياً. يرجى إعادة المحاولة."
    }

