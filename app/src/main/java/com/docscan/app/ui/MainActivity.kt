package com.docscan.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.docscan.app.core.PdfManager
import com.docscan.app.core.ScannerManager
import com.docscan.app.core.ShareManager
import com.docscan.app.model.DocFilter
import com.docscan.app.model.ScannedDoc
import com.docscan.app.ui.theme.*
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val scannedDocuments = mutableStateListOf<ScannedDoc>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedDocuments()

        setContent {
            DocScanTheme {
                val scannerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { activityResult ->
                    if (activityResult.resultCode == Activity.RESULT_OK) {
                        val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
                        if (result != null) {
                            handleScanResult(result)
                        }
                    }
                }

                DashboardScreen(
                    documents = scannedDocuments,
                    onScanClick = {
                        ScannerManager.startScan(
                            activity = this,
                            onSuccess = { request ->
                                scannerLauncher.launch(request)
                            },
                            onFailure = { exception ->
                                Toast.makeText(this, "Scanner error: ${exception.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    onDocumentClick = { doc ->
                        openDocumentViewer(doc)
                    },
                    onShareClick = { doc ->
                        if (doc.pdfUri != null) {
                            val file = File(doc.pdfUri.path ?: "")
                            if (file.exists()) {
                                ShareManager.sharePdfFile(this, file, doc.title)
                            } else {
                                ShareManager.sharePdfUri(this, doc.pdfUri, doc.title)
                            }
                        } else if (doc.pageUris.isNotEmpty()) {
                            val generatedPdf = PdfManager.createPdfWithAnnotations(
                                context = this,
                                imageUris = doc.pageUris,
                                annotationsMap = emptyMap(),
                                filter = DocFilter.ORIGINAL,
                                outputTitle = doc.title
                            )
                            ShareManager.sharePdfFile(this, generatedPdf, doc.title)
                        }
                    },
                    onDeleteClick = { doc ->
                        if (doc.pdfUri != null) {
                            val file = File(doc.pdfUri.path ?: "")
                            if (file.exists()) file.delete()
                        }
                        scannedDocuments.remove(doc)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadSavedDocuments()
    }

    private fun handleScanResult(result: GmsDocumentScanningResult) {
        val title = PdfManager.generateDefaultDocTitle()
        val pdfResult = result.pdf
        val rawPageUris = result.pages?.map { it.imageUri } ?: emptyList()

        val docsDir = File(filesDir, "scanned_documents").apply { mkdirs() }
        val targetPdf = File(docsDir, "${title}.pdf")

        // 1. Copy PDF if generated by ML Kit
        if (pdfResult != null) {
            try {
                contentResolver.openInputStream(pdfResult.uri)?.use { input ->
                    FileOutputStream(targetPdf).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Copy page images to persistent internal storage
        val pagesDir = File(filesDir, "doc_pages_${title}").apply { mkdirs() }
        val persistentPageUris = mutableListOf<Uri>()

        for ((idx, uri) in rawPageUris.withIndex()) {
            val pageFile = File(pagesDir, "page_${idx + 1}.jpg")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(pageFile).use { output ->
                        input.copyTo(output)
                    }
                }
                persistentPageUris.add(Uri.fromFile(pageFile))
            } catch (e: Exception) {
                persistentPageUris.add(uri)
            }
        }

        // 3. If PDF was not already generated, compile it now
        if (!targetPdf.exists() && persistentPageUris.isNotEmpty()) {
            PdfManager.createPdfWithAnnotations(
                context = this,
                imageUris = persistentPageUris,
                annotationsMap = emptyMap(),
                filter = DocFilter.ORIGINAL,
                outputTitle = title
            )
        }

        // 4. Refresh documents list on Home screen immediately!
        loadSavedDocuments()

        // 5. Open viewer with all pages
        val intent = Intent(this, DocumentViewerActivity::class.java).apply {
            putExtra(DocumentViewerActivity.EXTRA_DOC_TITLE, title)
            putExtra(DocumentViewerActivity.EXTRA_PDF_PATH, targetPdf.absolutePath)
            putStringArrayListExtra(
                DocumentViewerActivity.EXTRA_PAGE_URIS,
                ArrayList(persistentPageUris.map { it.toString() })
            )
        }
        startActivity(intent)
    }

    private fun openDocumentViewer(doc: ScannedDoc) {
        val intent = Intent(this, DocumentViewerActivity::class.java).apply {
            putExtra(DocumentViewerActivity.EXTRA_DOC_TITLE, doc.title)
            putExtra(DocumentViewerActivity.EXTRA_PDF_PATH, doc.pdfUri?.path)
            putStringArrayListExtra(
                DocumentViewerActivity.EXTRA_PAGE_URIS,
                ArrayList(doc.pageUris.map { it.toString() })
            )
        }
        startActivity(intent)
    }

    private fun loadSavedDocuments() {
        scannedDocuments.clear()
        val docsDir = File(filesDir, "scanned_documents")
        if (docsDir.exists()) {
            val files = docsDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
            files?.sortedByDescending { it.lastModified() }?.forEach { file ->
                var actualPageCount = 1
                var thumbnailUri: Uri? = null

                try {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    if (pfd != null) {
                        val renderer = PdfRenderer(pfd)
                        actualPageCount = renderer.pageCount

                        val thumbDir = File(cacheDir, "pdf_thumbs").apply { mkdirs() }
                        val thumbFile = File(thumbDir, "${file.nameWithoutExtension}.jpg")

                        if (!thumbFile.exists() && actualPageCount > 0) {
                            val page = renderer.openPage(0)
                            val bitmap = Bitmap.createBitmap(page.width / 2, page.height / 2, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()

                            FileOutputStream(thumbFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            }
                            bitmap.recycle()
                        }
                        thumbnailUri = Uri.fromFile(thumbFile)

                        renderer.close()
                        pfd.close()
                    }
                } catch (e: Exception) {
                    actualPageCount = 1
                }

                scannedDocuments.add(
                    ScannedDoc(
                        id = file.nameWithoutExtension,
                        title = file.nameWithoutExtension,
                        timestamp = file.lastModified(),
                        pageUris = if (thumbnailUri != null) listOf(thumbnailUri) else emptyList(),
                        pdfUri = Uri.fromFile(file),
                        pageCount = actualPageCount
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    documents: List<ScannedDoc>,
    onScanClick: () -> Unit,
    onDocumentClick: (ScannedDoc) -> Unit,
    onShareClick: (ScannedDoc) -> Unit,
    onDeleteClick: (ScannedDoc) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = EmeraldPrimary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DocumentScanner,
                                    contentDescription = "App Logo",
                                    tint = SlateBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "DocScan Pro",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextLightPrimary
                            )
                            Text(
                                text = "Smart Scanner & PDF Suite",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextLightSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateSurface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onScanClick,
                containerColor = EmeraldPrimary,
                contentColor = SlateBackground,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .padding(bottom = 12.dp, end = 8.dp)
                    .size(68.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Scan Document",
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        containerColor = SlateBackground
    ) { padding ->
        if (documents.isEmpty()) {
            EmptyDashboardState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onScanClick = onScanClick
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Scans",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextLightPrimary
                        )
                        Text(
                            text = "${documents.size} ${if (documents.size == 1) "document" else "documents"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextLightSecondary
                        )
                    }
                }

                items(documents, key = { it.id }) { doc ->
                    DocumentCard(
                        doc = doc,
                        onClick = { onDocumentClick(doc) },
                        onShare = { onShareClick(doc) },
                        onDelete = { onDeleteClick(doc) }
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentCard(
    doc: ScannedDoc,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
    val formattedDate = remember(doc.timestamp) { dateFormat.format(Date(doc.timestamp)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateBorder),
                contentAlignment = Alignment.Center
            ) {
                if (doc.pageUris.isNotEmpty()) {
                    AsyncImage(
                        model = doc.pageUris.first(),
                        contentDescription = "Document Thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Metadata Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextLightPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = EmeraldDark.copy(alpha = 0.3f),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = "${doc.pageCount} ${if (doc.pageCount == 1) "page" else "pages"}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = EmeraldPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextLightSecondary,
                        maxLines = 1
                    )
                }
            }

            // Actions
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = DangerRed,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyDashboardState(
    modifier: Modifier = Modifier,
    onScanClick: () -> Unit
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = SlateSurface,
            modifier = Modifier.size(100.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.DocumentScanner,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "No Scanned Documents",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextLightPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tap the button below to scan papers with smart edge detection and auto-crop.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextLightSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onScanClick,
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = SlateBackground
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Scan First Document",
                color = SlateBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}
