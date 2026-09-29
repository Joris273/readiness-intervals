package com.readiness.app.ui

import androidx.compose.ui.graphics.Color

object T {
    val Bg = Color(0xFF0B1017)
    val Panel = Color(0xFF131A24)
    val Line = Color(0xFF243044)
    val Text = Color(0xFFE8EDF4)
    val Muted = Color(0xFF8A97A8)
    val Faint = Color(0xFF5B6778)
    val Chip = Color(0xFF1A2330)
    val Sel = Color(0xFF22304A)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFC53D)
    val Red = Color(0xFFFF5C5C)

    fun hex(h: String): Color = runCatching { Color(android.graphics.Color.parseColor(h)) }.getOrDefault(Muted)
    fun deltaColor(kind: String): Color = when (kind) {
        "good" -> Green; "bad" -> Red; else -> Muted
    }
}
