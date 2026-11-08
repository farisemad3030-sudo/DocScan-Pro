package com.docscan.app.core

import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.*
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning

object ScannerManager {

    fun getScannerClient(): GmsDocumentScanner {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_BASE_WITH_FILTER) // Keeps filters OFF (Original) by default while keeping edit options available
            .build()

        return GmsDocumentScanning.getClient(options)
    }

    fun startScan(
        activity: Activity,
        onSuccess: (IntentSenderRequest) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val scanner = getScannerClient()
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                val request = IntentSenderRequest.Builder(intentSender).build()
                onSuccess(request)
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }
}
