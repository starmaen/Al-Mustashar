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
    val documentsNotes: String = "",
    val userId: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
