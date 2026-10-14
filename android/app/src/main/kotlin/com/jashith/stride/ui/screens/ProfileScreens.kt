package com.jashith.stride.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.BuildConfig
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.GearKind
import com.jashith.stride.core.model.HeartRateZones
import com.jashith.stride.core.model.PowerZones
import com.jashith.stride.core.model.Totals
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.health.rememberHealthPermissionRequest
import com.jashith.stride.ui.Dest
import com.jashith.stride.ui.Icons as AppIcons
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalMessage
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.AvatarBadge
import com.jashith.stride.ui.components.ButtonRow
import com.jashith.stride.ui.components.FormSection
import com.jashith.stride.ui.components.NavRow
import com.jashith.stride.ui.components.NotesField
import com.jashith.stride.ui.components.NumberFieldRow
import com.jashith.stride.ui.components.PickerRow
import com.jashith.stride.ui.components.Pill
import com.jashith.stride.ui.components.StatTile
import com.jashith.stride.ui.components.StrideCard
import com.jashith.stride.ui.components.StrideProgressBar
import com.jashith.stride.ui.components.SubPage
import com.jashith.stride.ui.components.TextFieldRow
import com.jashith.stride.ui.components.ToggleRow
import com.jashith.stride.ui.components.ValueRow
import com.jashith.stride.ui.components.ZoneRow
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import kotlinx.coroutines.launch
import java.util.UUID

/** The "You" hub, reached from the avatar on Today. */
@Composable
fun ProfileScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    val totals = remember(state.activities) { Totals.of(state.activities) }
    val requestHealth = rememberHealthPermissionRequest(graph.health)
    val available = graph.health.isAvailable

    SubPage("You", onBack = { nav.pop() }) {
        StrideCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarBadge(state.settings.profile.name, size = 48.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(state.settings.profile.name.ifEmpty { "Athlete" }, style = StrideType.Body.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold))
                        Text("${totals.count} activities · ${Fmt.distanceWithUnit(totals.distance, units, 0)}", style = StrideType.Body.copy(fontSize = 12.sp, color = StrideColors.SecondaryText))
                    }
                }
                Row {
                    StatTile("${store.currentStreak()}d", "Streak", Modifier.weight(1f), size = 18.sp)
                    StatTile(Fmt.durationCompact(totals.movingTime), "Time", Modifier.weight(1f), size = 18.sp)
                    StatTile(Fmt.elevation(totals.elevationGain, units), "Elevation", Modifier.weight(1f), unit = units.elevationUnit, size = 18.sp)
                }
            }
        }
        FormSection(header = "Training") {
            NavRow(Icons.Filled.DirectionsRun, "Profile and zones", { nav.push(Dest.AthleteProfile) })
            NavRow(AppIcons.Bike, "Gear", { nav.push(Dest.Gear) }, divider = false)
        }
        FormSection(header = "Recording") {
            NavRow(Icons.Filled.RecordVoiceOver, "Voice coach", { nav.push(Dest.VoiceSettings) })
            NavRow(Icons.Filled.FiberManualRecord, "Recording and GPS", { nav.push(Dest.RecordingSettings) })
            NavRow(Icons.Filled.FavoriteBorder, "Heart rate monitor", { nav.push(Dest.HeartRateMonitor) }, divider = false)
        }
        FormSection(
            header = "General",
            footer = if (!available) "Health Connect is not installed on this phone, so these two switches have no effect until it is." else null,
        ) {
            PickerRow("Units", UnitSystem.entries, units, { it.title }, { u -> store.updateSettings { it.copy(display = it.display.copy(units = u)) } })
            ToggleRow("Week starts on Monday", state.settings.display.weekStartsMonday, { on -> store.updateSettings { it.copy(display = it.display.copy(weekStartsMonday = on)) } })
            ToggleRow("Write workouts to Health Connect", state.settings.display.writeToAppleHealth, { on ->
                store.updateSettings { it.copy(display = it.display.copy(writeToAppleHealth = on)) }
                if (on && available) requestHealth()
            })
            ToggleRow("Read heart rate from Health Connect", state.settings.display.readFromAppleHealth, { on ->
                store.updateSettings { it.copy(display = it.display.copy(readFromAppleHealth = on)) }
                if (on && available) requestHealth()
            }, divider = false)
        }
        FormSection(header = "Data") {
            NavRow(Icons.Filled.Storage, "Export and maintenance", { nav.push(Dest.Data) }, divider = false)
        }
        FormSection {
            NavRow(Icons.Filled.Info, "About Stride", { nav.push(Dest.About) }, divider = false)
        }
    }
}

