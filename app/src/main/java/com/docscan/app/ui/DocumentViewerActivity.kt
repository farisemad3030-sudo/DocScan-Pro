package com.docscan.app.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.docscan.app.core.ImageFilterManager
import com.docscan.app.core.PdfManager
import com.docscan.app.core.ShareManager
import com.docscan.app.model.ActiveTool
import com.docscan.app.model.DocFilter
import com.docscan.app.model.PageAnnotations
import com.docscan.app.model.TextAnnotation
import com.docscan.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DocumentViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val docTitle = intent.getStringExtra(EXTRA_DOC_TITLE) ?: "Scanned_Document"
        val pdfPath = intent.getStringExtra(EXTRA_PDF_PATH)
        val imageUriStrings = intent.getStringArrayListExtra(EXTRA_PAGE_URIS) ?: arrayListOf()
        var imageUris = imageUriStrings.map { Uri.parse(it) }

        // If opened from saved PDF and page list is empty or only has 1 thumb, extract all pages
        if (pdfPath != null) {
            val pdfFile = File(pdfPath)
            if (pdfFile.exists()) {
                try {
                    val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    if (pfd != null) {
                        val renderer = PdfRenderer(pfd)
                        val pageCount = renderer.pageCount
                        if (imageUris.size < pageCount) {
                            val extractedUris = mutableListOf<Uri>()
                            val cacheDir = File(cacheDir, "pdf_view_${pdfFile.nameWithoutExtension}").apply { mkdirs() }
                            for (i in 0 until pageCount) {
                                val pageFile = File(cacheDir, "page_${i + 1}.jpg")
                                if (!pageFile.exists()) {
                                    val page = renderer.openPage(i)
                                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    page.close()
                                    FileOutputStream(pageFile).use { out ->
                                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                    }
                                    bmp.recycle()
                                }
                                extractedUris.add(Uri.fromFile(pageFile))
                            }
                            imageUris = extractedUris
                        }
                        renderer.close()
                        pfd.close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        setContent {
            DocScanTheme {
                DocumentViewerScreen(
                    initialTitle = docTitle,
                    pdfPath = pdfPath,
                    pageUris = imageUris,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_DOC_TITLE = "extra_doc_title"
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        const val EXTRA_PAGE_URIS = "extra_page_uris"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    initialTitle: String,
    pdfPath: String?,
    pageUris: List<Uri>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentTitle by remember { mutableStateOf(initialTitle) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var newTextNoteContent by remember { mutableStateOf("") }

    var isExporting by remember { mutableStateOf(false) }
    var exportProgressMessage by remember { mutableStateOf("") }

    var activeFilter by remember { mutableStateOf(DocFilter.ORIGINAL) }
    var isAnnotationModeOpen by remember { mutableStateOf(false) }
    var activeTool by remember { mutableStateOf(ActiveTool.NONE) }
    var selectedColor by remember { mutableStateOf(Color(0xFFEF4444)) }

    // Map of PageIndex -> PageAnnotations
    val annotationsMap = remember {
        mutableStateMapOf<Int, PageAnnotations>().apply {
            pageUris.indices.forEach { index ->
                put(index, PageAnnotations(index))
            }
        }
    }

    var annotationChangeTrigger by remember { mutableIntStateOf(0) }

    // Live Compose ColorFilter matching exact ImageFilterManager ColorMatrix
    val liveComposeColorFilter = remember(activeFilter) {
        val matrix = ImageFilterManager.getFilterColorMatrix(activeFilter)
        if (matrix != null) {
            ColorFilter.colorMatrix(ColorMatrix(matrix.array))
        } else {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showRenameDialog = true }
                        ) {
                            Text(
                                text = currentTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextLightPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Rename",
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Annotate Toggle Button in Top Bar
                        FilledTonalButton(
                            onClick = {
                                isAnnotationModeOpen = !isAnnotationModeOpen
                                activeTool = if (isAnnotationModeOpen) ActiveTool.PEN else ActiveTool.NONE
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isAnnotationModeOpen) EmeraldPrimary else SlateBorder,
                                contentColor = if (isAnnotationModeOpen) SlateBackground else TextLightPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isAnnotationModeOpen) Icons.Default.Check else Icons.Default.Draw,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isAnnotationModeOpen) "Done" else "Annotate",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextLightPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateSurface)
            )
        },
        bottomBar = {
            Surface(
                color = SlateSurface,
                tonalElevation = 10.dp,
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    // Filter Chips Row (Horizontally Scrollable)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Filter:",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldPrimary,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        DocFilter.values().forEach { filter ->
                            FilterChip(
                                selected = activeFilter == filter,
                                onClick = { activeFilter = filter },
                                label = {
                                    Text(
                                        text = filter.displayName,
                                        fontSize = 12.sp,
                                        fontWeight = if (activeFilter == filter) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EmeraldPrimary,
                                    selectedLabelColor = SlateBackground,
                                    containerColor = SlateBackground.copy(alpha = 0.5f),
                                    labelColor = TextLightSecondary
                                )
                            )
                        }
                    }

                    // Action Buttons (Save to Phone & Share PDF)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (pageUris.isEmpty()) {
                                    Toast.makeText(context, "No pages to save", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                coroutineScope.launch {
                                    isExporting = true
                                    exportProgressMessage = "Saving PDF to phone..."
                                    withContext(Dispatchers.IO) {
                                        val compiledPdf = PdfManager.createPdfWithAnnotations(
                                            context = context,
                                            imageUris = pageUris,
                                            annotationsMap = annotationsMap,
                                            filter = activeFilter,
                                            outputTitle = currentTitle
                                        )
                                        val savedUri = PdfManager.exportPdfToPublicDownloads(context, compiledPdf, currentTitle)
                                        withContext(Dispatchers.Main) {
                                            isExporting = false
                                            if (savedUri != null) {
                                                Toast.makeText(context, "Saved ${currentTitle}.pdf to Downloads/DocScan!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "PDF saved successfully!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isExporting,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = SlateBackground,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Save to Phone",
                                fontWeight = FontWeight.Bold,
                                color = SlateBackground,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        OutlinedButton(
                            onClick = {
                                if (pageUris.isEmpty()) {
                                    Toast.makeText(context, "No pages to share", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                coroutineScope.launch {
                                    isExporting = true
                                    exportProgressMessage = "Preparing PDF to share..."
                                    withContext(Dispatchers.IO) {
                                        val compiledPdf = PdfManager.createPdfWithAnnotations(
                                            context = context,
                                            imageUris = pageUris,
                                            annotationsMap = annotationsMap,
                                            filter = activeFilter,
                                            outputTitle = currentTitle
                                        )
                                        withContext(Dispatchers.Main) {
                                            isExporting = false
                                            ShareManager.sharePdfFile(context, compiledPdf, currentTitle)
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isExporting,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLightPrimary),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder)),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Share PDF",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        },
        containerColor = SlateBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SlateBackground)
        ) {
            // Vertical Document Page Stream (Continuous Scrolling)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
            ) {
                itemsIndexed(pageUris) { index, uri ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SlateSurface,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "Page ${index + 1} of ${pageUris.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextLightSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }

                        // Page Container with Live Filter and Drawing Canvas Overlay
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.707f) // Standard A4 Aspect Ratio
                                .clip(RoundedCornerShape(12.dp))
                                .background(SlateSurface)
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Page ${index + 1}",
                                colorFilter = liveComposeColorFilter,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )

                            // Annotation Canvas Overlay (Captures touches when tool is active)
                            val pageAnnotation = annotationsMap.getOrPut(index) { PageAnnotations(index) }
                            AnnotationCanvas(
                                pageIndex = index,
                                pageAnnotation = pageAnnotation,
                                activeTool = if (isAnnotationModeOpen) activeTool else ActiveTool.NONE,
                                selectedColor = selectedColor,
                                onAnnotationsChanged = { annotationChangeTrigger++ },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // Side Floating Annotation Tool Dock (Collapsible on right edge)
            AnimatedVisibility(
                visible = isAnnotationModeOpen,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(bottom = 40.dp)
            ) {
                SideAnnotationToolbar(
                    activeTool = activeTool,
                    selectedColor = selectedColor,
                    onToolSelected = { activeTool = it },
                    onColorSelected = { selectedColor = it },
                    onUndo = {
                        annotationsMap.values.lastOrNull { it.paths.isNotEmpty() }?.paths?.removeLastOrNull()
                        annotationChangeTrigger++
                    },
                    onAddTextClick = { showAddTextDialog = true },
                    onCloseDock = {
                        isAnnotationModeOpen = false
                        activeTool = ActiveTool.NONE
                    }
                )
            }

            // Loading / Progress Dialog for Ultra Smooth Export
            if (isExporting) {
                Dialog(onDismissRequest = { /* Non dismissible during save */ }) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SlateSurface,
                        tonalElevation = 8.dp,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = EmeraldPrimary,
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = exportProgressMessage,
                                color = TextLightPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename Document Dialog
    if (showRenameDialog) {
        var tempName by remember { mutableStateOf(currentTitle) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Document", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    singleLine = true,
                    label = { Text("Document Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        cursorColor = EmeraldPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempName.isNotBlank()) {
                            currentTitle = tempName
                        }
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Save", color = SlateBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = TextLightSecondary)
                }
            }
        )
    }

    // Add Text Note Dialog
    if (showAddTextDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddTextDialog = false
                newTextNoteContent = ""
            },
            title = { Text("Add Text Note", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newTextNoteContent,
                    onValueChange = { newTextNoteContent = it },
                    label = { Text("Enter your note...") },
                    placeholder = { Text("e.g. Sign here, Verified, etc.") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        cursorColor = EmeraldPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTextNoteContent.isNotBlank()) {
                            val firstPage = annotationsMap.getOrPut(0) { PageAnnotations(0) }
                            firstPage.textNotes.add(
                                TextAnnotation(
                                    id = System.currentTimeMillis().toString(),
                                    text = newTextNoteContent,
                                    position = Offset(0.3f, 0.4f),
                                    color = selectedColor
                                )
                            )
                            annotationChangeTrigger++
                        }
                        showAddTextDialog = false
                        newTextNoteContent = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Add", color = SlateBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddTextDialog = false
                    newTextNoteContent = ""
                }) {
                    Text("Cancel", color = TextLightSecondary)
                }
            }
        )
    }
}
