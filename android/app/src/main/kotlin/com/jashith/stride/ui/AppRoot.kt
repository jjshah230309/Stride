package com.jashith.stride.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.jashith.stride.AppGraph
import com.jashith.stride.ui.components.AppTab
import com.jashith.stride.ui.components.StrideTabBar
import com.jashith.stride.ui.screens.AboutScreen
import com.jashith.stride.ui.screens.ActivityDetailScreen
import com.jashith.stride.ui.screens.AthleteProfileScreen
import com.jashith.stride.ui.screens.DataScreen
import com.jashith.stride.ui.screens.GearScreen
import com.jashith.stride.ui.screens.HeartRateMonitorScreen
import com.jashith.stride.ui.screens.ManualActivityScreen
import com.jashith.stride.ui.screens.OnboardingScreen
import com.jashith.stride.ui.screens.ProfileScreen
import com.jashith.stride.ui.screens.ProgressScreen
import com.jashith.stride.ui.screens.RecordScreen
import com.jashith.stride.ui.screens.RecordingSettingsScreen
import com.jashith.stride.ui.screens.RouteBuilderScreen
import com.jashith.stride.ui.screens.RouteDetailScreen
import com.jashith.stride.ui.screens.RoutesScreen
import com.jashith.stride.ui.screens.SaveActivityScreen
import com.jashith.stride.ui.screens.SavedBackupsScreen
import com.jashith.stride.ui.screens.SegmentCreatorScreen
import com.jashith.stride.ui.screens.SegmentDetailScreen
import com.jashith.stride.ui.screens.TodayScreen
import com.jashith.stride.ui.screens.VoiceSettingsScreen
import com.jashith.stride.ui.theme.StrideColors
import kotlinx.coroutines.launch

@Composable
fun AppRoot(graph: AppGraph) {
    val navigator = remember { Navigator() }
    val state = rememberStoreStateOf(graph)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialogMessage by remember { mutableStateOf<String?>(null) }
    val message: (String) -> Unit = { text ->
        if (text.length > 90 || '\n' in text) dialogMessage = text else scope.launch { snackbar.showSnackbar(text) }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> scope.launch { graph.store.runAutomaticBackupIfDue() }
                Lifecycle.Event.ON_STOP -> graph.store.flush()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Keep the screen on while recording, if asked to.
    val stats by graph.recorder.stats.collectAsState()
    val view = LocalView.current
    val keepOn = state.settings.display.keepScreenOn && (stats.state.isActive || stats.state == com.jashith.stride.core.recording.RecordingState.Countdown)
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = navigator.stack.isNotEmpty()) { navigator.pop() }
    BackHandler(enabled = navigator.stack.isEmpty() && navigator.tab != AppTab.Today) { navigator.tab = AppTab.Today }

    CompositionLocalProvider(LocalGraph provides graph, LocalNavigator provides navigator, LocalMessage provides message) {
        Box(Modifier.fillMaxSize().background(StrideColors.PageBackground)) {
            if (!state.loaded) {
                // The store reads its files before anything is drawn, so a returning user never sees the welcome flow flash up.
                return@Box
            }
            if (!state.settings.display.hasCompletedOnboarding) {
                OnboardingScreen(onDone = {})
                return@Box
            }
            val top = navigator.top
            Crossfade(top, label = "screen") { dest ->
                if (dest == null) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            when (navigator.tab) {
                                AppTab.Today -> TodayScreen(onMessage = message)
                                AppTab.Record -> RecordScreen()
                                AppTab.Progress -> ProgressScreen()
                                AppTab.Routes -> RoutesScreen()
                            }
                        }
                        StrideTabBar(navigator.tab, { navigator.tab = it })
                    }
                } else {
                    Screen(dest)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
        }
        dialogMessage?.let {
            AlertDialog(
                onDismissRequest = { dialogMessage = null },
                text = { Text(it) },
                confirmButton = { TextButton(onClick = { dialogMessage = null }) { Text("OK") } },
            )
        }
    }
}

@Composable
private fun rememberStoreStateOf(graph: AppGraph) = graph.store.state.collectAsState().value

@Composable
private fun Screen(dest: Dest) {
    when (dest) {
        is Dest.ActivityDetail -> ActivityDetailScreen(dest.id)
        is Dest.SegmentDetail -> SegmentDetailScreen(dest.id)
        is Dest.RouteDetail -> RouteDetailScreen(dest.id)
        is Dest.SegmentCreator -> SegmentCreatorScreen(dest.activityId)
        is Dest.RouteBuilder -> RouteBuilderScreen()
        Dest.Profile -> ProfileScreen()
        Dest.AthleteProfile -> AthleteProfileScreen()
        Dest.Gear -> GearScreen()
        Dest.HeartRateMonitor -> HeartRateMonitorScreen()
        Dest.Data -> DataScreen()
        Dest.SavedBackups -> SavedBackupsScreen()
        Dest.About -> AboutScreen()
        Dest.RecordingSettings -> RecordingSettingsScreen()
        Dest.VoiceSettings -> VoiceSettingsScreen()
        Dest.ManualActivity -> ManualActivityScreen()
        Dest.SaveActivity -> SaveActivityScreen()
    }
}
