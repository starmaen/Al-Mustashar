package com.maen.almustashar

data class Case(
    val id: String = "",
    val title: String = "",
    val basisNumber: String = "",
    val caseYear: String = "",
    val court: String = "",
    val chamber: String = "",
    val judgeName: String = "",
    val clientName: String = "",
    val clientRole: String = "مدعٍ",
    val clientPhone: String = "",
    val opponentName: String = "",
    val opponentLawyer: String = "",
    val witnesses: String = "",
    val date: String = "",
    val nextSessionDate: String = "",
    val lastSessionDecision: String = "",
    val nextSessionRequired: String = "",
    val summary: String = "",
    val sessions: String = "",
    val procedures: String = "",
    val status: String = "قيد النظر",
    val finalJudgment: String = "",
    // مواعيد الطعن (1): النوع + تاريخ صدور الحكم + آخر مهلة + تنبيه
    val appealType: String = "",
    val appealJudgmentDate: String = "",
    val appealDeadline: String = "",
    // سجل الجلسات المنظم (2): JSON [{d, decision, next}]
    val sessionsLog: String = "",
    // المتابعة المالية (3): أتعاب متفق/مدفوع + مصاريف (نصوص أرقام)
    val feesAgreed: String = "",
    val feesPaid: String = "",
    val expenses: String = "",
    val documentsNotes: String = "",
    val userId: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