@Composable
fun AthleteProfileScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val message = LocalMessage.current
    val scope = rememberCoroutineScope()
    val state = rememberStoreState()
    val units = state.settings.units
    val profile = state.settings.profile
    var recalculating by remember { mutableStateOf(false) }
    var confirmRecalc by remember { mutableStateOf(false) }

    fun update(change: (com.jashith.stride.core.model.AthleteProfile) -> com.jashith.stride.core.model.AthleteProfile) =
        store.updateSettings { it.copy(profile = change(it.profile)) }

    val requestHealth = rememberHealthPermissionRequest(graph.health) {
        scope.launch {
            val kg = graph.health.latestBodyMassKg()
            if (kg != null) update { it.copy(weightKg = kg) } else message("No weight was found in Health Connect.")
        }
    }

    SubPage("Profile", onBack = { nav.pop() }) {
        FormSection(header = "You") {
            TextFieldRow("Name", profile.name, { v -> update { it.copy(name = v) } })
            NumberFieldRow("Weight", profile.weightKg / units.kilogramsPerWeightUnit, { v -> update { it.copy(weightKg = v * units.kilogramsPerWeightUnit) } }, 1, units.weightUnit)
            PickerRow("Born", (1930..2018).toList().reversed(), profile.birthYear, { "$it" }, { v -> update { it.copy(birthYear = v) } })
            PickerRow("Sex", listOf(true, false), profile.isMale, { if (it) "Male" else "Female" }, { v -> update { it.copy(isMale = v) } })
            ButtonRow("Import weight from Health Connect", {
                scope.launch {
                    graph.health.refreshGranted()
                    if (!graph.health.isAvailable) message("Health Connect is not available on this phone.")
                    else if (!graph.health.canReadWeight) requestHealth()
                    else {
                        val kg = graph.health.latestBodyMassKg()
                        if (kg != null) update { it.copy(weightKg = kg) } else message("No weight was found in Health Connect.")
                    }
                }
            }, divider = false)
        }

        FormSection(
            header = "Heart rate zones",
            footer = "Zones drive Relative Effort, calories and the Fitness & Freshness chart. Getting max heart rate roughly right matters more than getting it exactly right.",
        ) {
            NumberFieldRow("Max heart rate", profile.hrZones.maxHR, { v -> update { it.copy(hrZones = it.hrZones.copy(maxHR = v)) } }, 0, "bpm")
            NumberFieldRow("Resting heart rate", profile.hrZones.restingHR, { v -> update { it.copy(hrZones = it.hrZones.copy(restingHR = v)) } }, 0, "bpm")
            ToggleRow("Base zones on threshold heart rate", profile.hrZones.useThresholdBased, { v -> update { it.copy(hrZones = it.hrZones.copy(useThresholdBased = v)) } })
            if (profile.hrZones.useThresholdBased) {
                NumberFieldRow("Threshold heart rate", profile.hrZones.lactateThresholdHR, { v -> update { it.copy(hrZones = it.hrZones.copy(lactateThresholdHR = v)) } }, 0, "bpm")
            }
            ButtonRow("Use predicted max (${profile.predictedMaxHR.toInt()} bpm)", { update { it.copy(hrZones = it.hrZones.copy(maxHR = it.predictedMaxHR)) } })
            (0 until 5).forEach { zone ->
                val (low, high) = profile.hrZones.range(zone)
                ZoneRow(
                    StrideColors.HeartRateZones[zone], "Z${zone + 1} ${HeartRateZones.names[zone]}",
                    if (zone == 4) "${low.toInt()}+ bpm" else "${low.toInt()}–${high.toInt()} bpm", divider = zone < 4,
                )
            }
        }

        FormSection(header = "Power") {
            NumberFieldRow("FTP", profile.powerZones.ftp, { v -> update { it.copy(powerZones = it.powerZones.copy(ftp = v)) } }, 0, "W")
            val count = profile.powerZones.bounds.size
            (0 until count).forEach { zone ->
                val (low, high) = profile.powerZones.range(zone)
                ZoneRow(
                    StrideColors.PowerZones[zone.coerceAtMost(StrideColors.PowerZones.lastIndex)], "Z${zone + 1} ${PowerZones.names[zone]}",
                    if (zone == count - 1) "${low.toInt()}+ W" else "${low.toInt()}–${high.toInt()} W", divider = zone < count - 1,
                )
            }
        }

        FormSection(
            header = "Running pace",
            footer = "Roughly the pace you could hold for an hour flat out. Used for effort estimates when you record without heart rate.",
        ) {
            ValueRow("Threshold pace", Fmt.paceFromSeconds(profile.paceZones.thresholdPace * units.metersPerUnit / 1000) + " " + units.paceUnit, divider = false)
            Box(Modifier.padding(horizontal = 16.dp)) {
                Slider(
                    value = profile.paceZones.thresholdPace.toFloat(), valueRange = 180f..480f, steps = 59,
                    onValueChange = { v -> update { it.copy(paceZones = it.paceZones.copy(thresholdPace = (Math.round(v / 5f) * 5).toDouble())) } },
                    colors = SliderDefaults.colors(thumbColor = StrideColors.Green, activeTrackColor = StrideColors.Green, inactiveTrackColor = StrideColors.Track),
                )
            }
        }

        FormSection(
            header = "Estimated cycling power",
            footer = "Without a power meter, watts are modelled from speed, gradient, mass and drag. Defaults suit a road bike on the hoods; 0.32 CdA and 0.005 rolling resistance are reasonable starting points.",
        ) {
            NumberFieldRow("Bike + kit weight", profile.bikeWeightKg / units.kilogramsPerWeightUnit, { v -> update { it.copy(bikeWeightKg = v * units.kilogramsPerWeightUnit) } }, 1, units.weightUnit)
            NumberFieldRow("Aerodynamic drag (CdA)", profile.dragArea, { v -> update { it.copy(dragArea = v) } }, 3)
            NumberFieldRow("Rolling resistance", profile.rollingResistance, { v -> update { it.copy(rollingResistance = v) } }, 4, divider = false)
        }

        FormSection(footer = "Run this after changing your weight, zones or FTP so historical numbers match.") {
            if (recalculating) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = StrideColors.Green)
                    Text("Recalculating…", style = StrideType.Footnote)
                }
            } else {
                ButtonRow("Recalculate every activity", { confirmRecalc = true }, divider = false)
            }
        }
    }

    if (confirmRecalc) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRecalc = false },
            title = { Text("Recalculate every activity from its raw track?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRecalc = false
                    recalculating = true
                    scope.launch { store.reanalyseAll(); recalculating = false; message("All activities recalculated.") }
                }) { Text("Recalculate") }
            },
            dismissButton = { TextButton(onClick = { confirmRecalc = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun HeartRateMonitorScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val strap = graph.strap
    val state by strap.state.collectAsState()
    val hasPermission = state.permitted

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        strap.refresh()
        if (strap.hasPermission()) strap.scan()
    }
    LaunchedEffect(Unit) { strap.refresh() }

    SubPage("Heart Rate", onBack = { nav.pop() }) {
        FormSection(
            header = "Status",
            footer = "Any Bluetooth chest strap or armband that advertises the standard Heart Rate Service will work — Polar, Garmin, Wahoo, Coospo and the rest.",
        ) {
            if (state.connected) {
                ValueRow(state.deviceName.ifEmpty { "Heart rate monitor" }, if (state.heartRate > 0) "${state.heartRate.toInt()} bpm" else "—", valueColor = StrideColors.Negative)
                state.batteryLevel?.let { ValueRow("Strap battery", "$it%") }
                ButtonRow("Disconnect", { strap.forgetDevice() }, destructive = true, divider = false)
            } else {
                ValueRow(
                    when {
                        !strap.isSupported -> "Bluetooth LE is not available on this phone."
                        !state.bluetoothReady -> "Bluetooth is off."
                        else -> "No monitor connected."
                    },
                    "", divider = false,
                )
            }
        }
        FormSection(header = "Nearby") {
            if (state.scanning) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = StrideColors.Green)
                    Text("Scanning…", style = StrideType.BodySecondary)
                }
            }
            state.discovered.forEach { device -> NavRow(Icons.Filled.Favorite, device.name, { strap.connect(device.address) }) }
            ButtonRow("Scan for monitors", {
                if (hasPermission || strap.requiredPermissions().isEmpty()) strap.scan()
                else permission.launch(strap.requiredPermissions())
            }, enabled = strap.isSupported, divider = false)
        }
        Text(
            "A watch works too — leave \"Read heart rate from Health Connect\" on and start a workout on the watch. It arrives in bursts, so a strap paired here updates faster.",
            style = StrideType.Footnote,
        )
    }
}

