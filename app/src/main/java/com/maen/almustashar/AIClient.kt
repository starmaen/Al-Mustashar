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
    // المفاتيح تُحقن من BuildConfig عبر GitHub Secrets — لا توجد مفاتيح نصية هنا.
    // الترتيب المعتمد: Gemini أولاً (4 ثم 3.8 ثم البدلاء)، ثم Muse Spark لدقته، ثم OpenRouter، وGroq أخيراً.
    // أسماء الموديلات ثابتة هنا (مدروسة ومجربة) — لا Secrets لها لتفادي فخ القيم الفارغة.
    // Gemini 4 صدر اليوم وهو اختيارك الأول، ثم 3.8 (اشتراكك)، ثم 3.5-lite السريع
    private const val GEMINI_PRIMARY = "gemini-4-flash"
    private const val GEMINI_FALLBACK = "gemini-3.8-flash"
    // موديل Groq الأصلي السريع (المثبت تاريخياً في التطبيق) — والاكتشاف يرقّيه تلقائياً إن تقاعد
    private const val GROQ_MODEL = "llama-3.1-8b-instant"
    private const val OR_MODEL = "google/gemma-4-31b-it:free"
    private fun geminiKey(): String = try { BuildConfig.GEMINI_API_KEY } catch (_: Exception) { "" }
    private fun zenKey(): String = try { BuildConfig.OPENCODE_ZEN_API_KEY } catch (_: Exception) { "" }
    private fun groqKey(): String = try { BuildConfig.GROQ_API_KEY } catch (_: Exception) { "" }
    private fun openRouterKey(): String = try { BuildConfig.OPENROUTER_API_KEY } catch (_: Exception) { "" }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """أنت مستشار ومرجع قانوني سوري خبير ومتخصص في التشريعات والقضاء السوري.
مهمتك تقديم دراسة قانونية وافية وتحليل شامل معزز بالنصوص الرسمية بالهيكل الآتي:
1. ⚖️ التكييف والوصف القانوني الدقيق للواقعة (وفق تصنيفات القضاء السوري: مدني/جزائي/شرعي/إداري، مع تحديد المحكمة المختصة ودرجتها).
2. 📜 السند القانوني النافذ (اسم القانون ورقمه وسنته + أرقام المواد ونصوصها الصريحة من التشريع السوري ذي الصلة، مع ذكر التعديلات إن وجدت).
3. 💡 التحليل القانوني وإبداء الرأي والحل أو العقوبة المقررة (مع المدد والمواعيد القانونية كالتقادم ومهل الطعن).
4. 🧭 التوجيه العملي والإجراءات المتبعة أمام المحاكم والدوائر الرسمية السورية خطوة بخطوة.
قاعدة ملزمة: إن زُوّدت بمواد مسترجعة من قاعدة قوانين التطبيق فاعتمدها أولاً واستشهد بها حرفياً، ولا تخترع نصوص مواد. وإن لم تكفِ فأجب من معرفتك مع التنبيه أن النص يحتاج مراجعة.
قاعدة اللغة الصارمة (لمنع تداخل الاتجاهات):
- أجب باللغة العربية الفصحى حصراً ومن اليمين إلى اليسار. يمنع منعاً باتاً كتابة أي جملة أو عبارة باللغة الإنجليزية داخل الجواب.
- ترجم كل المصطلحات القانونية إلى العربية (مثال: الزوج بدل Husband، الزوجة بدل wife، الإجراءات بدل procedures، كاتب المحكمة بدل clerk of the court، المحكمة بدل the court، الشخصي بدل Personal، حكومي بدل governmental، تشريعي بدل legislative، قانوني بدل Legal). لا تترك أي مصطلح إنجليزي له مقابل عربي.
- عند الضرورة القصوى لاسم علم أجنبي لا يقبل الترجمة ضعه وحده بين قوسين عربيين ( ... ) في سطر مستقل أو آخر الجملة، ولا تدمجه وسط الكلام العربي.
- اكتب كل فقرة بسطر مستقل، وابدأ كل بند برقم عربي ثم نقطة، ولا تدمج لغتين في سطر واحد أبداً.
- يُمنع منعاً باتاً إخراج كلام مشوّش أو حشو أو خلط لغات غير مفهوم. إن لم تتوفر معرفة كافية فاكتب ذلك بالعربية الفصحى بوضوح، ولا تُخرج نصاً غير عربي إطلاقاً."""

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
   - الطلبات الختامية المرقمة بدقة، وخاتمة الاحترام والتوقيع المتطابق مع صفة الموكل.
