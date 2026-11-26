package com.fengkao.timetable.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fengkao.timetable.ui.theme.GlassControlColor
import com.fengkao.timetable.ui.theme.GlassEdge
import com.fengkao.timetable.ui.theme.GlassElevatedColor
import com.fengkao.timetable.ui.theme.GlassSurfaceColor
import com.kyant.backdrop.Backdrop

/**
 * 普通实色表面。
 *
 * 这里保留原来的 Modifier 名称和参数，避免大范围改调用点；实际不再使用
 * backdrop 模糊、折射、高光等液态玻璃效果，减少 GPU 开销并保持界面干净。
 */
enum class GlassElevation(
    val blurDp: Float,
    val lensDp: Float,
    val highlightAlpha: Float,
    val shadowAlpha: Float,
    val pressScaleDp: Float,
) {
    Chrome(blurDp = 0f, lensDp = 0f, highlightAlpha = 0f, shadowAlpha = 0f, pressScaleDp = 0f),
    Control(blurDp = 0f, lensDp = 0f, highlightAlpha = 0f, shadowAlpha = 0f, pressScaleDp = 0f),
    Elevated(blurDp = 0f, lensDp = 0f, highlightAlpha = 0f, shadowAlpha = 0f, pressScaleDp = 0f),
}

@Composable
fun Modifier.liquidGlassSurface(
    backdrop: Backdrop,
    elevation: GlassElevation = GlassElevation.Control,
    lensDp: Dp = 0.dp,
    chromatic: Boolean = false,
    corner: Dp? = null,
    pressProgress: (() -> Float)? = null,
    shadowAlpha: Float = 0f,
    highlightAlpha: Float = 0f,
    edgeColor: Color = GlassEdge,
    onSurface: (DrawScope.() -> Unit)? = null,
): Modifier {
    val shape = corner?.let { RoundedCornerShape(it) } ?: RoundedCornerShape(50)
    val fill = when (elevation) {
        GlassElevation.Chrome -> GlassSurfaceColor
        GlassElevation.Control -> GlassControlColor
        GlassElevation.Elevated -> GlassElevatedColor
    }
    return this
        .background(fill, shape)
        .drawBehind { onSurface?.invoke(this) }
        .border(1.dp, edgeColor, shape)
}
