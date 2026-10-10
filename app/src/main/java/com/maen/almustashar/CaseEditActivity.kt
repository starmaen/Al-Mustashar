package com.maen.almustashar

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.UUID

class CaseEditActivity : AppCompatActivity() {

    private var caseId: String? = null

    private lateinit var etTitle: EditText
    private lateinit var etBasisNumber: EditText
    private lateinit var etCaseYear: EditText
    private lateinit var etCourt: EditText
    private lateinit var etChamber: EditText
    private lateinit var etJudgeName: EditText
    private lateinit var etClient: EditText
    private lateinit var etClientRole: EditText
    private lateinit var etClientPhone: EditText
    private lateinit var etOpponentName: EditText
    private lateinit var etOpponentLawyer: EditText
    private lateinit var etWitnesses: EditText
    private lateinit var etDate: EditText
    private lateinit var etNextSessionDate: EditText
    private lateinit var etLastSessionDecision: EditText
    private lateinit var etNextSessionRequired: EditText
    private lateinit var etSummary: EditText
    private lateinit var etSessions: EditText
    private lateinit var etProcedures: EditText
    private lateinit var etStatus: EditText
    private lateinit var etFinalJudgment: EditText

    private lateinit var tvEditTitle: TextView
    private lateinit var btnDelete: Button
    private lateinit var btnDecisions: Button
    private lateinit var btnDraftForCase: Button
    private lateinit var btnAddDocument: Button
    private lateinit var btnCloudBackup: Button
    private lateinit var layoutDocumentsList: LinearLayout

