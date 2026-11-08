package com.docscan.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docscan.app.model.ActiveTool
import com.docscan.app.model.DrawPath
import com.docscan.app.model.PageAnnotations
import com.docscan.app.model.TextAnnotation
import com.docscan.app.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun AnnotationCanvas(
    pageIndex: Int,
    pageAnnotation: PageAnnotations,
    activeTool: ActiveTool,
    selectedColor: Color,
    onAnnotationsChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var currentDrawingPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(activeTool, selectedColor, canvasSize) {
                if (activeTool == ActiveTool.NONE || activeTool == ActiveTool.TEXT) return@pointerInput

                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (canvasSize.width > 0 && canvasSize.height > 0) {
                            val normOffset = Offset(
                                startOffset.x / canvasSize.width.toFloat(),
                                startOffset.y / canvasSize.height.toFloat()
                            )
                            currentDrawingPoints = listOf(normOffset)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (canvasSize.width > 0 && canvasSize.height > 0) {
                            val normOffset = Offset(
                                change.position.x / canvasSize.width.toFloat(),
                                change.position.y / canvasSize.height.toFloat()
                            )
                            currentDrawingPoints = currentDrawingPoints + normOffset
                        }
                    },
                    onDragEnd = {
                        if (currentDrawingPoints.isNotEmpty()) {
                            val isHighlighter = activeTool == ActiveTool.HIGHLIGHTER
                            val strokeWidth = if (isHighlighter) 28f else 6f
                            val color = if (isHighlighter) selectedColor.copy(alpha = 0.45f) else selectedColor

                            pageAnnotation.paths.add(
                                DrawPath(
                                    points = currentDrawingPoints,
                                    color = color,
                                    strokeWidth = strokeWidth,
                                    isHighlighter = isHighlighter
                                )
                            )
                            currentDrawingPoints = emptyList()
                            onAnnotationsChanged()
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // 1. Draw saved strokes
            for (drawPath in pageAnnotation.paths) {
                if (drawPath.points.size > 1) {
                    val path = Path().apply {
                        moveTo(drawPath.points[0].x * width, drawPath.points[0].y * height)
                        for (i in 1 until drawPath.points.size) {
                            lineTo(drawPath.points[i].x * width, drawPath.points[i].y * height)
                        }
                    }
                    drawPath(
                        path = path,
                        color = drawPath.color,
                        style = Stroke(
                            width = drawPath.strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // 2. Draw current in-progress stroke
            if (currentDrawingPoints.size > 1) {
                val isHighlighter = activeTool == ActiveTool.HIGHLIGHTER
                val strokeWidth = if (isHighlighter) 28f else 6f
                val color = if (isHighlighter) selectedColor.copy(alpha = 0.45f) else selectedColor

                val path = Path().apply {
                    moveTo(currentDrawingPoints[0].x * width, currentDrawingPoints[0].y * height)
                    for (i in 1 until currentDrawingPoints.size) {
                        lineTo(currentDrawingPoints[i].x * width, currentDrawingPoints[i].y * height)
                    }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // 3. Render Beautiful Draggable Text Notes (Yellow Post-It Note Style)
        for ((noteIndex, textNote) in pageAnnotation.textNotes.withIndex()) {
            key(textNote.id) {
                var localOffset by remember { mutableStateOf(textNote.position) }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (localOffset.x * canvasSize.width).roundToInt(),
                                (localOffset.y * canvasSize.height).roundToInt()
                            )
                        }
                        .pointerInput(canvasSize) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                if (canvasSize.width > 0 && canvasSize.height > 0) {
                                    val newX = (localOffset.x + dragAmount.x / canvasSize.width).coerceIn(0f, 0.85f)
                                    val newY = (localOffset.y + dragAmount.y / canvasSize.height).coerceIn(0f, 0.9f)
                                    localOffset = Offset(newX, newY)
                                    pageAnnotation.textNotes[noteIndex] = textNote.copy(position = localOffset)
                                    onAnnotationsChanged()
                                }
                            }
                        }
                        .shadow(4.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEF08A)) // Warm Post-it Yellow background
                        .border(1.dp, Color(0xFFFACC15), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragIndicator,
                            contentDescription = "Drag Note",
                            tint = Color(0xFF713F12),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = textNote.text,
                            color = Color(0xFF1C1917), // Crisp readable dark text
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Delete Note",
                            tint = Color(0xFF991B1B),
                            modifier = Modifier
                                .size(14.dp)
                                .clickable {
                                    pageAnnotation.textNotes.removeAt(noteIndex)
                                    onAnnotationsChanged()
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SideAnnotationToolbar(
    activeTool: ActiveTool,
    selectedColor: Color,
    onToolSelected: (ActiveTool) -> Unit,
    onColorSelected: (Color) -> Unit,
    onUndo: () -> Unit,
    onAddTextClick: () -> Unit,
    onCloseDock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorsList = listOf(
        Color(0xFFEF4444), // Red
        Color(0xFF3B82F6), // Blue
        Color(0xFFEAB308), // Yellow (Highlighter)
        Color(0xFF10B981), // Emerald
        Color.White
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = SlateSurface.copy(alpha = 0.95f),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder)),
        shadowElevation = 10.dp,
        modifier = modifier.padding(end = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Close / Dismiss Button at top of dock
            IconButton(
                onClick = onCloseDock,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.Red.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Tool Dock",
                    tint = DangerRed,
                    modifier = Modifier.size(18.dp)
                )
            }

            HorizontalDivider(color = SlateBorder, modifier = Modifier.width(28.dp))

            // Pen Tool
            IconButton(
                onClick = { onToolSelected(if (activeTool == ActiveTool.PEN) ActiveTool.NONE else ActiveTool.PEN) },
                modifier = Modifier
                    .size(42.dp)
                    .background(if (activeTool == ActiveTool.PEN) EmeraldPrimary.copy(alpha = 0.25f) else Color.Transparent, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Pen",
                    tint = if (activeTool == ActiveTool.PEN) EmeraldPrimary else TextLightPrimary
                )
            }

            // Highlighter Tool
            IconButton(
                onClick = { onToolSelected(if (activeTool == ActiveTool.HIGHLIGHTER) ActiveTool.NONE else ActiveTool.HIGHLIGHTER) },
                modifier = Modifier
                    .size(42.dp)
                    .background(if (activeTool == ActiveTool.HIGHLIGHTER) EmeraldPrimary.copy(alpha = 0.25f) else Color.Transparent, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Highlight,
                    contentDescription = "Highlighter",
                    tint = if (activeTool == ActiveTool.HIGHLIGHTER) EmeraldPrimary else TextLightPrimary
                )
            }

            // Text Note Tool
            IconButton(
                onClick = onAddTextClick,
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.TextFields,
                    contentDescription = "Add Text Note",
                    tint = TextLightPrimary
                )
            }

            HorizontalDivider(color = SlateBorder, modifier = Modifier.width(28.dp))

            // Color Palette Dots
            colorsList.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selectedColor == color) 2.5.dp else 0.dp,
                            color = if (selectedColor == color) TextLightPrimary else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(color) }
                )
            }

            HorizontalDivider(color = SlateBorder, modifier = Modifier.width(28.dp))

            // Undo Button
            IconButton(
                onClick = onUndo,
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = TextLightSecondary
                )
            }
        }
    }
}
