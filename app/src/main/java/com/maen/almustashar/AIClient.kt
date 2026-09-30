package com.maen.almustashar

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

object AIClient {

    private val GROQ_KEY = BuildConfig.GROQ_API_KEY.trim()
    private val GEMINI_KEYS = BuildConfig.GEMINI_API_KEY.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private val GROQ_MODELS = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
    private val GEMINI_MODELS = listOf("gemini-1.5-flash", "gemini-2.0-flash")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """أنت مستشار قانوني سوري خبير ومرجع معتمد في التشريعات والقضاء السوري.
مهمتك تقديم استشارة قانونية وافية ودقيقة ومعززة بالسند والنص القانوني السوري النافذ.
هيكل الإجابة:
1. التكييف القانوني
2. الحكم والحل القانوني أو العقوبة المقررة
3. السند القانوني النافذ (أرقام المواد)
4. التوجيه والإجراء العملي
ضوابط: أسلوب قانوني فصيح ومباشر."""

    suspend fun askLegalQuestion(question: String): String = withContext(Dispatchers.IO) {
        val relevantLaws = try { LawsRepository.searchRelevantLaws(question) } catch (e: Exception) { "" }
        val prompt = if (relevantLaws.isNotEmpty()) {
            "$SYSTEM_PROMPT\n\n📚 نصوص قانونية ذات صلة:\n$relevantLaws\n\nالسؤال:\n$question"
        } else {
            "$SYSTEM_PROMPT\n\nالسؤال:\n$question"
        }

        // تجربة Groq أولاً إن وجد مفتاحه
        if (GROQ_KEY.isNotEmpty()) {
            for (m in GROQ_MODELS) {
                val r = callGroq(m, prompt)
                if (r is AIResult.Success) return@withContext r.text
            }
        }

        // إذا فشل أو لم يتوفر، نجرب Gemini
        if (GEMINI_KEYS.isNotEmpty()) {
            for (k in GEMINI_KEYS) {
                for (m in GEMINI_MODELS) {
                    val r = callGemini(k, m, prompt)
                    if (r is AIResult.Success) return@withContext r.text
                }
            }
        }

        return@withContext "❌ تعذر الحصول على رد من الخوادم حالياً، يرجى المحاولة لاحقاً."
    }

    suspend fun askGeneralQuestion(question: String): String = withContext(Dispatchers.IO) {
        val prompt = "أنت مستشار قانوني سوري خبير. أجب على السؤال القانوني بدقة:\n\nالسؤال: $question"

        if (GROQ_KEY.isNotEmpty()) {
            for (m in GROQ_MODELS) {
                val r = callGroq(m, prompt)
                if (r is AIResult.Success) return@withContext r.text
            }
        }

        if (GEMINI_KEYS.isNotEmpty()) {
            for (k in GEMINI_KEYS) {
                for (m in GEMINI_MODELS) {
                    val r = callGemini(k, m, prompt)
                    if (r is AIResult.Success) return@withContext r.text
                }
            }
        }

        return@withContext "❌ تعذر الحصول على رد، يرجى المحاولة لاحقاً."
    }

    private fun callGroq(model: String, prompt: String): AIResult {
        return try {
            val url = "https://api.groq.com/openai/v1/chat/completions"
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }
            val body = JsonObject().apply {
                addProperty("model", model)
                add("messages", messages)
                addProperty("max_tokens", 2000)
            }
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $GROQ_KEY")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return AIResult.Error("رد فارغ")
            if (resp.code == 200) {
                val json = JsonParser.parseString(respBody).asJsonObject
                val choices = json.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val msg = choices[0].asJsonObject.getAsJsonObject("message")
                    AIResult.Success(msg.get("content").asString)
                } else AIResult.Error("رد فارغ")
            } else {
                AIResult.Error("HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            AIResult.Error("اتصال: ${e.message?.take(50)}")
        }
    }

    private fun callGemini(key: String, model: String, prompt: String): AIResult {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
            val body = JsonObject().apply {
                add("contents", JsonArray().apply {
                    add(JsonObject().apply {
                        add("parts", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("text", prompt)
                            })
                        })
                    })
                })
            }
            val req = Request.Builder().url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return AIResult.Error("رد فارغ")
            if (resp.code == 200) {
                val json = JsonParser.parseString(respBody).asJsonObject
                val cands = json.getAsJsonArray("candidates")
                if (cands != null && cands.size() > 0) {
                    val parts = cands[0].asJsonObject.getAsJsonObject("content").getAsJsonArray("parts")
                    if (parts.size() > 0) AIResult.Success(parts[0].asJsonObject.get("text").asString)
                    else AIResult.Error("رد فارغ")
                } else AIResult.Error("رد فارغ")
            } else {
                AIResult.Error("HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            AIResult.Error("اتصال: ${e.message?.take(50)}")
        }
    }

    sealed class AIResult {
        data class Success(val text: String) : AIResult()
        data class Error(val message: String) : AIResult()
    }
}
