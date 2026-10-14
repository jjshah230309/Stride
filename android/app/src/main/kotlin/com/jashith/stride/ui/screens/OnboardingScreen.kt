package com.jashith.stride.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.health.rememberHealthPermissionRequest
import com.jashith.stride.ui.Icons as AppIcons
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.components.FormSection
import com.jashith.stride.ui.components.NumberFieldRow
import com.jashith.stride.ui.components.PickerRow
import com.jashith.stride.ui.components.PrimaryButton
import com.jashith.stride.ui.components.SegmentedPicker
import com.jashith.stride.ui.components.TextFieldRow
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import kotlinx.coroutines.launch

/** A short first-run flow: units, a couple of body numbers, and the permissions the app genuinely needs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val graph = LocalGraph.current
    val store = graph.store
    val state = rememberStoreState()
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { 3 }

    Column(Modifier.fillMaxSize().background(StrideColors.PageBackground).statusBarsPadding().navigationBarsPadding()) {
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            when (page) {
                0 -> Welcome()
                1 -> ProfilePage()
                else -> PermissionsPage()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { i ->
                Box(Modifier.padding(4.dp).size(7.dp).clip(CircleShape).background(if (i == pager.currentPage) StrideColors.Green else StrideColors.Track))
            }
        }
        PrimaryButton(
            if (pager.currentPage < 2) "Continue" else "Start training",
            onClick = {
                if (pager.currentPage < 2) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                else {
                    store.updateSettings { it.copy(display = it.display.copy(hasCompletedOnboarding = true)) }
                    store.flush()
                    onDone()
                }
            },
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(108.dp).clip(CircleShape).background(StrideColors.Lime), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Run, null, tint = StrideColors.DeepGreen, modifier = Modifier.size(52.dp))
        }
        Text("Stride", style = StrideType.figure(40))
        Text(
            "GPS tracking, live splits read aloud, segments and a full training log — all on your phone, nothing behind a paywall.",
            style = StrideType.Body.copy(fontSize = 16.sp, color = StrideColors.SecondaryText, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun ProfilePage() {
    val graph = LocalGraph.current
    val store = graph.store
    val state = rememberStoreState()
    val profile = state.settings.profile
    val units = state.settings.units
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("About you", style = StrideType.ScreenTitle)
        FormSection(header = "What should we call you?") {
            TextFieldRow("Name", profile.name, { v -> store.updateSettings { it.copy(profile = it.profile.copy(name = v)) } }, placeholder = "Your name", divider = false)
        }
        FormSection(header = "Units") {
            Box(Modifier.padding(12.dp)) {
                SegmentedPicker(UnitSystem.entries, units, { it.title }, { u -> store.updateSettings { it.copy(display = it.display.copy(units = u)) } })
            }
        }
        FormSection(
            header = "About you",
            footer = "These feed the calorie, power and training-load estimates. You can change them later in your profile.",
        ) {
            NumberFieldRow("Weight", profile.weightKg / units.kilogramsPerWeightUnit, { v -> store.updateSettings { it.copy(profile = it.profile.copy(weightKg = v * units.kilogramsPerWeightUnit)) } }, 1, units.weightUnit)
            PickerRow("Born", (1930..2018).toList().reversed(), profile.birthYear, { "$it" }, { v -> store.updateSettings { it.copy(profile = it.profile.copy(birthYear = v)) } })
            PickerRow("Sex", listOf(true, false), profile.isMale, { if (it) "Male" else "Female" }, { v -> store.updateSettings { it.copy(profile = it.profile.copy(isMale = v)) } }, divider = false)
        }
    }
}

@Composable
private fun PermissionsPage() {
    val graph = LocalGraph.current
    val requestHealth = rememberHealthPermissionRequest(graph.health)
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { graph.location.refreshPermission() }
    val extras = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("Permissions", style = StrideType.ScreenTitle, modifier = Modifier.padding(top = 16.dp))
        PermissionRow(
            Icons.Filled.LocationOn, "Location",
            "Required to record your route, distance, pace and elevation. Recording keeps running with the screen off, shown by a notification.",
            "Allow",
        ) { location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        PermissionRow(
            AppIcons.Run, "Notifications and motion",
            "Optional. The notification lets you pause from the lock screen; motion counts steps for running cadence.",
            "Allow",
        ) {
            val list = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            if (Build.VERSION.SDK_INT >= 29) list += Manifest.permission.ACTIVITY_RECOGNITION
            if (list.isNotEmpty()) extras.launch(list.toTypedArray())
        }
        PermissionRow(
            Icons.Filled.Favorite, "Health Connect",
            "Optional. Reads heart rate from a watch and writes finished workouts back so they appear in your other health apps.",
            if (graph.health.isAvailable) "Allow" else null,
        ) { requestHealth() }
        PermissionRow(
            Icons.Filled.VolumeUp, "Audio",
            "No permission needed — splits are spoken over your music, ducking it briefly rather than stopping it.",
            null, {},
        )
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, button: String?, action: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(StrideColors.Lime), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = StrideColors.DeepGreen, modifier = Modifier.size(19.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = StrideType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            Text(detail, style = StrideType.Body.copy(fontSize = 13.sp, color = StrideColors.SecondaryText))
            if (button != null) {
                Text(
                    button, Modifier.clip(CircleShape).clickableNoRipple(action).padding(top = 2.dp, bottom = 2.dp),
                    style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = StrideColors.Green),
                )
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
