package com.maen.almustashar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class CaseEditActivity : AppCompatActivity() {

    private var caseId: String? = null
    private lateinit var etTitle: EditText
    private lateinit var etDate: EditText
    private lateinit var etCourt: EditText
    private lateinit var etClient: EditText
    private lateinit var etSummary: EditText
    private lateinit var etSessions: EditText
    private lateinit var etProcedures: EditText
    private lateinit var tvEditTitle: TextView
    private lateinit var btnDelete: Button
    private lateinit var btnDecisions: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_case_edit)

        etTitle = findViewById(R.id.etTitle)
        etDate = findViewById(R.id.etDate)
        etCourt = findViewById(R.id.etCourt)
        etClient = findViewById(R.id.etClient)
        etSummary = findViewById(R.id.etSummary)
        etSessions = findViewById(R.id.etSessions)
        etProcedures = findViewById(R.id.etProcedures)
        tvEditTitle = findViewById(R.id.tvEditTitle)
        btnDelete = findViewById(R.id.btnDelete)
        btnDecisions = findViewById(R.id.btnDecisions)

        caseId = intent.getStringExtra("case_id")

        if (caseId != null) {
            tvEditTitle.text = "تعديل الدعوى"
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

    private fun load() {
        FirebaseFirestore.getInstance().collection("cases").document(caseId!!)
            .get()
            .addOnSuccessListener { doc ->
                val c = doc.toObject(Case::class.java) ?: return@addOnSuccessListener
                etTitle.setText(c.title)
                etDate.setText(c.date)
                etCourt.setText(c.court)
                etClient.setText(c.clientName)
                etSummary.setText(c.summary)
                etSessions.setText(c.sessions)
                etProcedures.setText(c.procedures)
            }
    }

    private fun save() {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show()
            return
        }
        val title = etTitle.text?.toString()?.trim() ?: ""
        if (title.isEmpty()) {
            Toast.makeText(this, "أدخل اسم الدعوى", Toast.LENGTH_SHORT).show()
            return
        }

        val case = Case(
            title = title,
            date = etDate.text?.toString()?.trim() ?: "",
            court = etCourt.text?.toString()?.trim() ?: "",
            clientName = etClient.text?.toString()?.trim() ?: "",
            summary = etSummary.text?.toString()?.trim() ?: "",
            sessions = etSessions.text?.toString()?.trim() ?: "",
            procedures = etProcedures.text?.toString()?.trim() ?: "",
            userId = user.uid,
            timestamp = if (caseId != null) 0 else System.currentTimeMillis()
        )

        val db = FirebaseFirestore.getInstance().collection("cases")

        if (caseId != null) {
            db.document(caseId!!).get().addOnSuccessListener { old ->
                val oldTs = old.getLong("timestamp") ?: System.currentTimeMillis()
                db.document(caseId!!).set(case.copy(timestamp = oldTs))
                    .addOnSuccessListener {
                        Toast.makeText(this, "تم التعديل", Toast.LENGTH_SHORT).show()
                        finish()
                    }
            }
        } else {
            db.add(case)
                .addOnSuccessListener {
                    Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "فشل: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun delete() {
        if (caseId == null) return
        FirebaseFirestore.getInstance().collection("cases").document(caseId!!)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()
                finish()
            }
    }
}
