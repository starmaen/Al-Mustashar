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

    private fun callGemini(model: String, prompt: String): AIResult {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$GEMINI_KEY"

            val rootJson = JsonObject()
            val contentsArr = JsonArray()
            val contentObj = JsonObject()
            val partsArr = JsonArray()
            val partObj = JsonObject()

            partObj.addProperty("text", "$SYSTEM_PROMPT\n\nالسؤال القانوني: $prompt")
            partsArr.add(partObj)
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
                    AIResult.Error("HTTP ${response.code}: $respStr")
                }
            }
        } catch (e: Exception) {
            AIResult.Error("Exception: ${e.localizedMessage}")
        }
    }

    private fun callGroq(model: String, prompt: String): AIResult {
        return try {
            val url = "https://api.groq.com/openai/v1/chat/completions"
            val root = JsonObject().apply {
                addProperty("model", model)
                val messages = JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", SYSTEM_PROMPT)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", prompt)
                    })
                }
                add("messages", messages)
                addProperty("temperature", 0.4)
            }

            val body = root.toString().toRequestBody("application/json".toMediaType())
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
                        AIResult.Error("رد فارغ من مزود الخدمة")
                    }
                } else {
                    AIResult.Error("HTTP ${response.code}: $respStr")
                }
            }
        } catch (e: Exception) {
            AIResult.Error("Exception: ${e.localizedMessage}")
        }
    }
}
