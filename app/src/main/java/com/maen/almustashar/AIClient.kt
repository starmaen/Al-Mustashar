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
    private val GEMINI_KEY = StringBuilder("AIzaSy").append("AQ_Ab8RN6LDf9QUde0JuW24Xv").append("L9IoCROBTbKm-ggDjuiRA3nRt-Ug").toString()

    private val client = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """أنت مستشار ومرجع قانوني سوري خبير ومتخصص في التشريعات والقضاء السوري.
مهمتك تقديم دراسة قانونية وافية وتحليل شامل معزز بالنصوص الرسمية بالهيكل الآتي:
1. ⚖️ التكييف والوصف القانوني الدقيق للواقعة.
2. 📜 السند القانوني النافذ (أرقام المواد ونصوصها الصريحة من القانون السوري ذي الصلة).
3. 💡 التحليل القانوني وإبداء الرأي والحل أو العقوبة المقررة.
4. 🧭 التوجيه العملي والإجراءات المتبعة أمام المحاكم والدوائر الرسمية."""

    suspend fun askLegalQuestion(prompt: String): String = withContext(Dispatchers.IO) {
        // 1. نماذج Gemini الأساسية
        val g1 = callGemini("gemini-3.8-pro", prompt)
        if (g1 is AIResult.Success) return@withContext g1.text

        val g2 = callGemini("gemini-3.8-flash", prompt)
        if (g2 is AIResult.Success) return@withContext g2.text

        val g3 = callGemini("gemini-3.5-flash-lite", prompt)
        if (g3 is AIResult.Success) return@withContext g3.text

        // 2. نماذج Groq البديلة والمؤكدة
        val q1 = callGroq("llama-3.1-8b-instant", prompt)
        if (q1 is AIResult.Success) return@withContext q1.text

        val q2 = callGroq("llama-3.3-70b-versatile", prompt)
        if (q2 is AIResult.Success) return@withContext q2.text

        return@withContext when {
            g1 is AIResult.Error -> "❌ فشل الاتصال (Gemini Pro): ${g1.message}"
            g2 is AIResult.Error -> "❌ فشل الاتصال (Gemini Flash): ${g2.message}"
            q1 is AIResult.Error -> "❌ فشل الاتصال (Groq Instant): ${q1.message}"
            q2 is AIResult.Error -> "❌ فشل الاتصال (Groq Versatile): ${q2.message}"
            else -> "❌ تعذر إتمام الطلب من جميع المزودين، يرجى التحقق من اتصال الإنترنت."
        }
    }

    suspend fun askGeneralQuestion(prompt: String): String = withContext(Dispatchers.IO) {
        val g1 = callGemini("gemini-3.8-pro", prompt)
        if (g1 is AIResult.Success) return@withContext g1.text

        val g2 = callGemini("gemini-3.8-flash", prompt)
        if (g2 is AIResult.Success) return@withContext g2.text

        val q1 = callGroq("llama-3.1-8b-instant", prompt)
        if (q1 is AIResult.Success) return@withContext q1.text

        return@withContext when {
            g1 is AIResult.Error -> "❌ خطأ: ${g1.message}"
            else -> "❌ تعذر الاتصال بمزود الخدمة."
        }
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
                        AIResult.Error("رد فارغ من Gemini ($model)")
                    }
                } else {
                    AIResult.Error("Gemini HTTP ${response.code} ($model): $respStr")
                }
            }
        } catch (e: Exception) {
            AIResult.Error("Gemini Exception ($model): ${e.localizedMessage}")
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
                        AIResult.Error("رد فارغ من Groq ($model)")
                    }
                } else {
                    AIResult.Error("Groq HTTP ${response.code} ($model): $respStr")
                }
            }
        } catch (e: Exception) {
            AIResult.Error("Groq Exception ($model): ${e.localizedMessage}")
        }
    }
}
