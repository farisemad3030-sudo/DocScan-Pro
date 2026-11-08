package com.docscan.app.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

enum class ActiveTool {
    NONE,
    PEN,
    HIGHLIGHTER,
    TEXT
}

data class DrawPath(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val isHighlighter: Boolean = false
)

data class TextAnnotation(
    val id: String,
    val text: String,
    val position: Offset,
    val color: Color,
    val fontSize: Float = 16f
)

data class PageAnnotations(
    val pageIndex: Int,
    val paths: MutableList<DrawPath> = mutableListOf(),
    val textNotes: MutableList<TextAnnotation> = mutableListOf()
)

enum class DocFilter(val displayName: String) {
    ORIGINAL("Original"),
    MAGIC_COLOR("Magic Color"),
    BLACK_AND_WHITE("B & W"),
    CLEAN_PAGE("Clean Paper"),
    GRAYSCALE("Grayscale")
}
