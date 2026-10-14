package com.jashith.stride.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.PowerCurvePoint
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Everything here is drawn by hand with Canvas, like the iPhone app draws with Path: the charts look the same
// on every Android version and stay fast with ten thousand samples.

private fun resolvedBounds(values: List<Double>, yMin: Double?, yMax: Double?): Pair<Double, Double> {
    val lo = yMin ?: (values.minOrNull() ?: 0.0)
    val hi = yMax ?: (values.maxOrNull() ?: 1.0)
    return if (hi - lo < 0.0001) (lo - 1) to (hi + 1) else lo to hi
}

/** A plain line chart. [highlight] shades a range of indices, used to show a best effort inside a stream. */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = StrideColors.Accent,
    fill: Boolean = false,
    lineWidth: Float = 2f,
    yMin: Double? = null,
    yMax: Double? = null,
    highlight: IntRange? = null,
) {
    Canvas(modifier.fillMaxSize()) { drawLine(this, values, color, fill, lineWidth.dp.toPx(), yMin, yMax, highlight) }
}

private fun drawLine(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    values: List<Double>,
    color: Color,
    fill: Boolean,
    lineWidthPx: Float,
    yMin: Double?,
    yMax: Double?,
    highlight: IntRange?,
) = with(scope) {
    if (values.size < 2) return@with
    val (lo, hi) = resolvedBounds(values, yMin, yMax)
    val span = hi - lo
    val stepX = size.width / (values.size - 1)
    val xs = FloatArray(values.size) { it * stepX }
    val ys = FloatArray(values.size) { i ->
        val clamped = max(lo, min(hi, values[i]))
        (size.height - ((clamped - lo) / span).toFloat() * size.height)
    }
    if (highlight != null) {
        val lower = max(0, min(values.size - 1, highlight.first))
        val upper = max(0, min(values.size - 1, highlight.last))
        if (upper > lower) {
            drawRect(color.copy(alpha = 0.16f), Offset(xs[lower], 0f), Size(xs[upper] - xs[lower], size.height))
        }
    }
    val line = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 1 until values.size) lineTo(xs[i], ys[i])
    }
    if (fill) {
        val area = Path().apply {
            addPath(line)
            lineTo(xs.last(), size.height)
            lineTo(xs.first(), size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f))))
    }
    drawPath(line, color, style = Stroke(width = lineWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

data class ChartSeries(val values: List<Double>, val color: Color, val fill: Boolean = false, val lineWidth: Float = 2f)

/** Two or more lines on shared axes, used by Fitness & Freshness. */
@Composable
fun MultiLineChart(series: List<ChartSeries>, modifier: Modifier = Modifier, zeroLine: Boolean = false) {
    Canvas(modifier.fillMaxSize()) {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (s in series) for (v in s.values) {
            lo = min(lo, v); hi = max(hi, v)
        }
        val (bottom, top) = when {
            lo > hi -> 0.0 to 1.0
            hi - lo < 0.001 -> (lo - 1) to (hi + 1)
            else -> {
                val pad = (hi - lo) * 0.08
                (lo - pad) to (hi + pad)
            }
        }
        if (zeroLine && bottom < 0 && top > 0) {
            val y = size.height - ((0 - bottom) / (top - bottom)).toFloat() * size.height
            drawLine(
                StrideColors.SecondaryText.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y),
                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
        }
        for (s in series) drawLine(this, s.values, s.color, s.fill, s.lineWidth.dp.toPx(), bottom, top, null)
    }
}

/** Weekly or daily volume bars. The last bar is full strength, the rest are faded. */
@Composable
fun BarChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
    color: Color = StrideColors.Accent,
    highlightLast: Boolean = true,
) {
    val maxValue = max(values.maxOrNull() ?: 1.0, 0.0001)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (values.isEmpty()) return@Canvas
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (values.size - 1)) / values.size
            values.forEachIndexed { i, v ->
                val h = max(2.dp.toPx(), (v / maxValue).toFloat() * size.height)
                val isLast = highlightLast && i == values.lastIndex
                drawRoundRect(
                    if (isLast) color else color.copy(alpha = 0.35f),
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        if (labels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEach {
                    Text(it, Modifier.weight(1f), style = StrideType.Footnote.copy(fontSize = 9.sp, textAlign = TextAlign.Center), maxLines = 1)
                }
            }
        }
    }
}

