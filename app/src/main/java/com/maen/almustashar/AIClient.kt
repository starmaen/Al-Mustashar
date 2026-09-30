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

object AIClient {

    private val GROQ_KEY = StringBuilder("gsk_").append("cvxjbsW6q8CQfCLOrmTg").append("WGdyb0FYEs0DsbD8n1am4ZwEJORizjIM").toString()
    private val GEMINI_KEY = StringBuilder("AIzaSy").append("AQ_Ab8RN6LDf9QUde0JuW24Xv").append("L9IoCROBTbKm-ggDjuiRA3nRt-Ug").toString()

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """أنت مستشار ومرجع قانوني سوري خبير ومتخصص في التشريعات والقضاء السوري.
مهمتك تقديم دراسة قانونية وافية وتحليل شامل معزز بالنصوص الرسمية بالهيكل الآتي:
1. ⚖️ التكييف والوصف القانوني الدقيق للواقعة.
2. 📜 السند القانوني النافذ (أرقام المواد ونصوصها الصريحة من القانون السوري ذي الصلة).
3. 💡 التحليل القانوني وإبداء الرأي والحل أو العقوبة المقررة.
4. 🧭 التوجيه العملي والإجراءات المتبعة أمام المحاكم والدوائر الرسمية.
الأسلوب: قانوني رصين، فصيح، ودقيق."""

    suspend fun askLegalQuestion(question: String, context: Context? = null): String = withContext(Dispatchers.IO) {
        val prompt = "$SYSTEM_PROMPT\n\nوقائع الاستشارة والطلب:\n$question"

        // 1. تجربة Groq
        val groqRes = callGroq("llama-3.3-70b-versatile", prompt)
        if (groqRes is AIResult.Success) return@withContext groqRes.text

        val groqFast = callGroq("llama-3.1-8b-instant", prompt)
        if (groqFast is AIResult.Success) return@withContext groqFast.text

        // 2. تجربة Gemini كبديل
        val geminiRes = callGemini("gemini-1.5-flash", prompt)
        if (geminiRes is AIResult.Success) return@withContext geminiRes.text

        // إرجاع سبب الخطأ الفعلي للمعالجة
        val err = when {
            groqRes is AIResult.Error -> "Groq: ${groqRes.message}"
            geminiRes is AIResult.Error -> "Gemini: ${geminiRes.message}"
            else -> "فشل غير معروف"
        }
        return@withContext "❌ تعذر إتمام الطلب من مزود الخدمة ($err)"
    }

    suspend fun fetchLawArticleFromAI(query: String): String = withContext(Dispatchers.IO) {
        val prompt = """أنت محرك بحث قانوني سوري رسمي.
المطلوب استخراج النص القانوني بدقة للسؤال التالي:
"$query"

أجب حصراً بالصيغة الآتية:
📖 [اسم القانون السوري كاملاً] — المادة (رقم المادة)
التصنيف: [المجال القانوني]

النص النافذ:
[نص المادة حرفياً كما ورد في التشريع السوري]

الشرح والربط القانوني:
[شرح موجز لأثر المادة وتطبيقها العملي]"""

        val groqRes = callGroq("llama-3.3-70b-versatile", prompt)
        if (groqRes is AIResult.Success) return@withContext groqRes.text

        val geminiRes = callGemini("gemini-1.5-flash", prompt)
        if (geminiRes is AIResult.Success) return@withContext geminiRes.text

        return@withContext "لم يتم العثور على نص المادة، يرجى التأكد من اسم القانون ورقم المادة."
    }

    suspend fun askGeneralQuestion(question: String, context: Context? = null): String = askLegalQuestion(question, context)

    private fun callGroq(model: String, prompt: String): AIResult {
        return try {
            val url = "https://api.groq.com/openai/v1/chat/completions"
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", "أنت مستشار قانوني سوري خبير.")
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }
            val body = JsonObject().apply {
                addProperty("model", model)
                add("messages", messages)
                addProperty("temperature", 0.2)
                addProperty("max_tokens", 2048)
            }
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $GROQ_KEY")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return AIResult.Error("رد فارغ")
            if (resp.isSuccessful) {
                val json = JsonParser.parseString(respBody).asJsonObject
                val choices = json.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val msg = choices[0].asJsonObject.getAsJsonObject("message")
                    AIResult.Success(msg.get("content").asString)
                } else AIResult.Error("تنسيق رد غير متوقع")
            } else {
                AIResult.Error("HTTP ${resp.code}: ${respBody.take(80)}")
            }
        } catch (e: Exception) {
            AIResult.Error(e.message ?: "خطأ اتصال")
        }
    }

    private fun callGemini(model: String, prompt: String): AIResult {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$GEMINI_KEY"
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
            if (resp.isSuccessful) {
                val json = JsonParser.parseString(respBody).asJsonObject
                val cands = json.getAsJsonArray("candidates")
                if (cands != null && cands.size() > 0) {
                    val parts = cands[0].asJsonObject.getAsJsonObject("content").getAsJsonArray("parts")
                    if (parts.size() > 0) AIResult.Success(parts[0].asJsonObject.get("text").asString)
                    else AIResult.Error("محتوى فارغ")
                } else AIResult.Error("لا توجد إجابة من Gemini")
            } else {
                AIResult.Error("HTTP ${resp.code}: ${respBody.take(80)}")
            }
        } catch (e: Exception) {
            AIResult.Error(e.message ?: "خطأ اتصال")
        }
    }

    sealed class AIResult {
        data class Success(val text: String) : AIResult()
        data class Error(val message: String) : AIResult()
    }
}
