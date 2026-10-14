package com.jashith.stride.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideShapes
import com.jashith.stride.ui.theme.StrideType
import java.util.Locale

/** A grouped block of settings rows, like an iOS Form section: optional heading above, optional note below. */
@Composable
fun FormSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) Kicker(header, Modifier.padding(horizontal = 4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(StrideShapes.Card))
                .background(StrideColors.Surface)
                .border(BorderStroke(1.dp, StrideColors.SurfaceBorder), RoundedCornerShape(StrideShapes.Card)),
        ) { content() }
        if (footer != null) Text(footer, Modifier.padding(horizontal = 4.dp), style = StrideType.Footnote)
    }
}

@Composable
private fun FormRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
        if (divider) Hairline(Modifier.padding(start = 16.dp))
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, divider: Boolean = true) {
    FormRow(onClick = { onChange(!checked) }, divider = divider) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = StrideType.Body.copy(fontSize = 15.sp))
            if (subtitle != null) Text(subtitle, style = StrideType.Footnote)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = StrideColors.Green,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = StrideColors.Track,
                uncheckedBorderColor = StrideColors.Track,
            ),
        )
    }
}

@Composable
fun ValueRow(
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    valueColor: Color = StrideColors.SecondaryText,
    divider: Boolean = true,
) {
    FormRow(onClick = onClick, divider = divider) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = StrideType.Body.copy(fontSize = 15.sp))
            if (subtitle != null) Text(subtitle, style = StrideType.Footnote)
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = StrideType.Body.copy(fontSize = 15.sp, color = valueColor, textAlign = TextAlign.End), maxLines = 2)
    }
}

@Composable
fun StepperRow(
    title: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    divider: Boolean = true,
    subtitle: String? = null,
) {
    FormRow(divider = divider) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = StrideType.Body.copy(fontSize = 15.sp))
            if (subtitle != null) Text(subtitle, style = StrideType.Footnote)
        }
        Text(valueText, style = StrideType.figure(15, FontWeight.SemiBold), modifier = Modifier.padding(end = 10.dp))
        CircleIconButton(Icons.Filled.Remove, "Decrease $title", onMinus, size = 32.dp, background = StrideColors.Track)
        Spacer(Modifier.width(8.dp))
        CircleIconButton(Icons.Filled.Add, "Increase $title", onPlus, size = 32.dp, background = StrideColors.Track)
    }
}

/** A single-line text or number field with its label on the left. */
@Composable
fun TextFieldRow(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    divider: Boolean = true,
    singleLine: Boolean = true,
) {
    FormRow(divider = divider) {
        Text(title, style = StrideType.Body.copy(fontSize = 15.sp), modifier = Modifier.width(112.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = StrideType.Body.copy(fontSize = 15.sp, textAlign = TextAlign.End),
            cursorBrush = SolidColor(StrideColors.Green),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, style = StrideType.Body.copy(fontSize = 15.sp, color = StrideColors.TertiaryText, textAlign = TextAlign.End))
                    inner()
                }
            },
        )
    }
}

/**
 * A number field that keeps what the user is typing as text until it parses, so "1." and "" are allowed
 * mid-edit. [onValue] is called only with parseable numbers.
 */
@Composable
fun NumberFieldRow(
    title: String,
    value: Double,
    onValue: (Double) -> Unit,
    decimals: Int = 1,
    suffix: String? = null,
    divider: Boolean = true,
) {
    val shown = remember(value, decimals) { String.format(Locale.ROOT, "%.${decimals}f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" } }
    var text by remember { mutableStateOf(shown) }
    var lastExternal by remember { mutableStateOf(value) }
    if (value != lastExternal && text.toDoubleOrNull() != value) {
        text = shown
        lastExternal = value
    }
    FormRow(divider = divider) {
        Text(title, style = StrideType.Body.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                it.replace(',', '.').toDoubleOrNull()?.let { v -> lastExternal = v; onValue(v) }
            },
            singleLine = true,
            textStyle = StrideType.figure(15, FontWeight.SemiBold).copy(textAlign = TextAlign.End),
            cursorBrush = SolidColor(StrideColors.Green),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(88.dp),
        )
        if (suffix != null) Text(" $suffix", style = StrideType.Footnote)
    }
}

/** A dropdown. Shows the current choice on the right and a menu of [options] when tapped. */
@Composable
fun <T> PickerRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    divider: Boolean = true,
    subtitle: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    FormRow(onClick = { open = true }, divider = divider) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = StrideType.Body.copy(fontSize = 15.sp))
            if (subtitle != null) Text(subtitle, style = StrideType.Footnote)
        }
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(selected), style = StrideType.Body.copy(fontSize = 15.sp, color = StrideColors.Green, fontWeight = FontWeight.Medium))
                Icon(Icons.Filled.ArrowDropDown, null, tint = StrideColors.Green)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = StrideColors.Surface) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option), style = StrideType.Body) },
                        onClick = { open = false; onSelect(option) },
                    )
                }
            }
        }
    }
}

/** A row of two to four mutually exclusive choices drawn as a pill strip. */
@Composable
fun <T> SegmentedPicker(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(StrideColors.Track)
            .padding(4.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Text(
                label(option),
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 9.dp),
                style = StrideType.Body.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) StrideColors.DeepGreen else StrideColors.SecondaryText,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

/** A free-text box for names and notes, several lines tall. */
@Composable
fun NotesField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, minLines: Int = 3) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        minLines = minLines,
        textStyle = StrideType.Body.copy(fontSize = 15.sp),
        cursorBrush = SolidColor(StrideColors.Green),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(StrideShapes.MetricCard))
            .background(StrideColors.Surface)
            .border(BorderStroke(1.dp, StrideColors.SurfaceBorder), RoundedCornerShape(StrideShapes.MetricCard))
            .padding(14.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = StrideType.Body.copy(fontSize = 15.sp, color = StrideColors.TertiaryText))
                inner()
            }
        },
    )
}

/** A row that opens another screen: icon, title, chevron. */
@Composable
fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit, divider: Boolean = true, subtitle: String? = null) {
    FormRow(onClick = onClick, divider = divider) {
        Icon(icon, null, tint = StrideColors.Green, modifier = Modifier.width(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = StrideType.Body.copy(fontSize = 15.sp))
            if (subtitle != null) Text(subtitle, style = StrideType.Footnote)
        }
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = StrideColors.TertiaryText)
    }
}

/** A tappable text row such as "Scan for monitors". */
@Composable
fun ButtonRow(title: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true, divider: Boolean = true) {
    FormRow(onClick = if (enabled) onClick else null, divider = divider) {
        Text(
            title,
            style = StrideType.Body.copy(
                fontSize = 15.sp,
                color = when {
                    !enabled -> StrideColors.TertiaryText
                    destructive -> StrideColors.Negative
                    else -> StrideColors.Green
                },
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/** A label with a coloured dot, used for the zone lists. */
@Composable
fun ZoneRow(color: Color, title: String, range: String, divider: Boolean = true) {
    FormRow(divider = divider) {
        Box(Modifier.width(8.dp).padding(vertical = 4.dp).background(color, CircleShape).padding(4.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 13.sp))
        Text(range, style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.SecondaryText))
    }
}
