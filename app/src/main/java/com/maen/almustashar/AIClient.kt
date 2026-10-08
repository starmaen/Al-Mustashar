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
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """أنت مستشار ومرجع قانوني سوري خبير ومتخصص في التشريعات والقضاء السوري.
مهمتك تقديم دراسة قانونية وافية وتحليل شامل معزز بالنصوص الرسمية بالهيكل الآتي:
1. ⚖️ التكييف والوصف القانوني الدقيق للواقعة.
2. 📜 السند القانوني النافذ (أرقام المواد ونصوصها الصريحة من القانون السوري ذي الصلة).
3. 💡 التحليل القانوني وإبداء الرأي والحل أو العقوبة المقررة.
4. 🧭 التوجيه العملي والإجراءات المتبعة أمام المحاكم والدوائر الرسمية."""

    private const val DRAFTING_SYSTEM_PROMPT = """أنت محامٍ ومستشار قضائي سوري متمرس وخبير في أصول المحاكمات السورية والصياغة القانونية الرصينة.
مهمتك: صياغة مذكرات قضائية، لوائح جوابية، واستدعاءات رسمية وإدارية للبلديات والمحاكم بدقة لغوية وقانونية متناهية.

قواعد الصياغة الصارمة لمنع أي تناقض:
1. الترويسة الرسمية الكاملة: ابدأ دائماً بترويسة المحكمة أو الدائرة الموجه إليها، ثم أطراف الدعوى بوضوح (الجهة المدعية/المستدعية، الجهة المدعى عليها إن وجدت، رقم الأساس والجهة، والموضوع).
2. الالتزام المطلق بصفة الموكل:
   - إذا كان موكلك هو (المدعي / المستدعي): تُصاغ المذكرة للمطالبة بحقوقه وتثبيت طلباته أو استدعاء البلدية باسمه، والتوقيع يكون حصراً: (وكيل المدعي / وكيل المستدعي).
   - إذا كان موكلك هو (المدعى عليه): تُصاغ المذكرة كدفاع وجواب ودحض لادعاءات الخصم، والتوقيع يكون حصراً: (وكيل المدعى عليه).
   - يمنع منعاً باتاً الخلط بين المدعي والمدعى عليه أو التناقض بين متن المذكرة وخاتمتها.
3. الترتيب القضائي للمذكرة:
   - الترويسة والأطراف.
   - من حيث الشكل (قبول المذكرة/الاستدعاء شكلاً).
   - من حيث الوقائع والأسانيد (سرد الوقائع والاستناد إلى الوثائق والمستندات بأسلوب قانوني جازم).
   - من حيث القانون (المواد القانونية السورية النافذة).
   - الطلبات الختامية المرقمة بدقة، وخاتمة الاحترام والتوقيع المتطابق مع صفة الموكل."""

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

        return@withContext "❌ تعذر الاتصال بمزود الخدمة."
    }

    // 3. البحث في مواد القوانين
    suspend fun fetchLawArticleFromAI(rawQ: String): String = withContext(Dispatchers.IO) {
        val searchPrompt = "استخرج النص الحرفي والكامل للمادة القانونية التالية من التشريعات السورية بدقة متناهية:\n$rawQ"
        val res1 = callGemini("gemini-3.8-flash", searchPrompt)
        if (res1 is AIResult.Success) return@withContext res1.text

        val res2 = callGemini("gemini-3.5-flash-lite", searchPrompt)
        if (res2 is AIResult.Success) return@withContext res2.text

        val groqRes = callGroq("llama-3.1-8b-instant", searchPrompt)
        if (groqRes is AIResult.Success) return@withContext groqRes.text

        return@withContext ""
    }

    // 4. صياغة المذكرات والاستدعاءات القضائية
    suspend fun draftLegalDocument(
        userNotes: String,
        attachmentsBase64: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val fullPrompt = DRAFTING_SYSTEM_PROMPT + "\n\nمعطيات ومطالب الصياغة:\n" + userNotes

        val res1 = callGeminiWithParts("gemini-3.8-flash", fullPrompt, attachmentsBase64)
        if (res1 is AIResult.Success) return@withContext res1.text

        val res2 = callGeminiWithParts("gemini-3.5-flash-lite", fullPrompt, attachmentsBase64)
        if (res2 is AIResult.Success) return@withContext res2.text

        val groqRes = callGroq("llama-3.1-8b-instant", fullPrompt)
        if (groqRes is AIResult.Success) return@withContext groqRes.text

        return@withContext when {
            res1 is AIResult.Error -> "❌ فشل التوليد: ${res1.message}"
            res2 is AIResult.Error -> "❌ فشل التوليد: ${res2.message}"
            groqRes is AIResult.Error -> "❌ فشل البديل: ${groqRes.message}"
            else -> "❌ تعذر إتمام الصياغة، يرجى إعادة المحاولة."
        }
    }

    private fun callGemini(model: String, prompt: String): AIResult {
        return callGeminiWithParts(model, "$SYSTEM_PROMPT\n\nالسؤال القانوني: $prompt", emptyList())
    }

    private fun callGeminiWithParts(
        model: String,
        promptText: String,
        attachments: List<Pair<String, String>>
    ): AIResult {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$GEMINI_KEY"
            val rootJson = JsonObject()
            val contentsArr = JsonArray()
            val contentObj = JsonObject()
            val partsArr = JsonArray()

            val textPart = JsonObject()
            textPart.addProperty("text", promptText)
            partsArr.add(textPart)

            for (att in attachments) {
                val inlineDataPart = JsonObject()
                val inlineData = JsonObject()
                inlineData.addProperty("mime_type", att.second)
                inlineData.addProperty("data", att.first)
                inlineDataPart.add("inline_data", inlineData)
                partsArr.add(inlineDataPart)
            }

            contentObj.add("parts", partsArr)
            contentsArr.add(contentObj)
            rootJson.add("contents", contentsArr)

            val body = rootJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = JsonParser.parseString(respStr).asJsonObject
                    val text = parsed.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString
                    if (!text.isNullOrBlank()) {
                        AIResult.Success(text)
                    } else {
                        AIResult.Error("رد فارغ من مزود الخدمة")
                    }
                } else {
                    AIResult.Error("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            AIResult.Error(e.localizedMessage ?: "timeout")
        }
    }

    private fun callGroq(model: String, prompt: String): AIResult {
        return try {
            val url = "https://api.groq.com/openai/v1/chat/completions"
            val rootJson = JsonObject()
            rootJson.addProperty("model", model)
            val messagesArr = JsonArray()

            val sysMsg = JsonObject()
            sysMsg.addProperty("role", "system")
            sysMsg.addProperty("content", DRAFTING_SYSTEM_PROMPT)
            messagesArr.add(sysMsg)

            val userMsg = JsonObject()
            userMsg.addProperty("role", "user")
            userMsg.addProperty("content", prompt)
            messagesArr.add(userMsg)

            rootJson.add("messages", messagesArr)

            val body = rootJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $GROQ_KEY")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = JsonParser.parseString(respStr).asJsonObject
                    val text = parsed.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString
                    if (!text.isNullOrBlank()) {
                        AIResult.Success(text)
                    } else {
                        AIResult.Error("رد فارغ من Groq")
                    }
                } else {
                    AIResult.Error("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            AIResult.Error(e.localizedMessage ?: "خطأ في الاتصال")
        }
    }
}
