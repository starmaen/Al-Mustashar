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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var btnAddDocument: Button
    private lateinit var layoutDocumentsList: LinearLayout

    // قائمة المرفقات المحلية: (Name, FilePath, MimeType)
    data class CaseDoc(val name: String, val path: String, val type: String)
    private val attachedDocs = mutableListOf<CaseDoc>()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { savePickedFile(it) }
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
        btnAddDocument = findViewById(R.id.btnAddDocument)
        layoutDocumentsList = findViewById(R.id.layoutDocumentsList)

        etDate.setOnClickListener { showDatePicker(etDate) }
        etNextSessionDate.setOnClickListener { showDatePicker(etNextSessionDate) }

        btnAddDocument.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        caseId = intent.getStringExtra("case_id")

        if (caseId != null) {
            tvEditTitle.text = "⚖️ تعديل ملف الدعوى"
            btnDelete.visibility = View.VISIBLE
            btnDecisions.visibility = View.VISIBLE
            load()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        btnDelete.setOnClickListener { delete() }
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
                    attachedDocs.removeAt(index)
                    renderDocumentsList()
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
            Toast.makeText(this, "الملف غير موجود محلياً", Toast.LENGTH_SHORT).show()
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
                attachedDocs.add(CaseDoc(obj.getString("name"), obj.getString("path"), obj.optString("type", "*/*")))
            }
        } catch (_: Exception) {}
        renderDocumentsList()
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
        if (title.isEmpty()) {
            Toast.makeText(this, "أدخل موضوع الدعوى", Toast.LENGTH_SHORT).show()
            return
        }

        val case = Case(
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
            userId = user.uid,
            timestamp = if (caseId != null) 0 else System.currentTimeMillis()
        )

        val db = FirebaseFirestore.getInstance().collection("cases")

        if (caseId != null) {
            db.document(caseId!!).get().addOnSuccessListener { old ->
                val oldTs = old.getLong("timestamp") ?: System.currentTimeMillis()
                db.document(caseId!!).set(case.copy(timestamp = oldTs))
                    .addOnSuccessListener {
                        Toast.makeText(this, "تم تحديث ملف الدعوى والمرفقات", Toast.LENGTH_SHORT).show()
                        finish()
                    }
            }
        } else {
            db.add(case)
                .addOnSuccessListener {
                    Toast.makeText(this, "تم حفظ ملف الدعوى والمرفقات بنجاح", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "فشل الحفظ: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun delete() {
        if (caseId == null) return
        FirebaseFirestore.getInstance().collection("cases").document(caseId!!)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "تم حذف ملف الدعوى", Toast.LENGTH_SHORT).show()
                finish()
            }
    }
}
