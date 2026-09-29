package com.maen.almustashar

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class DecisionEditActivity : AppCompatActivity() {

    private var caseId: String = ""
    private var decisionId: String? = null
    private lateinit var etNumber: EditText
    private lateinit var etDate: EditText
    private lateinit var etContent: EditText
    private lateinit var tvEditTitle: TextView
    private lateinit var btnDelete: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decision_edit)

        caseId = intent.getStringExtra("case_id") ?: ""
        decisionId = intent.getStringExtra("decision_id")

        etNumber = findViewById(R.id.etNumber)
        etDate = findViewById(R.id.etDate)
        etContent = findViewById(R.id.etContent)
        tvEditTitle = findViewById(R.id.tvEditTitle)
        btnDelete = findViewById(R.id.btnDelete)

        if (decisionId != null) {
            tvEditTitle.text = "تعديل القرار"
            btnDelete.visibility = View.VISIBLE
            load()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        btnDelete.setOnClickListener { delete() }
    }

    private fun load() {
        FirebaseFirestore.getInstance().collection("decisions").document(decisionId!!)
            .get()
            .addOnSuccessListener { doc ->
                val d = doc.toObject(CourtDecision::class.java) ?: return@addOnSuccessListener
                etNumber.setText(d.number)
                etDate.setText(d.date)
                etContent.setText(d.content)
            }
    }

    private fun save() {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show()
            return
        }
        val number = etNumber.text?.toString()?.trim() ?: ""
        if (number.isEmpty()) {
            Toast.makeText(this, "أدخل رقم القرار", Toast.LENGTH_SHORT).show()
            return
        }
        val decision = CourtDecision(
            caseId = caseId,
            number = number,
            date = etDate.text?.toString()?.trim() ?: "",
            content = etContent.text?.toString()?.trim() ?: "",
            userId = user.uid,
            timestamp = System.currentTimeMillis()
        )
        val db = FirebaseFirestore.getInstance().collection("decisions")
        if (decisionId != null) {
            db.document(decisionId!!).set(decision)
                .addOnSuccessListener {
                    Toast.makeText(this, "تم التعديل", Toast.LENGTH_SHORT).show()
                    finish()
                }
        } else {
            db.add(decision)
                .addOnSuccessListener {
                    Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
                    finish()
                }
        }
    }

    private fun delete() {
        if (decisionId == null) return
        FirebaseFirestore.getInstance().collection("decisions").document(decisionId!!)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()
                finish()
            }
    }
}