قاعدة اللغة: صُغ المذكرة بالعربية الفصحى حصراً. يمنع إقحام أي كلمة إنجليزية لها مقابل عربي. أي اسم أجنبي ضروري يوضع بين قوسين بسطر مستقل ولا يُدمج وسط الجملة."""

    // ===== فلترة اللغة العامة: عربية افتراضياً، وإنجليزية فقط عند طلب صريح =====
    private const val AR_ONLY_RULE = "\n\n[قاعدة لغة ملزمة: أجب باللغة العربية الفصحى حصراً. يمنع أي جملة أو مصطلح إنجليزي له مقابل عربي. ترجم كل المصطلحات (الزوج، الزوجة، الإجراءات، المحكمة، كاتب المحكمة...). أي اسم أجنبي ضروري يوضع بين قوسين في آخر الجملة فقط.]"

    private fun wantsEnglish(userPrompt: String): Boolean {
        val q = userPrompt.lowercase()
        return q.contains("in english") || q.contains("answer in english") ||
            q.contains("english please") || q.contains("translate to english") ||
            userPrompt.contains("بالانكليز") || userPrompt.contains("بالإنكليز") ||
            userPrompt.contains("بالإنجليز") || userPrompt.contains("بالانجليز") ||
            userPrompt.contains("باللغة الانكليزية") || userPrompt.contains("باللغة الإنجليزية") ||
            userPrompt.contains("أجب بالانكليزية") || userPrompt.contains("أجب بالإنجليزية")
    }

    private fun withLangGuard(userPrompt: String): String =
        if (wantsEnglish(userPrompt)) userPrompt else userPrompt + AR_ONLY_RULE + NUMBERS_RULE

    // قاعدة الأرقام للمحاكم: الرقم رقماً دائماً — كتابته حروفاً خطأ مهني
    private const val NUMBERS_RULE = "\n[قاعدة أرقام ملزمة للمحاكم: اكتب كل الأرقام أرقاماً لاتينية كما هي تماماً (أرقام المواد والقوانين والسنوات والمدد والنسب). مثال: المادة 103، القانون 59 لعام 1953، مهلة 30 يوماً. يمنع منعاً باتاً كتابة أي رقم بالحروف (لا تكتب مائة وثلاثة أبداً). عند الاستشهاد بمادة اكتب رقمها رقمياً دائماً.]"

    // قاموس إسعافي لأشهر المصطلحات الإنجليزية التي تسربت سابقاً (من لقطات المستخدم)
    // يطبق فقط عندما لم يطلب المستخدم الإنجليزية.
    private val EN_AR_DICT: List<Pair<Regex, String>> = listOf(
        Regex("\\bclerk of the court\\b", RegexOption.IGNORE_CASE) to "كاتب المحكمة",
        Regex("\\bclerk of the wife\\b", RegexOption.IGNORE_CASE) to "كاتب المحكمة",
        Regex("\\bpersonal status\\b", RegexOption.IGNORE_CASE) to "الأحوال الشخصية",
        Regex("\\bthe court\\b", RegexOption.IGNORE_CASE) to "المحكمة",
        Regex("\\bprocedures\\b", RegexOption.IGNORE_CASE) to "الإجراءات",
        Regex("\\bprocedure\\b", RegexOption.IGNORE_CASE) to "الإجراء",
        Regex("\\bhusband\\b", RegexOption.IGNORE_CASE) to "الزوج",
        Regex("\\bwife\\b", RegexOption.IGNORE_CASE) to "الزوجة",
        Regex("\\bspouse\\b", RegexOption.IGNORE_CASE) to "الزوج الآخر",
        Regex("\\blegislative\\b", RegexOption.IGNORE_CASE) to "تشريعي",
        Regex("\\bgovernmental\\b", RegexOption.IGNORE_CASE) to "حكومي",
        Regex("\\blegal\\b", RegexOption.IGNORE_CASE) to "قانوني",
        Regex("\\bprobabilities\\b", RegexOption.IGNORE_CASE) to "الاحتمالات",
        Regex("\\bprobability\\b", RegexOption.IGNORE_CASE) to "الاحتمال",
        Regex("\\bprepare\\b", RegexOption.IGNORE_CASE) to "إعداد",
        Regex("\\bfiled\\b", RegexOption.IGNORE_CASE) to "المُقدَّم",
        Regex("\\bbinding\\b", RegexOption.IGNORE_CASE) to "مُلزِم",
        Regex("\\bexam\\b", RegexOption.IGNORE_CASE) to "الفحص",
        Regex("\\bproducts\\b", RegexOption.IGNORE_CASE) to "المنتجات",
        Regex("\\bclerk\\b", RegexOption.IGNORE_CASE) to "الكاتب",
        Regex("\\bcourt\\b", RegexOption.IGNORE_CASE) to "المحكمة",
        Regex("\\bpersonal\\b", RegexOption.IGNORE_CASE) to "الشخصي",
        Regex("\\bfrente\\b", RegexOption.IGNORE_CASE) to ""
    )

    private fun arabizeCommonTerms(text: String): String {
        var out = text
        for ((re, ar) in EN_AR_DICT) out = re.replace(out, ar)
        // تنظيف فراغات مزدوجة قد تنتج عن حذف كلمات غريبة
        out = out.replace(Regex("[ \\t]{2,}"), " ")
        return out
    }

    // عزل أي مقطع لاتيني متبقٍ بمحارف FSI/PDI حتى لا يكسر اتجاه السطر العربي
    private val LATIN_RUN = Regex("[A-Za-z][A-Za-z0-9 \\t\\-_,.;:'\"()/]*[A-Za-z0-9)]|[A-Za-z]")
    private fun isolateBidi(text: String): String {
        return LATIN_RUN.replace(text) { m -> "⁦${m.value}⁩" }
    }

    // ===== بوابة اللغة على المخرَج: لا يُعرض للمستخدم نص غير عربي أو مشوّش أبداً =====
    private val ARABIC_LETTER = Regex("[\u0621-\u064A\u0671-\u06D3\u06FA-\u06FF]")
    private val LATIN_LETTER = Regex("[A-Za-z]")

    // نص مقبول: عربي الطابع (العربية على الأقل ثلثا الحروف). الركام الأجنبي/المشوّش مرفوض.
    private fun looksArabic(text: String): Boolean {
        if (text.isBlank() || text.startsWith("❌")) return true
        val arabic = ARABIC_LETTER.findAll(text).count()
        if (arabic == 0) return false
        val latin = LATIN_LETTER.findAll(text).count()
        return arabic * 2 >= latin
    }

    // رسالة الفشل الأخيرة: نمنع عرض أي مخرَج أجنبي أو مشوّش مهما كان السبب
    private const val ARABIC_ONLY_FAILURE =
        "❌ تعذر الحصول على إجابة عربية سليمة من مزود الخدمة حالياً. يرجى إعادة المحاولة بعد قليل، أو استخدام وضع البحث في الويب 🌐."

    // إعادة صياغة عربية واحدة عبر Gemini — بلا استدعاء متداخل (يمنع أي تكرار لا نهائي)
    private fun repairArabicText(text: String): String {
        val fix = "أعد كتابة النص التالي باللغة العربية الفصحى الخالصة حصراً. ممنوع أي حرف لاتيني أو أي كلمة بلغة أخرى إطلاقاً؛ النص الحالي مشوّش ويجب أن يصبح عربية فصيحة مفهومة. إن كان مشوّشاً تماماً لا يحمل معنى فاكتب: تعذر فهم النص. حافظ على المعنى والأرقام والنصوص القانونية المقتبسة حرفياً. قاعدة الأرقام: اكتب كل الأرقام أرقاماً لاتينية كما هي (المادة 103). أخرج النص العربي فقط بلا مقدمات ولا شروح:\n\n$text"
        for (m in geminiChain()) {
            when (val r = callGemini(m, fix)) {
                is AIResult.Success -> {
                    lastWorkingGemini = m
                    markProvider("Gemini:$m-repair")
                    return arabizeCommonTerms(r.text)
                }
                is AIResult.Error -> lastGeminiError = r
            }
        }
        return text // بقي الأصل إن فشلت الإعادة
    }

    private fun purifiedAnswer(text: String, userPrompt: String): String {
        val fin = finalizeAnswer(text, userPrompt)
        if (wantsEnglish(userPrompt) || looksArabic(fin)) return fin
        val repaired = repairArabicText(fin)
        return if (looksArabic(repaired)) isolateBidi(arabizeCommonTerms(repaired)) else ARABIC_ONLY_FAILURE
    }

    private fun finalizeAnswer(raw: String, userPrompt: String): String {
        if (raw.startsWith("❌")) return raw
        return if (wantsEnglish(userPrompt)) isolateBidi(raw)
        else isolateBidi(arabizeCommonTerms(raw))
    }

    // آخر مزود نجح فعلاً — للتحقق أن Gemini هو العامل وليس Groq
    var lastProvider: String = "—"
        private set

    private fun markProvider(name: String) {
        lastProvider = name
        try { android.util.Log.d("AIClient", "Provider used: $name") } catch (_: Exception) {}
    }

    // سلسلة Gemini بالترتيب: 4 ثم 3.8 ثم 3.5 — يجرّب كل اسم فعلياً حتى لو غاب عن قائمة النماذج المعلنة لمفتاحك
    // آخر نموذج نجح يُجرَّب أولاً لتفادي انتظار محاولات فاشلة في كل سؤال (تسريع)
    @Volatile private var lastWorkingGemini: String? = null
    private fun geminiChain(): List<String> =
        (listOfNotNull(lastWorkingGemini) + listOf(GEMINI_PRIMARY, GEMINI_FALLBACK, "gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-2.5-flash", resolveGemini())).distinct()

    private var lastGeminiError: AIResult.Error? = null

    private fun tryGeminiChain(call: (String) -> AIResult, origPrompt: String): String? {
        lastGeminiError = null
        for (m in geminiChain()) {
            when (val r = call(m)) {
                is AIResult.Success -> { lastWorkingGemini = m; markProvider("Gemini:$m"); return purifiedAnswer(r.text, origPrompt) }
                is AIResult.Error -> lastGeminiError = r
            }
        }
        return null
    }

    // 1. الاستشارة القانونية — الترتيب: Gemini (4/3.8/3.5) ثم Muse Spark ثم OpenRouter وGroq أخيراً
    suspend fun askLegalQuestion(prompt: String, context: Context? = null): String = withContext(Dispatchers.IO) {
        val guarded = withLangGuard(prompt)
        tryGeminiChain({ m -> callGemini(m, guarded) }, prompt)?.let { return@withContext it }

        val sparkRes = callMuseSpark(guarded)
        if (sparkRes is AIResult.Success) { markProvider("MuseSpark"); return@withContext purifiedAnswer(sparkRes.text, prompt) }

        val orRes = callOpenRouter(guarded, SYSTEM_PROMPT)
        if (orRes is AIResult.Success) { markProvider("OpenRouter"); return@withContext purifiedAnswer(orRes.text, prompt) }

        val gm = resolveGroq()
        val groqRes = callGroq(gm, guarded, SYSTEM_PROMPT)
        if (groqRes is AIResult.Success) { markProvider("Groq:$gm"); return@withContext purifiedAnswer(groqRes.text, prompt) }

        return@withContext when {
            lastGeminiError != null -> "❌ فشل الاتصال: ${lastGeminiError?.message}"
            sparkRes is AIResult.Error -> "❌ فشل اتصال ماوي سبارك: ${sparkRes.message}"
            orRes is AIResult.Error -> "❌ فشل اتصال البديل الثالث: ${orRes.message}"
            groqRes is AIResult.Error -> "❌ فشل اتصال Groq: ${groqRes.message}"
            else -> "❌ تعذر إتمام الطلب، يرجى التحقق من اتصال الإنترنت."
        }
    }

    // 2. البحث العام — نفس الترتيب: Gemini ثم Muse Spark ثم OpenRouter وGroq أخيراً
    suspend fun askGeneralQuestion(prompt: String, context: Context? = null): String = withContext(Dispatchers.IO) {
        val guarded = withLangGuard(prompt)
        tryGeminiChain({ m -> callGemini(m, guarded) }, prompt)?.let { return@withContext it }

        val sparkRes = callMuseSpark(guarded)
        if (sparkRes is AIResult.Success) { markProvider("MuseSpark"); return@withContext purifiedAnswer(sparkRes.text, prompt) }

        val orRes = callOpenRouter(guarded, SYSTEM_PROMPT)
        if (orRes is AIResult.Success) { markProvider("OpenRouter"); return@withContext purifiedAnswer(orRes.text, prompt) }

        val gm = resolveGroq()
        val groqRes = callGroq(gm, guarded, SYSTEM_PROMPT)
        if (groqRes is AIResult.Success) { markProvider("Groq:$gm"); return@withContext purifiedAnswer(groqRes.text, prompt) }

        return@withContext "❌ تعذر الاتصال بمزود الخدمة."
    }

    // 3. البحث في مواد القوانين
    suspend fun fetchLawArticleFromAI(rawQ: String): String = withContext(Dispatchers.IO) {
        val searchPrompt = "استخرج النص الحرفي والكامل للمادة القانونية التالية من التشريعات السورية بدقة متناهية:\n$rawQ"
        val guarded = withLangGuard(searchPrompt)
        tryGeminiChain({ m -> callGemini(m, guarded) }, rawQ)?.let { return@withContext it }

        val sparkRes = callMuseSpark(guarded)
        if (sparkRes is AIResult.Success) { markProvider("MuseSpark"); return@withContext purifiedAnswer(sparkRes.text, rawQ) }

        val orRes = callOpenRouter(guarded, SYSTEM_PROMPT)
        if (orRes is AIResult.Success) { markProvider("OpenRouter"); return@withContext purifiedAnswer(orRes.text, rawQ) }

        val groqRes = callGroq(resolveGroq(), guarded, SYSTEM_PROMPT)
        if (groqRes is AIResult.Success) { markProvider("Groq"); return@withContext purifiedAnswer(groqRes.text, rawQ) }

        return@withContext ""
    }

    // 4. صياغة المذكرات والاستدعاءات القضائية
    suspend fun draftLegalDocument(
        userNotes: String,
        attachmentsBase64: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val guardedNotes = withLangGuard(userNotes)
        val fullPrompt = DRAFTING_SYSTEM_PROMPT + "\n\nمعطيات ومطالب الصياغة:\n" + guardedNotes

        tryGeminiChain({ m -> callGeminiWithParts(m, fullPrompt, attachmentsBase64) }, userNotes)?.let { return@withContext it }

        val sparkRes = callMuseSpark(fullPrompt)
        if (sparkRes is AIResult.Success) { markProvider("MuseSpark"); return@withContext purifiedAnswer(sparkRes.text, userNotes) }

        val orRes = callOpenRouter(guardedNotes, DRAFTING_SYSTEM_PROMPT)
        if (orRes is AIResult.Success) { markProvider("OpenRouter"); return@withContext purifiedAnswer(orRes.text, userNotes) }

        val groqRes = callGroq(resolveGroq(), guardedNotes, DRAFTING_SYSTEM_PROMPT)
        if (groqRes is AIResult.Success) { markProvider("Groq"); return@withContext purifiedAnswer(groqRes.text, userNotes) }

        return@withContext when {
            lastGeminiError != null -> "❌ فشل التوليد: ${lastGeminiError?.message}"
            sparkRes is AIResult.Error -> "❌ فشل ماوي سبارك: ${sparkRes.message}"
            orRes is AIResult.Error -> "❌ فشل البديل الثالث: ${orRes.message}"
            groqRes is AIResult.Error -> "❌ فشل البديل: ${groqRes.message}"
            else -> "❌ تعذر إتمام الصياغة، يرجى إعادة المحاولة."
        }
    }

    // اكتشاف ذاتي: يسأل كل مزود عن موديلاته المتاحة لمفتاحك ويختار منها (حماية من موت الأسماء)
    private var geminiResolved: Pair<String, Long>? = null
    private var groqResolved: Pair<String, Long>? = null
    private var orResolved: Pair<String, Long>? = null

    private fun errFor(code: Int, body: String): AIResult {
        return if (body.trimStart().startsWith("<")) {
            AIResult.Error("HTTP $code: محجوب من الشبكة/البوابة")
        } else {
            AIResult.Error("HTTP $code: ${body.take(150)}")
        }
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val b = Request.Builder().url(url)
            headers.forEach { (k, v) -> b.addHeader(k, v) }
            client.newCall(b.build()).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (r.isSuccessful) s else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fresh(p: Pair<String, Long>?): String? =
        if (p != null && System.currentTimeMillis() - p.second < 24 * 3600 * 1000L) p.first else null

    private fun resolveGemini(): String {
        fresh(geminiResolved)?.let { return it }
        val key = geminiKey()
        if (key.isNotBlank()) {
            val list = httpGet("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
            // الأفضلية لاختيارك في الصورة: 3.8 ثم 3.5 ثم 3.5-lite ثم أي Flash متاح
            pickFromList(list, "models", "name", "models/") { it.contains("3.8-flash", true) }?.let {
                geminiResolved = it to System.currentTimeMillis()
                return it
            }
            pickFromList(list, "models", "name", "models/") { it.contains("3.5-flash-lite", true) }?.let {
                geminiResolved = it to System.currentTimeMillis()
                return it
            }
            pickFromList(list, "models", "name", "models/") { it.contains("3.5-flash", true) }?.let {
                geminiResolved = it to System.currentTimeMillis()
                return it
            }
            pickFromList(list, "models", "name", "models/") { it.contains("flash", true) }?.let {
                geminiResolved = it to System.currentTimeMillis()
                return it
            }
        }
        return GEMINI_PRIMARY
    }

    private fun resolveGroq(): String {
        fresh(groqResolved)?.let { return it }
        val key = groqKey()
        if (key.isNotBlank()) {
            val list = httpGet(
                "https://api.groq.com/openai/v1/models",
                mapOf("Authorization" to "Bearer $key")
            )
            pickFromList(list, "data", "id", "") { it.contains("llama", true) || it.contains("gpt-oss", true) }?.let {
                groqResolved = it to System.currentTimeMillis()
                return it
            }
        }
        return GROQ_MODEL
    }

    private fun resolveOR(): String {
        fresh(orResolved)?.let { return it }
        val list = httpGet("https://openrouter.ai/api/v1/models")
        pickFromList(list, "data", "id", "") { it.contains(":free", true) }?.let {
            orResolved = it to System.currentTimeMillis()
            return it
        }
        return OR_MODEL
    }

    private fun pickFromList(
        listJson: String?, arrayKey: String, idKey: String, prefix: String,
        prefer: (String) -> Boolean
    ): String? {
        if (listJson.isNullOrBlank() || listJson.trimStart().startsWith("<")) return null
        return try {
            val arr = JsonParser.parseString(listJson).asJsonObject.getAsJsonArray(arrayKey)
                ?: return null
            val ids = arr.mapNotNull {
                try {
                    it.asJsonObject.get(idKey)?.asString?.removePrefix(prefix)
                } catch (_: Exception) {
                    null
                }
            }.filter { it.isNotBlank() }
            ids.firstOrNull(prefer) ?: ids.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    // 5. سؤال المكتبة القانونية — إجابة حصراً من مراجعها مع ذكر المصدر
    suspend fun askLibraryQuestion(question: String, passages: String): String = withContext(Dispatchers.IO) {
        val libPrompt = """أنت أمين مكتبة قانونية سورية. أجب حصراً من النصوص المرجعية المرفقة أدناه، واستشهد في كل فقرة باسم المرجع ورقم المقطع. إن لم تجد الجواب فيها فقل بوضوح: لا يوجد في المكتبة ما يجيب عن هذا السؤال. يمنع الاستعانة بأي معرفة خارجية.
