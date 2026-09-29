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

    private const val GEMINI_KEY = BuildConfig.GEMINI_API_KEY
    private const val GROQ_KEY = BuildConfig.GROQ_API_KEY

    private val GEMINI_MODELS = listOf("gemini-3.7-flash", "gemini-3.5-flash-lite")
    private val GROQ_MODELS = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """أنت مستشار قانوني سوري خبير.
حلل السؤال وأعط إجابة دقيقة باللغة العربية الفصحى.

⚠️ قواعد إلزامية غير قابلة للاجتهاد أو التأويل:

1. لكل مادة تذكرها، اعرضها بهذا التنسيق الحرفي:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⚖️ المادة {رقم} — {اسم القانون}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📜 [1] النص الأصلي ({السنة الأصلية}):
{originalText}

📝 [2] التعديلات:
• {law} ({year}) — {change}

✅ [3] النص النافذ المعتمد أصولاً:
{effectiveText}

🔖 الحالة: {status}
📅 آخر تعديل: {lastAmendedYear}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

2. لا يجوز الاجتهاد خارج النص النافذ (effectiveText).
3. إذا كانت الحالة "ملغاة" — اصرح: «هذه المادة ملغاة ولا يُعمل بها».
4. إذا لم تجد تعديلات — اكتب: «لم تُعدَّل».
5. الاستشارة القانونية تُبنى حصراً على النص النافذ.
6. في نهاية الرد: أضف خلاصة عملية بجملتين، مع الاستشهاد بالأرقام.

تعليمات عامة:
- لا تستخدم الجداول
- لا تستخدم رموز # * `
- استخدم الترقيم العادي فقط
- اذكر رقم المادة واسم القانون بدقة
- اللغة: عربية فصحى قانونية

المرجع الأساسي: القوانين السورية النافذة."""

    suspend fun askLegalQuestion(question: String): String = withContext(Dispatchers.IO) {
        val errors = StringBuilder()

        // 1. البحث في القوانين المرفوعة
        val relevantLaws = try {
            LawsRepository.searchRelevantLaws(question)
        } catch (e: Exception) {
            ""
        }

        // 2. بناء البرومبت مع النصوص القانونية
        val enrichedPrompt = if (relevantLaws.isNotEmpty()) {
            "$SYSTEM_PROMPT\n\n" +
            "📚 نصوص قانونية ذات صلة من قاعدة البيانات:\n\n" +
            "$relevantLaws\n\n" +
            "───────────────────────\n\n" +
            "السؤال:\n$question\n\n" +
            "اعتمد على النصوص أعلاه في إجابتك، واذكر أرقام المواد."
        } else {
            "$SYSTEM_PROMPT\n\nالسؤال:\n$question"
        }

        // 3. جرّب Gemini
        for (model in GEMINI_MODELS) {
            val r = callGemini(model, enrichedPrompt)
            if (r is AIResult.Success) return@withContext r.text
            errors.append("Gemini/$model: ${(r as? AIResult.Error)?.message ?: "مشغول"}\n")
        }

        // 4. جرّب Groq
        for (model in GROQ_MODELS) {
            val r = callGroq(model, enrichedPrompt)
            if (r is AIResult.Success) return@withContext r.text
            errors.append("Groq/$model: ${(r as? AIResult.Error)?.message ?: "مشغول"}\n")
        }

        return@withContext "❌ فشل جميع المزودين:\n\n$errors"
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
            val respBody = resp.body?.string() ?: return AIResult.Error("لا يوجد رد")
            when (resp.code) {
                200 -> {
                    val json = JsonParser.parseString(respBody).asJsonObject
                    val cands = json.getAsJsonArray("candidates")
                    if (cands != null && cands.size() > 0) {
                        val parts = cands[0].asJsonObject.getAsJsonObject("content").getAsJsonArray("parts")
                        if (parts.size() > 0) AIResult.Success(parts[0].asJsonObject.get("text").asString)
                        else AIResult.Error("رد فارغ")
                    } else AIResult.Error("رد فارغ")
                }
                503, 429 -> AIResult.Error("ضغط")
                404 -> AIResult.Error("الموديل غير موجود")
                400 -> AIResult.Error("طلب خاطئ")
                403 -> AIResult.Error("المفتاح غير مصرّح")
                else -> AIResult.Error("HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            AIResult.Error("اتصال: ${e.message?.take(50)}")
        }
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
            val respBody = resp.body?.string() ?: return AIResult.Error("لا يوجد رد")
            when (resp.code) {
                200 -> {
                    val json = JsonParser.parseString(respBody).asJsonObject
                    val choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val msg = choices[0].asJsonObject.getAsJsonObject("message")
                        AIResult.Success(msg.get("content").asString)
                    } else AIResult.Error("رد فارغ")
                }
                429 -> AIResult.Error("حد الاستخدام")
                404 -> AIResult.Error("الموديل غير موجود")
                401 -> AIResult.Error("المفتاح خطأ")
                else -> AIResult.Error("HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            AIResult.Error("اتصال: ${e.message?.take(50)}")
        }
    }


    suspend fun askGeneralQuestion(question: String): String = withContext(Dispatchers.IO) {
        val errors = StringBuilder()
        val generalPrompt = """أنت مستشار قانوني سوري خبير.
أجب على السؤال القانوني بدقة ووضوح بالعربية الفصحى.
يمكنك استخدام معرفتك القانونية العامة (القوانين السورية والعربية والدولية).
لا تستخدم الجداول أو رموز # * \`.
اعطِ إجابة شاملة مع أمثلة إن أمكن."""

        val full = "$generalPrompt\n\nالسؤال:\n$question"

        for (model in GEMINI_MODELS) {
            val r = callGemini(model, full)
            if (r is AIResult.Success) return@withContext r.text
            errors.append("Gemini/$model: ${(r as? AIResult.Error)?.message ?: "مشغول"}\n")
        }

        for (model in GROQ_MODELS) {
            val r = callGroq(model, full)
            if (r is AIResult.Success) return@withContext r.text
            errors.append("Groq/$model: ${(r as? AIResult.Error)?.message ?: "مشغول"}\n")
        }

        return@withContext "❌ فشل: \n$errors"
    }

    sealed class AIResult {
        data class Success(val text: String) : AIResult()
        data class Error(val message: String) : AIResult()
    }
}
