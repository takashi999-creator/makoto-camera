package app.makoto.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Ink = Color(0xFF0E141B)
    val Panel = Color(0xFF18212B)
    val Line = Color(0xFF2A3644)
    val Text = Color(0xFFE8EDF2)
    val Sub = Color(0xFF93A1B0)
    val Accent = Color(0xFF2F80C2)
    val Ok = Color(0xFF2E9E6B)
    val Warn = Color(0xFFD08A1E)
    val Bad = Color(0xFFC8473F)
}

/** 角の小さい、枠線だけのボタン。選択中は塗りつぶす。 */
@Composable
fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Palette.Accent else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) Palette.Accent else Palette.Line), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (!enabled) Palette.Sub else Palette.Text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
