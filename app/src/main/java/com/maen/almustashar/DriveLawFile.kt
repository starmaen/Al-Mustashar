package com.maen.almustashar

data class DriveLawFile(
    val id: String,
    val name: String,
    val mimeType: String? = "application/pdf",
    val webViewLink: String? = null,
    val webContentLink: String? = null
)
