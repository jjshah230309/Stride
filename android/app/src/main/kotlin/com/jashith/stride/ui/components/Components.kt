package com.jashith.stride.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideShapes
import com.jashith.stride.ui.theme.StrideType
import java.util.Locale
import kotlin.math.max

// Cards

@Composable
fun StrideCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    radius: Dp = StrideShapes.Card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(StrideColors.Surface)
            .border(BorderStroke(1.dp, StrideColors.SurfaceBorder), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    radius: Dp = StrideShapes.InkCard,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(StrideColors.Ink)
            .padding(padding),
        content = content,
    )
}

// Headings

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = StrideColors.SecondaryText) {
    Text(text.uppercase(Locale.getDefault()), modifier = modifier, style = StrideType.Kicker.copy(color = color), maxLines = 1)
}

@Composable
fun ScreenHeader(
    kicker: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Kicker(kicker)
            Text(title, style = StrideType.ScreenTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Kicker(title, modifier = Modifier.weight(1f))
        if (actionTitle != null && onAction != null) {
            Text(
                actionTitle,
                modifier = Modifier.clickable(onClick = onAction).padding(vertical = 4.dp),
                style = StrideType.Body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.Green),
            )
        }
    }
}

// Stat tiles

@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    size: TextUnit = 22.sp,
    valueColor: Color = StrideColors.PrimaryText,
    labelColor: Color = StrideColors.SecondaryText,
    alignEnd: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = StrideType.figure(size, FontWeight.SemiBold, valueColor), maxLines = 1)
            if (unit != null) {
                Text(
                    unit,
                    modifier = Modifier.padding(bottom = 2.dp),
                    style = StrideType.Body.copy(fontSize = max(10f, size.value * 0.44f).sp, fontWeight = FontWeight.Medium, color = StrideColors.SecondaryText),
                    maxLines = 1,
                )
            }
        }
        Text(
            label.uppercase(Locale.getDefault()),
            style = StrideType.Body.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.07.sp * 10, color = labelColor),
            maxLines = 1,
        )
    }
}

@Composable
fun MetricCard(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier, unit: String? = null) {
    StrideCard(modifier = modifier, padding = 14.dp, radius = StrideShapes.MetricCard) {
        Icon(icon, contentDescription = null, tint = StrideColors.Green, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, style = StrideType.Caption, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = StrideType.figure(19), maxLines = 1)
            if (unit != null) Text(unit, modifier = Modifier.padding(bottom = 2.dp), style = StrideType.Caption)
        }
    }
}

@Composable
fun GhostMetricCard(icon: ImageVector, label: String, value: String, note: String, modifier: Modifier = Modifier) {
    StrideCard(modifier = modifier) {
        Icon(icon, contentDescription = null, tint = StrideColors.Green, modifier = Modifier.size(17.dp))
        Spacer(Modifier.height(12.dp))
        Text(label, style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = StrideColors.SecondaryText), maxLines = 1)
        Spacer(Modifier.height(12.dp))
        Text(
            value,
            style = StrideType.figure(38, color = StrideColors.PrimaryText.copy(alpha = 0.22f)),
            maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        Text(note, style = StrideType.Body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.Green), maxLines = 2)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = StrideColors.Green, filled: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .clip(CircleShape)
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        style = StrideType.Body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (filled) Color.White else color),
        maxLines = 1,
    )
}

// Buttons

@Composable
fun PrimaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(StrideColors.Lime)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 17.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = StrideColors.Ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = StrideType.Button)
    }
}

@Composable
fun SecondaryButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false) {
    val color = if (destructive) StrideColors.Negative else StrideColors.Green
    Text(
        title,
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .border(BorderStroke(1.dp, StrideColors.SurfaceBorder), CircleShape)
            .background(StrideColors.Surface)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        style = StrideType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color, textAlign = TextAlign.Center),
    )
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = StrideColors.Surface,
    tint: Color = StrideColors.PrimaryText,
) {
    val bordered = background == StrideColors.Surface
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(if (bordered) Modifier.border(BorderStroke(1.dp, StrideColors.SurfaceBorder), CircleShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun StrideChip(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) StrideColors.Ink else StrideColors.Surface)
            .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, StrideColors.SurfaceBorder), CircleShape))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else StrideColors.PrimaryText),
        maxLines = 1,
    )
}

@Composable
fun AvatarBadge(name: String, modifier: Modifier = Modifier, size: Dp = 46.dp, onClick: (() -> Unit)? = null) {
    val letters = name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "ME" }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(StrideColors.Lime)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = "Profile" },
        contentAlignment = Alignment.Center,
    ) {
        Text(letters, style = StrideType.figure((size.value * 0.34f).sp, FontWeight.Bold, StrideColors.DeepGreen))
    }
}

@Composable
fun GlyphTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(StrideShapes.Glyph)).background(StrideColors.Lime),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = StrideColors.DeepGreen, modifier = Modifier.size(size * 0.4f))
    }
}

