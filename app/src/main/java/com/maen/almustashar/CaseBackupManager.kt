package com.maen.almustashar

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object CaseBackupManager {

    fun exportBackup(context: Context, cases: List<Case>, outputStream: OutputStream): Boolean {
        return try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("timestamp", System.currentTimeMillis())
            val casesArray = JSONArray()

            for (c in cases) {
                val obj = JSONObject().apply {
                    put("id", c.id)
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
                    put("timestamp", c.timestamp)
                }
                casesArray.put(obj)
            }
            root.put("cases", casesArray)

            outputStream.bufferedWriter().use { writer ->
                writer.write(root.toString(2))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importBackup(inputStream: InputStream): List<Case>? {
        return try {
            val jsonStr = inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val casesArray = root.getJSONArray("cases")
            val list = mutableListOf<Case>()

            for (i in 0 until casesArray.length()) {
                val obj = casesArray.getJSONObject(i)
                val c = Case(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    basisNumber = obj.optString("basisNumber"),
                    caseYear = obj.optString("caseYear"),
                    court = obj.optString("court"),
                    chamber = obj.optString("chamber"),
                    judgeName = obj.optString("judgeName"),
                    clientName = obj.optString("clientName"),
                    clientRole = obj.optString("clientRole", "مدعٍ"),
                    clientPhone = obj.optString("clientPhone"),
                    opponentName = obj.optString("opponentName"),
                    opponentLawyer = obj.optString("opponentLawyer"),
                    witnesses = obj.optString("witnesses"),
                    date = obj.optString("date"),
                    nextSessionDate = obj.optString("nextSessionDate"),
                    lastSessionDecision = obj.optString("lastSessionDecision"),
                    nextSessionRequired = obj.optString("nextSessionRequired"),
                    summary = obj.optString("summary"),
                    sessions = obj.optString("sessions"),
                    procedures = obj.optString("procedures"),
                    status = obj.optString("status", "قيد النظر"),
                    finalJudgment = obj.optString("finalJudgment"),
                    documentsNotes = obj.optString("documentsNotes"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                )
                list.add(c)
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
