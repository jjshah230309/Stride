package com.jashith.stride.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jashith.stride.core.model.Coord
import com.jashith.stride.ui.theme.StrideColors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The shape of a route, drawn on a plain tinted page. Used in lists, where a dozen live map views would
 * be slow and would each fetch tiles; the full map is one tap away.
 */
@Composable
fun PathThumbnail(
    path: List<Coord>,
    modifier: Modifier = Modifier,
    color: Color = StrideColors.MapLine,
    background: Color = Color(0xFFEDEFE8),
    paddingDp: Float = 14f,
    lineDp: Float = 3.5f,
    showEnds: Boolean = true,
) {
    val points = remember(path) { path.filter { it.isValid } }
    Box(modifier.fillMaxWidth().height(108.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(background)
            if (points.size < 2) return@Canvas
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            // Longitude degrees shrink with latitude; without this a route looks stretched sideways.
            val midLat = (minLat + maxLat) / 2
            val xSpan = max(1e-9, (maxLon - minLon) * cos(Math.toRadians(midLat)))
            val ySpan = max(1e-9, maxLat - minLat)
            val pad = paddingDp.dp.toPx()
            val scale = min((size.width - 2 * pad) / xSpan.toFloat(), (size.height - 2 * pad) / ySpan.toFloat())
            val offX = (size.width - xSpan.toFloat() * scale) / 2
            val offY = (size.height - ySpan.toFloat() * scale) / 2

            fun project(c: Coord) = Offset(
                offX + ((c.lon - minLon) * cos(Math.toRadians(midLat))).toFloat() * scale,
                offY + (maxLat - c.lat).toFloat() * scale,
            )

            val p = Path()
            points.forEachIndexed { i, c ->
                val o = project(c)
                if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
            }
            drawPath(p, color, style = Stroke(width = lineDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (showEnds) {
                drawCircle(Color.White, 6.dp.toPx(), project(points.first()))
                drawCircle(StrideColors.Green, 4.2.dp.toPx(), project(points.first()))
                drawCircle(Color.White, 6.dp.toPx(), project(points.last()))
                drawCircle(StrideColors.Negative, 4.2.dp.toPx(), project(points.last()))
            }
        }
    }
}
