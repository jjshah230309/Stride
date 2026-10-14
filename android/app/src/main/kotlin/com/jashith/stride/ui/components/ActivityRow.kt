package com.jashith.stride.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.ui.DateFmt
import com.jashith.stride.ui.Icons
import com.jashith.stride.ui.map.PathThumbnail
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType

/** The value shown under "Pace" for runs and "Speed" for rides. */
fun paceOrSpeedLabel(activity: Activity): String = if (activity.sport.usesPace) "Pace" else "Speed"

fun paceOrSpeedValue(activity: Activity, units: UnitSystem): String =
    if (activity.sport.usesPace) Fmt.pace(activity.distance, activity.movingTime, units)
    else Fmt.avgSpeed(activity.distance, activity.movingTime, units)

/** One activity in a list: a route strip, the name, and the numbers. */
@Composable
fun ActivityRow(activity: Activity, units: UnitSystem, prCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    StrideCard(modifier = modifier, padding = 0.dp, onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphTile(Icons.forSport(activity.sport), size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(activity.name, style = StrideType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateFmt.relativeDay(activity.startDate), style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.SecondaryText))
            }
            if (prCount > 0) Pill("$prCount PR")
        }
        if (activity.hasGPS && activity.previewPath.size > 1) {
            PathThumbnail(activity.previewPath, showEnds = false, paddingDp = 12f)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            StatTile(Fmt.distance(activity.distance, units), "Distance", Modifier.weight(1f), unit = units.distanceUnit, size = 17.sp)
            StatTile(paceOrSpeedValue(activity, units), paceOrSpeedLabel(activity), Modifier.weight(1f), size = 17.sp)
            StatTile(Fmt.duration(activity.movingTime), "Time", Modifier.weight(1f), size = 17.sp)
            if (activity.elevationGain > 5) {
                StatTile(Fmt.elevation(activity.elevationGain, units), "Climb", Modifier.weight(1f), unit = units.elevationUnit, size = 17.sp)
            }
        }
    }
}
