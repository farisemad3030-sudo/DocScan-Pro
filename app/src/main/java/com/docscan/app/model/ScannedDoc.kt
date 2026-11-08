package com.docscan.app.model

import android.net.Uri

data class ScannedDoc(
    val id: String,
    val title: String,
    val timestamp: Long,
    val pageUris: List<Uri>,
    val pdfUri: Uri?,
    val pageCount: Int = pageUris.size
)
