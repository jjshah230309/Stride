package com.jashith.stride.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jashith.stride.ui.components.AppTab
import java.util.UUID

/** Every screen that sits above the tab bar. */
sealed interface Dest {
    data class ActivityDetail(val id: UUID) : Dest
    data class SegmentDetail(val id: UUID) : Dest
    data class RouteDetail(val id: UUID) : Dest
    data class SegmentCreator(val activityId: UUID) : Dest
    data class RouteBuilder(val seedRouteId: UUID? = null) : Dest
    data object Profile : Dest
    data object AthleteProfile : Dest
    data object Gear : Dest
    data object HeartRateMonitor : Dest
    data object Data : Dest
    data object SavedBackups : Dest
    data object About : Dest
    data object RecordingSettings : Dest
    data object VoiceSettings : Dest
    data object ManualActivity : Dest
    data object SaveActivity : Dest
}

/** A hand-rolled back stack: the app has few screens and no deep links, so a list is all it takes. */
class Navigator {
    var tab by mutableStateOf(AppTab.Today)
    val stack = mutableStateListOf<Dest>()

    val top: Dest? get() = stack.lastOrNull()

    fun push(dest: Dest) {
        if (stack.lastOrNull() != dest) stack.add(dest)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replaces the current screen, for example from Save to the new activity. */
    fun replaceTop(dest: Dest) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(dest)
    }

    fun popToRoot() = stack.clear()
}
