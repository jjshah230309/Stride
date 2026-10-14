package com.jashith.stride.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.Split
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.util.fixed
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import kotlin.math.abs
import kotlin.math.max

fun sportAccent(sport: SportType) = if (sport.isRide) StrideColors.RideColor else StrideColors.RunColor

/** The split table, with a bar behind each row scaled to pace so the fast and slow ones are obvious at a glance. */
@Composable
fun SplitsTable(splits: List<Split>, sport: SportType, units: UnitSystem, modifier: Modifier = Modifier) {
    val paces = splits.map { if (it.distance > 1 && it.moving > 0) it.moving / (it.distance / units.metersPerUnit) else 0.0 }
    val fastest = paces.filter { it > 0 }.minOrNull() ?: 1.0
    val slowest = paces.filter { it > 0 }.maxOrNull() ?: 1.0
    val span = max(0.001, slowest - fastest)
    val hasHr = splits.any { it.avgHR != null }
    val small = StrideType.Body.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.TertiaryText)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(units.distanceUnit.uppercase(), Modifier.width(26.dp), style = small)
            Text(if (sport.usesPace) "PACE" else "SPEED", Modifier.width(62.dp), style = small)
            Spacer(Modifier.weight(1f))
            Text("ELEV", Modifier.width(46.dp), style = small.copy(textAlign = TextAlign.End))
            if (hasHr) Text("HR", Modifier.width(34.dp), style = small.copy(textAlign = TextAlign.End))
        }
        splits.forEachIndexed { index, split ->
            val pace = paces[index]
            // The fastest split gets the full bar, the slowest the shortest.
            val fraction = if (pace > 0) 1.0 - (pace - fastest) / span * 0.72 else 0.1
            Box(Modifier.fillMaxWidth().height(26.dp)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(max(0.08, fraction).toFloat())
                        .clip(RoundedCornerShape(4.dp)).background(sportAccent(sport).copy(alpha = 0.18f)),
                )
                Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (split.isPartial) fixed(split.distance / units.metersPerUnit, 2) else "${split.index}",
                        Modifier.width(26.dp), style = StrideType.Body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1,
                    )
                    Text(
                        if (sport.usesPace) Fmt.paceFromSeconds(pace) else Fmt.avgSpeed(split.distance, split.moving, units),
                        Modifier.width(62.dp), style = StrideType.figure(13, FontWeight.SemiBold),
                    )
                    val gap = split.gapSeconds
                    if (gap != null && sport.usesPace && abs(gap - pace) > 3) {
                        Text("GAP " + Fmt.paceFromSeconds(gap), style = StrideType.Body.copy(fontSize = 10.sp, color = StrideColors.SecondaryText))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(elevationText(split, units), Modifier.width(46.dp), style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText, textAlign = TextAlign.End))
                    if (hasHr) {
                        Text(
                            split.avgHR?.let { "${it.toInt()}" } ?: "",
                            Modifier.width(34.dp), style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText, textAlign = TextAlign.End),
                        )
                    }
                }
            }
        }
    }
}

private fun elevationText(split: Split, units: UnitSystem): String {
    val net = split.elevGain - split.elevLoss
    if (abs(net) <= 0.5) return "—"
    return Fmt.signed(Fmt.elevationValue(net, units), 0)
}