    // قائمة المرفقات: (Name, FilePath, MimeType, CloudUrl)
    data class CaseDoc(val name: String, val path: String, val type: String, var url: String = "")
    private val attachedDocs = mutableListOf<CaseDoc>()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { savePickedFile(it) }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(singleCaseJson().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "حُفظت نسخة الدعوى على الجهاز/الذاكرة", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل التصدير: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // خيارات مكان الحفظ: الحساب / الجهاز / Drive — تظهر عند كل حفظ
    private fun showSaveOptions() {
        AlertDialog.Builder(this)
            .setTitle("أين تحفظ ملف الدعوى؟")
            .setItems(arrayOf("☁️ في حسابي (Firebase — يُحدّث الأرشيف)", "📱 نسخة على الجهاز / الذاكرة الخارجية", "💾 نسخة على Google Drive")) { _, which ->
                when (which) {
                    0 -> save()
                    1 -> exportSingleCase()
                    2 -> uploadCaseJsonToDrive()
                }
            }
            .show()
    }

    private fun collectCase(userId: String, timestamp: Long): Case? {
        val title = etTitle.text?.toString()?.trim() ?: ""
        if (title.isEmpty()) {
            Toast.makeText(this, "أدخل موضوع الدعوى", Toast.LENGTH_SHORT).show()
            return null
        }
        return Case(
            title = title,
            basisNumber = etBasisNumber.text?.toString()?.trim() ?: "",
            caseYear = etCaseYear.text?.toString()?.trim() ?: "",
            court = etCourt.text?.toString()?.trim() ?: "",
            chamber = etChamber.text?.toString()?.trim() ?: "",
            judgeName = etJudgeName.text?.toString()?.trim() ?: "",
            clientName = etClient.text?.toString()?.trim() ?: "",
            clientRole = etClientRole.text?.toString()?.trim().let { if (it.isNullOrEmpty()) "مدعٍ" else it },
            clientPhone = etClientPhone.text?.toString()?.trim() ?: "",
            opponentName = etOpponentName.text?.toString()?.trim() ?: "",
            opponentLawyer = etOpponentLawyer.text?.toString()?.trim() ?: "",
            witnesses = etWitnesses.text?.toString()?.trim() ?: "",
            date = etDate.text?.toString()?.trim() ?: "",
            nextSessionDate = etNextSessionDate.text?.toString()?.trim() ?: "",
            lastSessionDecision = etLastSessionDecision.text?.toString()?.trim() ?: "",
            nextSessionRequired = etNextSessionRequired.text?.toString()?.trim() ?: "",
            summary = etSummary.text?.toString()?.trim() ?: "",
            sessions = etSessions.text?.toString()?.trim() ?: "",
            procedures = etProcedures.text?.toString()?.trim() ?: "",
            status = etStatus.text?.toString()?.trim().let { if (it.isNullOrEmpty()) "قيد النظر" else it },
            finalJudgment = etFinalJudgment.text?.toString()?.trim() ?: "",
            documentsNotes = serializeDocs(),
            userId = userId,
            timestamp = timestamp
        )
    }

    private fun singleCaseJson(): String {
        val user = FirebaseAuth.getInstance().currentUser
        val c = collectCase(user?.uid ?: "", System.currentTimeMillis()) ?: return "{}"
        return JSONObject().apply {
            put("version", 1)
            put("title", c.title)
            put("basisNumber", c.basisNumber)
            put("caseYear", c.caseYear)
            put("court", c.court)
            put("chamber", c.chamber)
            put("judgeName", c.judgeName)
            put("clientName", c.clientName)
            put("clientRole", c.clientRole)
            put("clientPhone", c.clientPhone)
            put("opponentName", c.opponentName)
            put("opponentLawyer", c.opponentLawyer)
            put("witnesses", c.witnesses)
            put("date", c.date)
            put("nextSessionDate", c.nextSessionDate)
            put("lastSessionDecision", c.lastSessionDecision)
            put("nextSessionRequired", c.nextSessionRequired)
            put("summary", c.summary)
            put("sessions", c.sessions)
            put("procedures", c.procedures)
            put("status", c.status)
            put("finalJudgment", c.finalJudgment)
            put("documentsNotes", c.documentsNotes)
        }.toString(2)
    }

    private fun exportSingleCase() {
        if ((etTitle.text?.toString()?.trim() ?: "").isEmpty()) {
            Toast.makeText(this, "أدخل موضوع الدعوى أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "daawa_${etBasisNumber.text?.toString()?.trim().ifNullOrBlank("بدون-رقم")}.json"
        exportLauncher.launch(name)
    }

    private fun String?.ifNullOrBlank(default: String): String =
        if (this.isNullOrBlank()) default else this

    // رفع نسخة JSON من الدعوى إلى Drive (نص مباشر — بلا ملف وسيط)
    private fun uploadCaseJsonToDrive() {
        if ((etTitle.text?.toString()?.trim() ?: "").isEmpty()) {
            Toast.makeText(this, "أدخل موضوع الدعوى أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "سجّل الدخول بحساب Google أولاً", Toast.LENGTH_LONG).show()
            return
        }
        if (!GoogleSignIn.hasPermissions(account, driveScope)) {
            GoogleSignIn.requestPermissions(this, 9002, account, driveScope)
            Toast.makeText(this, "امنح إذن Drive ثم أعد المحاولة", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "جارٍ رفع نسخة الدعوى إلى Drive...", Toast.LENGTH_SHORT).show()
        val json = singleCaseJson()
        val fileName = "daawa_${etBasisNumber.text?.toString()?.trim().ifNullOrBlank("بدون-رقم")}.json"
        Thread {
            try {
                val token = GoogleAuthUtil.getToken(
                    this, GoogleSignIn.getLastSignedInAccount(this)!!.account!!,
                    "oauth2:https://www.googleapis.com/auth/drive.file"
                )
                val folderId = driveGetOrCreateFolder(token, "Al-Mustashar Cases")
                val link = driveUploadText(token, folderId, fileName, json)
                try {
                    GoogleAuthUtil.clearToken(this, token)
                } catch (_: Exception) {
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (link != null) "رُفعت نسخة الدعوى إلى Drive بنجاح" else "فشل رفع النسخة",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "فشل الرفع: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun driveUploadText(token: String, folderId: String, name: String, text: String): String? {
        val meta = JSONObject().apply {
            put("name", name)
            put("parents", org.json.JSONArray().put(folderId))
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                okhttp3.RequestBody.create("application/json".toMediaType(), meta.toString())
            )
            .addPart(
                okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                okhttp3.RequestBody.create("application/json".toMediaType(), text)
            )
            .build()
        val uploaded = driveApi(
            token,
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .post(body)
        ) ?: return null
        val fileId = try {
            JSONObject(uploaded).getString("id")
        } catch (_: Exception) {
            return null
        }
        val info = driveApi(
            token,
            Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId?fields=id,webViewLink").get()
        ) ?: return fileId
        return try {
            JSONObject(info).optString("webViewLink").ifBlank { fileId }
        } catch (_: Exception) {
            fileId
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_case_edit)

        etTitle = findViewById(R.id.etTitle)
        etBasisNumber = findViewById(R.id.etBasisNumber)
        etCaseYear = findViewById(R.id.etCaseYear)
        etCourt = findViewById(R.id.etCourt)
        etChamber = findViewById(R.id.etChamber)
        etJudgeName = findViewById(R.id.etJudgeName)
        etClient = findViewById(R.id.etClient)
        etClientRole = findViewById(R.id.etClientRole)
        etClientPhone = findViewById(R.id.etClientPhone)
        etOpponentName = findViewById(R.id.etOpponentName)
        etOpponentLawyer = findViewById(R.id.etOpponentLawyer)
        etWitnesses = findViewById(R.id.etWitnesses)
        etDate = findViewById(R.id.etDate)
        etNextSessionDate = findViewById(R.id.etNextSessionDate)
        etLastSessionDecision = findViewById(R.id.etLastSessionDecision)
        etNextSessionRequired = findViewById(R.id.etNextSessionRequired)
        etSummary = findViewById(R.id.etSummary)
        etSessions = findViewById(R.id.etSessions)
        etProcedures = findViewById(R.id.etProcedures)
        etStatus = findViewById(R.id.etStatus)
        etFinalJudgment = findViewById(R.id.etFinalJudgment)

        tvEditTitle = findViewById(R.id.tvEditTitle)
        btnDelete = findViewById(R.id.btnDelete)
        btnDecisions = findViewById(R.id.btnDecisions)
        btnDraftForCase = findViewById(R.id.btnDraftForCase)
        btnAddDocument = findViewById(R.id.btnAddDocument)
        btnCloudBackup = findViewById(R.id.btnCloudBackup)
        layoutDocumentsList = findViewById(R.id.layoutDocumentsList)

        etDate.setOnClickListener { showDatePicker(etDate) }
        etNextSessionDate.setOnClickListener { showDatePicker(etNextSessionDate) }

        btnAddDocument.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        btnCloudBackup.setOnClickListener { uploadAllToCloud() }

        caseId = intent.getStringExtra("case_id")

        if (caseId != null) {
            tvEditTitle.text = "⚖️ تعديل ملف الدعوى"
            btnDelete.visibility = View.VISIBLE
            btnDecisions.visibility = View.VISIBLE
            btnDraftForCase.visibility = View.VISIBLE
            load()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { showSaveOptions() }
        btnDelete.setOnClickListener { delete() }
        
                btnDraftForCase.setOnClickListener {
                        try {
                val i = Intent(this, LegalDraftingActivity::class.java)
                i.putExtra("case_title", etTitle.text.toString())
                i.putExtra("case_basis", etBasisNumber.text.toString() + "/" + etCaseYear.text.toString())
                i.putExtra("case_court", etCourt.text.toString() + " (" + etChamber.text.toString() + ")")
                i.putExtra("client_name", etClient.text.toString())
                i.putExtra("client_role", etClientRole.text.toString())
                i.putExtra("opponent_name", etOpponentName.text.toString())
                i.putExtra("case_parties", etClient.text.toString() + " (" + etClientRole.text.toString() + ") ضد " + etOpponentName.text.toString())
                startActivity(i)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "تعذر فتح الشاشة: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    
        btnDecisions.setOnClickListener {
            if (caseId != null) {
                val i = Intent(this, DecisionsActivity::class.java)
                i.putExtra("case_id", caseId)
                startActivity(i)
            } else {
                Toast.makeText(this, "احفظ الدعوى أولاً", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDatePicker(targetEditText: EditText) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val formatted = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
                targetEditText.setText(formatted)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun savePickedFile(uri: Uri) {
        try {
            var fileName = "مستند_غير_معنون"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            val docsFolder = File(filesDir, "case_documents")
            if (!docsFolder.exists()) docsFolder.mkdirs()

            val ext = fileName.substringAfterLast(".", "")
            val safeName = "doc_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}." + ext
            val destFile = File(docsFolder, safeName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            attachedDocs.add(CaseDoc(fileName, destFile.absolutePath, mimeType))
            renderDocumentsList()
            Toast.makeText(this, "تم إرفاق: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل حفظ الملف: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderDocumentsList() {
        layoutDocumentsList.removeAllViews()
        for ((index, doc) in attachedDocs.withIndex()) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(android.graphics.Color.WHITE)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 6, 0, 6)
                layoutParams = params
            }

            val icon = when {
                doc.name.endsWith(".pdf", ignoreCase = true) -> "📄"
                doc.name.endsWith(".jpg", ignoreCase = true) || doc.name.endsWith(".png", ignoreCase = true) -> "🖼️"
                else -> "📝"
            }

            val tvName = TextView(this).apply {
                text = "$icon ${doc.name}"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#0F2042"))
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openDocument(doc) }
            }

            val btnView = Button(this).apply {
                text = "معاينة"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#1976D2"))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { openDocument(doc) }
            }

            val btnDel = Button(this).apply {
                text = "🗑️"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#D32F2F"))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    AlertDialog.Builder(this@CaseEditActivity)
                        .setTitle("حذف المرفق")
                        .setMessage("حذف \"${doc.name}\" من القائمة؟ (لا يُحذف من السحابة إن رُفع)")
                        .setPositiveButton("حذف") { _, _ ->
                            attachedDocs.removeAt(index)
                            renderDocumentsList()
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }

            itemLayout.addView(btnDel)
            itemLayout.addView(btnView)
            itemLayout.addView(tvName)
            layoutDocumentsList.addView(itemLayout)
        }
    }

    private fun openDocument(doc: CaseDoc) {
        val file = File(doc.path)
        if (!file.exists()) {
            // الملف المحلي مفقود (جهاز جديد) — جرّب النسخة السحابية
            if (doc.url.isNotBlank()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(doc.url)))
                } catch (_: Exception) {
                    Toast.makeText(this, "تعذر فتح النسخة السحابية", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "الملف غير موجود محلياً ولا نسخة سحابية له", Toast.LENGTH_LONG).show()
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, doc.type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح المستند: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun serializeDocs(): String {
        val arr = JSONArray()
        for (d in attachedDocs) {
            val obj = JSONObject()
            obj.put("name", d.name)
            obj.put("path", d.path)
            obj.put("type", d.type)
            obj.put("url", d.url)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserializeDocs(json: String) {
        attachedDocs.clear()
        if (json.isBlank()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                attachedDocs.add(CaseDoc(obj.getString("name"), obj.getString("path"), obj.optString("type", "*/*"), obj.optString("url", "")))
            }
        } catch (_: Exception) {}
        renderDocumentsList()
    }

    // النسخ السحابي إلى Google Drive على مساحة المستخدم الخاصة (لا حدود Firebase)
    private val driveScope = Scope("https://www.googleapis.com/auth/drive.file")
    private val driveHttp = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun uploadAllToCloud() {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show()
            return
        }
        if (caseId == null) {
            Toast.makeText(this, "احفظ الدعوى أولاً ثم انسخ مرفقاتها إلى Drive", Toast.LENGTH_LONG).show()
            return
        }
        val pending = attachedDocs.filter { it.url.isBlank() && File(it.path).exists() }
        if (pending.isEmpty()) {
            Toast.makeText(this, "لا جديد للرفع (الكل مرفوع أو بلا ملف محلي)", Toast.LENGTH_SHORT).show()
            return
        }
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "سجّل الدخول بحساب Google أولاً (شاشة الدخول) لاستخدام مساحتك على Drive", Toast.LENGTH_LONG).show()
            return
        }
        if (!GoogleSignIn.hasPermissions(account, driveScope)) {
            GoogleSignIn.requestPermissions(this, 9002, account, driveScope)
            Toast.makeText(this, "امنح إذن Drive ثم أعد الضغط", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "جارٍ رفع ${pending.size} مرفق إلى Drive...", Toast.LENGTH_SHORT).show()
        Thread { doDriveUpload(pending.map { it }) }.start()
    }

    private fun doDriveUpload(pending: List<CaseDoc>) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
            val token = GoogleAuthUtil.getToken(
                this, account.account!!,
                "oauth2:https://www.googleapis.com/auth/drive.file"
            )
            val folderId = driveGetOrCreateFolder(token, "Al-Mustashar Cases")
            var done = 0
            var failed = 0
            for (doc in pending) {
                val link = driveUploadFile(token, folderId, doc)
                if (link != null) {
                    doc.url = link
                    done++
                } else failed++
            }
            try {
                GoogleAuthUtil.clearToken(this, token)
            } catch (_: Exception) {
            }
            runOnUiThread {
                if (done > 0) {
                    caseId?.let { id ->
                        FirebaseFirestore.getInstance().collection("cases").document(id)
                            .update("documentsNotes", serializeDocs())
                    }
                    renderDocumentsList()
                }
                Toast.makeText(this, "اكتمل الرفع إلى Drive: $done ناجح، $failed فاشل", Toast.LENGTH_LONG).show()
            }
        } catch (e: UserRecoverableAuthException) {
            runOnUiThread {
                try {
                    val authIntent = e.intent
                    if (authIntent != null) {
                        @Suppress("DEPRECATION")
                        startActivityForResult(authIntent, 9003)
                    } else {
                        Toast.makeText(this, "تعذر طلب إذن Drive", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "تعذر طلب إذن Drive", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "فشل الرفع: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun driveApi(token: String, builder: Request.Builder): String? {
        val resp = driveHttp.newCall(
            builder.addHeader("Authorization", "Bearer $token").build()
        ).execute()
        val body = resp.body?.string().orEmpty()
        resp.close()
        return if (resp.isSuccessful) body else null
    }

    private fun driveGetOrCreateFolder(token: String, name: String): String {
        val q = java.net.URLEncoder.encode(
            "mimeType='application/vnd.google-apps.folder' and name='$name' and trashed=false", "UTF-8"
        )
        val found = driveApi(
            token,
            Request.Builder().url("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&spaces=drive").get()
        )
        val id = found?.let {
            try {
                JSONObject(it).getJSONArray("files").takeIf { a -> a.length() > 0 }
                    ?.getJSONObject(0)?.getString("id")
            } catch (_: Exception) {
                null
            }
        }
        if (id != null) return id
        val meta = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
        }
        val created = driveApi(
            token,
            Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id")
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), meta.toString()))
        ) ?: throw Exception("تعذر إنشاء مجلد Drive")
        return JSONObject(created).getString("id")
    }

    private fun driveUploadFile(token: String, folderId: String, doc: CaseDoc): String? {
        val file = File(doc.path)
        if (!file.exists()) return null
        val meta = JSONObject().apply {
            put("name", doc.name)
            put("parents", org.json.JSONArray().put(folderId))
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                okhttp3.RequestBody.create("application/json".toMediaType(), meta.toString())
            )
            .addPart(
                okhttp3.Headers.headersOf("Content-Type", doc.type.ifBlank { "application/octet-stream" }),
                file.asRequestBody(doc.type.ifBlank { "application/octet-stream" }.toMediaType())
            )
            .build()
        val uploaded = driveApi(
            token,
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .post(body)
        ) ?: return null
        val fileId = try {
            JSONObject(uploaded).getString("id")
        } catch (_: Exception) {
            return null
        }
        val info = driveApi(
            token,
            Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId?fields=id,webViewLink").get()
        ) ?: return null
        return try {
            JSONObject(info).optString("webViewLink").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 9002 || requestCode == 9003) && resultCode == RESULT_OK) {
            uploadAllToCloud()
        }
    }

    private fun load() {
        FirebaseFirestore.getInstance().collection("cases").document(caseId!!)
            .get()
            .addOnSuccessListener { doc ->
                val c = doc.toObject(Case::class.java) ?: return@addOnSuccessListener
                etTitle.setText(c.title)
                etBasisNumber.setText(c.basisNumber)
                etCaseYear.setText(c.caseYear)
                etCourt.setText(c.court)
                etChamber.setText(c.chamber)
                etJudgeName.setText(c.judgeName)
                etClient.setText(c.clientName)
                etClientRole.setText(if (c.clientRole.isNotEmpty()) c.clientRole else "مدعٍ")
                etClientPhone.setText(c.clientPhone)
                etOpponentName.setText(c.opponentName)
                etOpponentLawyer.setText(c.opponentLawyer)
                etWitnesses.setText(c.witnesses)
                etDate.setText(c.date)
                etNextSessionDate.setText(c.nextSessionDate)
                etLastSessionDecision.setText(c.lastSessionDecision)
                etNextSessionRequired.setText(c.nextSessionRequired)
                etSummary.setText(c.summary)
                etSessions.setText(c.sessions)
                etProcedures.setText(c.procedures)
                etStatus.setText(if (c.status.isNotEmpty()) c.status else "قيد النظر")
                etFinalJudgment.setText(c.finalJudgment)
                deserializeDocs(c.documentsNotes)
            }
    }

    private fun save() {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show()
            return
        }
        val title = etTitle.text?.toString()?.trim() ?: ""
        val case = collectCase(user.uid, if (caseId != null) 0 else System.currentTimeMillis()) ?: return

        val db = FirebaseFirestore.getInstance().collection("cases")

        if (caseId != null) {
            db.document(caseId!!).get().addOnSuccessListener { old ->
                val oldTs = old.getLong("timestamp") ?: System.currentTimeMillis()
                db.document(caseId!!).set(case.copy(timestamp = oldTs))
                    .addOnSuccessListener {
                        val reminded = SessionReminder.schedule(this, caseId!!, title, case.nextSessionDate)
                        Toast.makeText(
                            this,
                            "تم تحديث ملف الدعوى والمرفقات" + if (reminded) " 🔔 التذكير بالجلسة مفعّل (${case.nextSessionDate})" else "",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
            }
        } else {
            db.add(case)
                .addOnSuccessListener { ref ->
                    val reminded = SessionReminder.schedule(this, ref.id, title, case.nextSessionDate)
                    Toast.makeText(
                        this,
                        "تم حفظ ملف الدعوى والمرفقات بنجاح" + if (reminded) " 🔔 التذكير بالجلسة مفعّل (${case.nextSessionDate})" else "",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "فشل الحفظ: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun delete() {
        if (caseId == null) return
        AlertDialog.Builder(this)
            .setTitle("حذف الدعوى نهائياً؟")
            .setMessage("سيُحذف ملف الدعوى وقراراتها من حسابك. المرفقات المحلية تبقى على الجهاز. هل أنت متأكد؟")
            .setPositiveButton("حذف نهائي") { _, _ ->
                SessionReminder.cancel(this, caseId!!)
                FirebaseFirestore.getInstance().collection("cases").document(caseId!!)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(this, "تم حذف ملف الدعوى", Toast.LENGTH_SHORT).show()
                        finish()
                    }
            }
            .setNegativeButton("تراجع", null)
            .show()
    }
}
