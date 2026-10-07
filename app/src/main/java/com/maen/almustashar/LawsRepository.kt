package com.maen.almustashar

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object LawsRepository {

    data class LawMeta(
        val id: String,
        val name: String,
        val category: String?,
        val drivePdfUrl: String? = null
    )

    data class Article(
        val lawId: String,
        val lawName: String,
        val number: String,
        val text: String,
        val keywords: List<String> = emptyList(),
        val drivePdfUrl: String? = null
    )

    private const val WORKERS_LAW_ID = "state_workers_law"
    private const val WORKERS_LAW_NAME = "القانون الأساسي للعاملين في الدولة"
    private const val WORKERS_DRIVE_URL = "https://drive.google.com/file/d/1Uwrq2rfykYbDoa88Rp2wxNZaM-rQhFkt/view?usp=drivesdk"

    // قاعدة بيانات مدمجة لقانون العاملين لضمان عملها فوراً عند جميع المستخدمين بدون انتظار أي سكربت خارجي
    private val defaultWorkersArticles = listOf(
        Article(
            WORKERS_LAW_ID,
            WORKERS_LAW_NAME,
            "1",
            "يقصد بالتعابير الآتية في معرض تطبيق أحكام هذا القانون المعاني المبينة إلى جانب كل منها:\n" +
            "العامل: كل من يعين في إحدى الجهات العامة في وظيفة ملازمة لها.\n" +
            "الجهة العامة: الوزارات والإدارات والهيئات العامة والمؤسسات والشركات والمنشآت العامة والبلديات والوحدات الإدارية المحلية.",
            listOf("تعريف", "العامل", "الجهة العامة", "وظيفة"),
            WORKERS_DRIVE_URL
        ),
        Article(
            WORKERS_LAW_ID,
            WORKERS_LAW_NAME,
            "2",
            "تسري أحكام هذا القانون على العاملين في الوزارات والإدارات والمؤسسات والشركات العامة والمنشآت التابعة لها والبلديات وسائر أجهزة الدولة.",
            listOf("نطاق السريان", "الوزارات", "الإدارات", "أجهزة الدولة"),
            WORKERS_DRIVE_URL
        ),
        Article(
            WORKERS_LAW_ID,
            WORKERS_LAW_NAME,
            "5",
            "يشترط فيمن يعين في إحدى وظائف الجهات العامة أن يكون:\n" +
            "1- متمتعاً بالجنسية العربية السورية منذ خمس سنوات على الأقل.\n" +
            "2- قد أتم الثامنة عشرة من عمره.\n" +
            "3- خالياً من الأمراض والعاهات التي تمنعه من القيام بالوظيفة.\n" +
            "4- غير محكوم بجناية أو جنحة شائنة.\n" +
            "5- غير معزول أو مطرود من إحدى وظائف الجهات العامة.",
            listOf("شروط التعيين", "الجنسية", "العمر", "الأمراض", "غير محكوم"),
            WORKERS_DRIVE_URL
        ),
        Article(
            WORKERS_LAW_ID,
            WORKERS_LAW_NAME,
            "10",
            "تحدد بمرسوم بناء على اقتراح الوزير المختص الشروط الخاصة للتعيين في بعض الوظائف ذات الطبيعة الفنية أو التخصصية بما يتناسب مع طبيعة مهامها.",
            listOf("مرسوم", "شروط خاصة", "وظائف فنية", "تخصصية"),
            WORKERS_DRIVE_URL
        )
    )

    private fun normalizeDigits(s: String): String {
        val ar = "٠١٢٣٤٥٦٧٨٩"; val fa = "۰۱۲۳۴۵۶۷۸۹"; var r = s
        for (i in 0..9) {
            r = r.replace(ar[i], ('0' + i)).replace(fa[i], ('0' + i))
        }
        return r
    }

    private fun normalizeArabic(s: String): String =
        s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ة", "ه").replace("ى", "ي")

    private fun extractNumber(q: String): String? {
        val n = normalizeDigits(q)
        val patterns = listOf(
            Regex("""(?:المادة|الماده)\s*رقم\s*[:\(]?\s*(\d+)"""),
            Regex("""نص\s+(?:المادة|الماده)\s*[:\(]?\s*(\d+)"""),
            Regex("""(?:المادة|الماده)\s*[:\(]?\s*(\d+)\s*\)?"""),
            Regex("""\b(?:مادة|ماده)\s*[:\(]?\s*(\d+)"""),
            Regex("""article\s*[:\(]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            p.find(n)?.let { return it.groupValues[1] }
        }
        val bare = n.trim()
        if (bare.matches(Regex("""\d+"""))) return bare
        return null
    }

    suspend fun loadLawsList(): List<LawMeta> {
        val baseList = mutableListOf(
            LawMeta(WORKERS_LAW_ID, WORKERS_LAW_NAME, "قوانين إدارية ووظيفية", WORKERS_DRIVE_URL)
        )
        return try {
            val db = FirebaseFirestore.getInstance()
            val snap = db.collection("laws").get().await()
            val remote = snap.documents.map {
                val title = it.getString("name") ?: it.getString("shortTitle") ?: it.getString("title") ?: it.id
                LawMeta(it.id, title, it.getString("category"), it.getString("drivePdfUrl"))
            }
            (baseList + remote).distinctBy { it.id }.sortedBy { it.name }
        } catch (_: Exception) {
            baseList
        }
    }

    private fun detectLawId(question: String, laws: List<LawMeta>): String? {
        val qn = normalizeArabic(question)
        if (qn.contains("عاملين") || qn.contains("موظف") || qn.contains("وظيف")) {
            return WORKERS_LAW_ID
        }
        for (law in laws) {
            val nameWords = normalizeArabic(law.name).split(" ").filter { it.length >= 3 && !isStop(it) }
            if (nameWords.any { qn.contains(it) }) return law.id
        }
        return null
    }

    suspend fun searchRelevantLaws(
        question: String,
        selectedLawId: String?,
        limit: Int = 6
    ): String {
        val laws = loadLawsList()
        val num = extractNumber(question)
        val targetLawId = selectedLawId ?: detectLawId(question, laws)

        if (num != null) {
            val res = fetchByNumber(num, targetLawId, laws)
            if (res != null) return res
            return if (targetLawId != null) {
                val name = laws.find { it.id == targetLawId }?.name ?: targetLawId
                "⚠️ المادة $num غير موجودة ضمن $name."
            } else {
                "⚠️ المادة $num غير موجودة في القوانين المتاحة حاليًا."
            }
        }

        val all = loadAllArticles(laws)
        val qn = normalizeArabic(question)
        val matched = all.filter { a ->
            (targetLawId == null || a.lawId == targetLawId) &&
            (normalizeArabic(a.text).contains(qn) || a.keywords.any { normalizeArabic(it).contains(qn) })
        }.take(limit)

        if (matched.isEmpty()) {
            return "⚠️ لم يتم العثور على نص مطابق لهذا البحث."
        }

        return matched.joinToString("\n\n───────────────────────\n\n") { formatArticleOutput(it) }
    }

    private suspend fun fetchByNumber(n: String, targetLawId: String?, laws: List<LawMeta>): String? {
        // فحص المواد المدمجة أولاً
        val localMatches = defaultWorkersArticles.filter { it.number == n && (targetLawId == null || targetLawId == WORKERS_LAW_ID) }
        if (localMatches.isNotEmpty()) {
            return localMatches.joinToString("\n\n───────────────────────\n\n") { formatArticleOutput(it) }
        }

        // فحص Firestore
        return try {
            val db = FirebaseFirestore.getInstance()
            if (targetLawId != null) {
                val law = laws.find { it.id == targetLawId }
                val doc = db.collection("laws").document(targetLawId).collection("articles").document(n).get().await()
                if (doc.exists()) {
                    val txt = doc.getString("text") ?: doc.getString("content") ?: ""
                    val pdf = doc.getString("drivePdfUrl") ?: law?.drivePdfUrl
                    formatArticleOutput(Article(targetLawId, law?.name ?: targetLawId, n, txt, emptyList(), pdf))
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadAllArticles(laws: List<LawMeta>): List<Article> {
        val result = mutableListOf<Article>()
        result.addAll(defaultWorkersArticles)
        try {
            val db = FirebaseFirestore.getInstance()
            for (law in laws) {
                if (law.id == WORKERS_LAW_ID) continue
                val snap = db.collection("laws").document(law.id).collection("articles").get().await()
                for (doc in snap.documents) {
                    val txt = doc.getString("text") ?: doc.getString("content") ?: continue
                    result.add(Article(law.id, law.name, doc.id, txt, emptyList(), law.drivePdfUrl))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun formatArticleOutput(a: Article): String {
        val linkBlock = if (!a.drivePdfUrl.isNullOrEmpty()) {
            "\n\n📂 [تحميل وقراءة القانون الأصلي من Google Drive]:\n${a.drivePdfUrl}"
        } else ""
        return "📖 ${a.lawName} - المادة ${a.number}:\n\n${a.text}$linkBlock"
    }

    private fun isStop(w: String) = setOf("من", "في", "على", "عن", "إلى", "قانون", "مادة", "رقم").contains(w)
}
