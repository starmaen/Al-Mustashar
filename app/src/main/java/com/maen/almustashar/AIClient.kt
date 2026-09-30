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

    // قائمة المفاتيح: يمكنك إضافة مفتاحين أو أكثر مفصولة بفاصلة
    private val GEMINI_KEYS = BuildConfig.GEMINI_API_KEY.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    private val GEMINI_MODELS = listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash")

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

        if (GEMINI_KEYS.isEmpty()) return@withContext "❌ لم يتم تعيين مفاتيح API."

        // تجربة المفاتيح بالترتيب (تدوير تلقائي عند انتهاء الباقة)
        for (key in GEMINI_KEYS) {
            for (model in GEMINI_MODELS) {
                val r = callGemini(key, model, prompt)
                if (r is AIResult.Success) return@withContext r.text
            }
        }
        return@withContext "❌ تم استهلاك الحد المسموح لجميع المفاتيح المتوفرة حالياً، يرجى المحاولة بعد قليل."
    }

    suspend fun askGeneralQuestion(question: String): String = withContext(Dispatchers.IO) {
        val generalPrompt = "أنت مستشار قانوني سوري خبير. أجب على السؤال القانوني بدقة:\n\nالسؤال: $question"
        if (GEMINI_KEYS.isEmpty()) return@withContext "❌ لم يتم تعيين مفاتيح API."

        for (key in GEMINI_KEYS) {
            for (model in GEMINI_MODELS) {
                val r = callGemini(key, model, generalPrompt)
                if (r is AIResult.Success) return@withContext r.text
            }
        }
        return@withContext "❌ تم استهلاك الحد المسموح لجميع المفاتيح، يرجى المحاولة لاحقاً."
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