@Composable
fun DetailRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    StrideCard(modifier = modifier, padding = 14.dp, radius = StrideShapes.MetricCard, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphTile(icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = StrideType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = StrideType.BodySecondary.copy(fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = StrideColors.TertiaryText, modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun MapBadge(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.94f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = StrideColors.PrimaryText, modifier = Modifier.size(12.dp))
        Text(text, style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

// Progress, indicators, empty states

@Composable
fun StrideProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = StrideColors.Green, height: Dp = 8.dp) {
    Box(modifier = modifier.fillMaxWidth().height(height).clip(CircleShape).background(StrideColors.Track)) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(color))
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = StrideColors.TertiaryText, modifier = Modifier.size(34.dp))
        Text(title, style = StrideType.Body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
        Text(message, style = StrideType.BodySecondary.copy(textAlign = TextAlign.Center))
        if (actionTitle != null && onAction != null) {
            Text(
                actionTitle,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(StrideColors.Lime)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 20.dp, vertical = 11.dp),
                style = StrideType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.Ink),
            )
        }
    }
}

/** Three bars of rising height; as many light up as the GPS signal is strong (0 to 3). */
@Composable
fun GpsIndicator(strength: Int, modifier: Modifier = Modifier) {
    val lit = when (strength) {
        3 -> StrideColors.Green
        2 -> StrideColors.Warning
        1 -> StrideColors.Negative
        else -> StrideColors.Track
    }
    Row(
        modifier = modifier.semantics { contentDescription = "GPS signal $strength of 3" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(6.dp, 10.dp, 14.dp).forEachIndexed { index, h ->
            Box(Modifier.width(3.dp).height(h).clip(CircleShape).background(if (index < strength) lit else StrideColors.Track))
        }
    }
}

// Sport toggle and bar charts used on the home screens

enum class SportChoice { Run, Ride }

@Composable
fun SportToggle(selection: SportChoice, onSelect: (SportChoice) -> Unit, modifier: Modifier = Modifier, onDark: Boolean = false) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (onDark) StrideColors.InkBar.copy(alpha = 0.85f) else StrideColors.Track)
            .padding(4.dp),
    ) {
        for (choice in SportChoice.entries) {
            val active = choice == selection
            val icon = if (choice == SportChoice.Run) com.jashith.stride.ui.Icons.Run else com.jashith.stride.ui.Icons.Ride
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (active) (if (onDark) StrideColors.Lime else Color.White) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(choice) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = when {
                    active -> StrideColors.DeepGreen
                    onDark -> Color.White.copy(alpha = 0.85f)
                    else -> StrideColors.SecondaryText
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (choice == SportChoice.Run) "Run" else "Ride",
                    style = StrideType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint),
                )
            }
        }
    }
}

/** Seven capsules, one per weekday, today's in lime. */
@Composable
fun WeekBars(values: List<Double>, todayIndex: Int, dayLetters: List<String>, modifier: Modifier = Modifier) {
    val peak = max(1.0, values.maxOrNull() ?: 1.0)
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { index, value ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.height(78.dp).width(14.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.width(14.dp).height(max(4.0, value / peak * 78).dp).clip(CircleShape)
                            .background(if (index == todayIndex) StrideColors.Lime else StrideColors.InkBar),
                    )
                }
                Text(dayLetters.getOrElse(index) { "" }, style = StrideType.Body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = StrideColors.OnInkSecondary))
            }
        }
    }
}

@Composable
fun TrendBars(values: List<Double>, modifier: Modifier = Modifier) {
    val peak = max(1.0, values.maxOrNull() ?: 1.0)
    Row(modifier = modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { index, value ->
            Box(
                Modifier.weight(1f).height(max(6.0, value / peak * 96).dp).clip(RoundedCornerShape(6.dp))
                    .background(if (index == values.lastIndex) StrideColors.Lime else StrideColors.InkBar),
            )
        }
    }
}

// Tab bar

enum class AppTab(val title: String) { Today("Today"), Record("Record"), Progress("Progress"), Routes("Routes") }

@Composable
fun StrideTabBar(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().background(StrideColors.PageBackground).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(StrideColors.SurfaceBorder))
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            for (tab in AppTab.entries) {
                val active = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (active) StrideColors.Lime else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            com.jashith.stride.ui.Icons.forTab(tab), contentDescription = null,
                            tint = if (active) StrideColors.DeepGreen else StrideColors.SecondaryText, modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        tab.title,
                        style = StrideType.Body.copy(
                            fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) StrideColors.Green else StrideColors.SecondaryText,
                        ),
                    )
                }
            }
        }
    }
}

/** A thin dashed or solid rule, used inside cards. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawRect(StrideColors.SurfaceBorder, topLeft = Offset.Zero, size = Size(size.width, size.height))
    }
}