أجب باللغة العربية الفصحى حصراً. يمنع أي مصطلح إنجليزي له مقابل عربي.

المراجع:
$passages

السؤال: ${withLangGuard(question)}"""
        tryGeminiChain({ m -> callGeminiWithParts(m, libPrompt, emptyList()) }, question)?.let { return@withContext it }

        val sparkRes = callMuseSpark(libPrompt)
        if (sparkRes is AIResult.Success) { markProvider("MuseSpark"); return@withContext purifiedAnswer(sparkRes.text, question) }

        val orRes = callOpenRouter(libPrompt, SYSTEM_PROMPT)
        if (orRes is AIResult.Success) { markProvider("OpenRouter"); return@withContext purifiedAnswer(orRes.text, question) }

        val groqRes = callGroq(resolveGroq(), libPrompt, libPrompt.substringBefore("\n\nالمراجع:"))
        if (groqRes is AIResult.Success) { markProvider("Groq"); return@withContext purifiedAnswer(groqRes.text, question) }

        return@withContext "❌ تعذر الاتصال بمزود الخدمة."
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
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${geminiKey()}"
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
                    errFor(response.code, respStr)
                }
            }
        } catch (e: Exception) {
            AIResult.Error(e.localizedMessage ?: "timeout")
        }
    }

    private fun callGroq(model: String, prompt: String, systemPrompt: String = SYSTEM_PROMPT): AIResult {
        return try {
            val url = "https://api.groq.com/openai/v1/chat/completions"
            val rootJson = JsonObject()
            rootJson.addProperty("model", model)
            val messagesArr = JsonArray()

            val sysMsg = JsonObject()
            sysMsg.addProperty("role", "system")
            sysMsg.addProperty("content", systemPrompt)
            messagesArr.add(sysMsg)

            val userMsg = JsonObject()
            userMsg.addProperty("role", "user")
            userMsg.addProperty("content", prompt)
            messagesArr.add(userMsg)

            rootJson.add("messages", messagesArr)

            val body = rootJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${groqKey()}")
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
                    errFor(response.code, respStr)
                }
            }
        } catch (e: Exception) {
            AIResult.Error(e.localizedMessage ?: "خطأ في الاتصال")
        }
    }

    // ماوي سبارك 1.3 المجاني عبر OpenCode Zen (الثاني بعد Gemini لدقته، وGroq أخيراً).
    // Endpoint: https://opencode.ai/zen/v1/responses — بروتوكول OpenAI Responses.
    // المفتاح من لوحة https://opencode.ai/console ويُحقن عبر OPENCODE_ZEN_API_KEY.
    private fun callMuseSpark(prompt: String): AIResult {
        return try {
            val key = zenKey()
            if (key.isBlank()) return AIResult.Error("مفتاح ماوي سبارك غير مضبوط")
            val url = "https://opencode.ai/zen/v1/responses"
            val rootJson = JsonObject()
            rootJson.addProperty("model", "muse-spark-1.3-contributor-free")
            rootJson.addProperty("input", "$SYSTEM_PROMPT\n\nالسؤال القانوني: $prompt")

            val body = rootJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val text = extractResponsesText(respStr)
                    if (!text.isNullOrBlank()) {
                        AIResult.Success(text)
                    } else {
                        AIResult.Error("رد فارغ من ماوي سبارك")
                    }
                } else {
                    errFor(response.code, respStr)
                }
            }
        } catch (e: Exception) {
            AIResult.Error(e.localizedMessage ?: "خطأ في الاتصال")
        }
    }

    // استخراج النص من استجابة OpenAI Responses بعدة أشكال محتملة
    private fun extractResponsesText(respStr: String): String? {
        return try {
            val parsed = JsonParser.parseString(respStr).asJsonObject
            // الشكل 1: حقل output_text المباشر
            parsed.get("output_text")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { return it }
            // الشكل 2: مصفوفة output -> message -> content -> text
            val sb = StringBuilder()
            parsed.getAsJsonArray("output")?.forEach { item ->
                val obj = item?.asJsonObject ?: return@forEach
                obj.getAsJsonArray("content")?.forEach { part ->
                    val p = part?.asJsonObject ?: return@forEach
                    p.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { sb.append(it) }
                }
            }
            sb.toString().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // المزود الثالث المجاني (OpenRouter — نماذج free). يعمل بنفس بروتوكول OpenAI.
    private fun callOpenRouter(prompt: String, systemPrompt: String = SYSTEM_PROMPT): AIResult {
        return try {
            val key = openRouterKey()
            if (key.isBlank()) return AIResult.Error("مفتاح البديل الثالث غير مضبوط")
            val url = "https://openrouter.ai/api/v1/chat/completions"
            val rootJson = JsonObject()
            rootJson.addProperty("model", resolveOR())
            val messagesArr = JsonArray()

            val sysMsg = JsonObject()
            sysMsg.addProperty("role", "system")
            sysMsg.addProperty("content", systemPrompt)
            messagesArr.add(sysMsg)

            val userMsg = JsonObject()
            userMsg.addProperty("role", "user")
            userMsg.addProperty("content", prompt)
            messagesArr.add(userMsg)

            rootJson.add("messages", messagesArr)

            val body = rootJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("HTTP-Referer", "https://github.com/starmaen/Al-Mustashar")
                .addHeader("X-Title", "Al-Mustashar Legal App")
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
                        AIResult.Error("رد فارغ من البديل الثالث")
                    }
                } else {
                    errFor(response.code, respStr)
                }
            }
        } catch (e: Exception) {
            AIResult.Error(e.localizedMessage ?: "خطأ في الاتصال")
        }
    }
}