/** Horizontal time-in-zone bars. */
@Composable
fun ZoneBars(
    times: List<Double>,
    names: List<String>,
    shortNames: List<String>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    ranges: List<Pair<Double, Double>> = emptyList(),
    unit: String = "bpm",
) {
    val total = max(1.0, times.sum())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        times.forEachIndexed { i, seconds ->
            val fraction = seconds / total
            val color = colors[min(i, colors.lastIndex)]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(shortNames.getOrElse(i) { "Z${i + 1}" }, Modifier.width(22.dp), style = StrideType.Body.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color))
                    Text(names.getOrElse(i) { "" }, style = StrideType.Footnote)
                    if (i < ranges.size) {
                        val (lo, hi) = ranges[i]
                        val text = if (i == ranges.lastIndex) "${lo.toInt()}+ $unit" else "${lo.toInt()}–${hi.toInt()} $unit"
                        Text(text, style = StrideType.Footnote.copy(fontSize = 11.sp, color = StrideColors.TertiaryText))
                    }
                    Box(Modifier.weight(1f))
                    Text(Fmt.durationCompact(seconds), style = StrideType.figure(12, FontWeight.SemiBold))
                    Text(Fmt.percent(fraction), Modifier.width(38.dp), style = StrideType.Footnote.copy(fontSize = 11.sp, textAlign = TextAlign.End))
                }
                StrideProgressBar(fraction.toFloat(), color = color, height = 6.dp)
            }
        }
    }
}

/** Log-scaled power curve (duration across, watts up). */
@Composable
fun PowerCurveChart(
    points: List<PowerCurvePoint>,
    modifier: Modifier = Modifier,
    comparison: List<PowerCurvePoint> = emptyList(),
    color: Color = StrideColors.RideColor,
) {
    Canvas(modifier.fillMaxSize()) {
        val all = points + comparison
        val maxWatts = max(1.0, all.maxOfOrNull { it.watts } ?: 1.0)
        val lo0 = all.minOfOrNull { it.duration } ?: 1.0
        val hi0 = all.maxOfOrNull { it.duration } ?: 3600.0
        val lo = max(1.0, lo0)
        val hi = max(lo + 1, hi0)
        val logLo = ln(lo)
        val span = max(0.001, ln(hi) - logLo)

        fun curve(data: List<PowerCurvePoint>): Path {
            val path = Path()
            if (data.size < 2) return path
            data.sortedBy { it.duration }.forEachIndexed { i, p ->
                val x = ((ln(max(1.0, p.duration)) - logLo) / span).toFloat() * size.width
                val y = size.height - (p.watts / maxWatts).toFloat() * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }
        if (comparison.isNotEmpty()) {
            drawPath(
                curve(comparison), StrideColors.SecondaryText.copy(alpha = 0.4f),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        drawPath(curve(points), color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

data class DayVolume(val date: LocalDate, val value: Double, val color: Color, val count: Int)

/** The training-log calendar: one square per day, shaded by volume, today outlined in lime. */
@Composable
fun TrainingLogGrid(
    weeks: List<List<DayVolume>>,
    maxValue: Double,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onSelect: ((LocalDate) -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (week in weeks) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (day in week) {
                    val fraction = if (maxValue > 0) min(1.0, day.value / maxValue).toFloat() else 0f
                    val shape = RoundedCornerShape(5.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(shape)
                            .background(if (day.count > 0) day.color.copy(alpha = 0.25f + 0.75f * fraction) else StrideColors.SecondaryText.copy(alpha = 0.10f))
                            .let { if (day.date == today) it.border(1.5.dp, StrideColors.Accent, shape) else it }
                            .let { if (onSelect != null) it.clickable { onSelect(day.date) } else it },
                    )
                }
            }
        }
    }
}

/** Axis labels drawn under a chart. */
@Composable
fun ChartAxis(labels: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = StrideType.Footnote.copy(fontSize = 10.sp)) }
    }
}