@Composable
fun AboutScreen() {
    val nav = LocalNavigator.current
    SubPage("About", onBack = { nav.pop() }) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.SportsScore, null, tint = StrideColors.Green, modifier = Modifier.size(54.dp))
            Text("Stride", style = StrideType.figure(24))
            Text("Version ${BuildConfig.VERSION_NAME}", style = StrideType.Footnote)
        }
        FormSection(header = "Where your data lives") {
            Text(
                "Everything stays on this phone. There is no account, no server and no analytics. Your activities are files in the app's own folder. Use Back up now to move them to a new phone, or to an iPhone running Stride — the backup format is the same on both.",
                Modifier.padding(16.dp), style = StrideType.Body.copy(fontSize = 13.sp),
            )
        }
        FormSection(header = "Network use") {
            Text(
                "Recording, analysis and storage work with no connection. Map tiles come from OpenFreeMap (OpenStreetMap data) and the route builder's \"snap to paths\" uses OpenStreetMap routing; those are the only network requests the app makes.",
                Modifier.padding(16.dp), style = StrideType.Body.copy(fontSize = 13.sp),
            )
        }
        FormSection(header = "Honest limits") {
            Text(
                "Segment leaderboards, the heatmap and matched routes are built from your own history — there is no global database of other people's efforts to compare against. Power on rides without a meter is a physics estimate, not a measurement. Calories and Relative Effort are models, so treat them as trends rather than truth.",
                Modifier.padding(16.dp), style = StrideType.Body.copy(fontSize = 13.sp),
            )
        }
        FormSection(header = "Open source") {
            Text(
                "Maps © OpenStreetMap contributors, tiles by OpenFreeMap, rendered with MapLibre. Nunito typeface under the SIL Open Font License.",
                Modifier.padding(16.dp), style = StrideType.Body.copy(fontSize = 13.sp),
            )
        }
    }
}

// Gear

@Composable
fun GearScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val state = rememberStoreState()
    val units = state.settings.units
    var editing by remember { mutableStateOf<Gear?>(null) }
    var adding by remember { mutableStateOf(false) }

    SubPage("Gear", onBack = { nav.pop() }) {
        GearKind.entries.forEach { kind ->
            val items = state.gear.filter { it.kind == kind && !it.isRetired }
            if (items.isNotEmpty()) {
                FormSection(header = kind.displayName) { items.forEach { GearRow(it, store.mileage(it), store.activityCount(it), units) { editing = it } } }
            }
        }
        val retired = state.gear.filter { it.isRetired }
        if (retired.isNotEmpty()) {
            FormSection(header = "Retired") { retired.forEach { GearRow(it, store.mileage(it), store.activityCount(it), units) { editing = it } } }
        }
        FormSection { ButtonRow("Add gear", { adding = true }, divider = false) }
    }

    if (adding) GearEditorSheet(null, units, onDismiss = { adding = false })
    editing?.let { GearEditorSheet(it, units, onDismiss = { editing = null }) }
}

@Composable
private fun GearRow(item: Gear, distance: Double, count: Int, units: UnitSystem, onClick: () -> Unit) {
    val limit = if (item.retirementDistance > 0) item.retirementDistance else item.kind.defaultRetirementDistance
    val fraction = if (limit > 0) distance / limit else 0.0
    Column(Modifier.fillMaxWidth().clickableRow(onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (item.kind == GearKind.Shoes) AppIcons.Run else AppIcons.Bike, null, tint = StrideColors.Green, modifier = Modifier.size(18.dp))
            Text(item.displayName, Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
            if (item.isDefault) Pill("Default")
            Text(Fmt.distanceWithUnit(distance, units, 0), style = StrideType.figure(14, FontWeight.SemiBold))
        }
        StrideProgressBar(fraction.toFloat(), color = if (fraction > 0.9) StrideColors.Negative else if (fraction > 0.75) StrideColors.Warning else StrideColors.Positive, height = 5.dp)
        Row {
            Text("$count activities", Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 10.sp, color = StrideColors.SecondaryText))
            Text(
                if (fraction >= 1) "Past its recommended life" else Fmt.distanceWithUnit(maxOf(0.0, limit - distance), units, 0) + " to go",
                style = StrideType.Body.copy(fontSize = 10.sp, color = if (fraction >= 1) StrideColors.Negative else StrideColors.SecondaryText),
            )
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GearEditorSheet(gear: Gear?, units: UnitSystem, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val store = graph.store
    val state = rememberStoreState()
    var draft by remember { mutableStateOf(gear ?: Gear(kind = GearKind.Shoes, name = "")) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = StrideColors.PageBackground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (gear == null) "Add Gear" else "Edit Gear", Modifier.weight(1f), style = StrideType.Body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                TextButton(enabled = draft.name.isNotEmpty() || draft.brand.isNotEmpty(), onClick = {
                    val item = draft.copy(name = draft.name.ifEmpty { "${draft.brand} ${draft.model}".trim() })
                    if (item.isDefault) {
                        for (other in state.gear) if (other.kind == item.kind && other.id != item.id && other.isDefault) store.updateGear(other.copy(isDefault = false))
                    }
                    if (gear == null) store.addGear(item) else store.updateGear(item)
                    onDismiss()
                }) { Text("Save", color = StrideColors.Green, fontWeight = FontWeight.SemiBold) }
            }
            FormSection {
                PickerRow("Type", GearKind.entries, draft.kind, { it.displayName }, { draft = draft.copy(kind = it) })
                TextFieldRow("Nickname", draft.name, { draft = draft.copy(name = it) })
                TextFieldRow("Brand", draft.brand, { draft = draft.copy(brand = it) })
                TextFieldRow("Model", draft.model, { draft = draft.copy(model = it) }, divider = false)
            }
            FormSection(header = "Mileage") {
                NumberFieldRow("Already on it", draft.startingDistance / units.metersPerUnit, { v -> draft = draft.copy(startingDistance = v * units.metersPerUnit) }, 1, units.distanceUnit)
                NumberFieldRow("Remind me at", draft.retirementDistance / units.metersPerUnit, { v -> draft = draft.copy(retirementDistance = v * units.metersPerUnit) }, 1, units.distanceUnit, divider = false)
            }
            if (draft.kind == GearKind.Bike) {
                FormSection(header = "Bike", footer = "Used by the power estimate. Leave at zero to fall back to the default in your profile.") {
                    NumberFieldRow("Weight", draft.weightKg / units.kilogramsPerWeightUnit, { v -> draft = draft.copy(weightKg = v * units.kilogramsPerWeightUnit) }, 1, units.weightUnit, divider = false)
                }
            }
            FormSection {
                ToggleRow("Use by default", draft.isDefault, { draft = draft.copy(isDefault = it) })
                ToggleRow("Retired", draft.isRetired, { draft = draft.copy(isRetired = it) }, divider = false)
            }
            NotesField(draft.notes, { draft = draft.copy(notes = it) }, "Notes", minLines = 2)
            if (gear != null) {
                FormSection { ButtonRow("Delete", { store.deleteGear(gear); onDismiss() }, destructive = true, divider = false) }
            }
        }
    }
}
