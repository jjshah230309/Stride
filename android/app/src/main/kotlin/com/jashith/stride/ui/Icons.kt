package com.jashith.stride.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.ui.graphics.vector.ImageVector
import com.jashith.stride.core.model.SportType
import com.jashith.stride.ui.components.AppTab

/** Material stand-ins for the SF Symbols the iPhone app uses. */
object Icons {
    val Run: ImageVector get() = Icons.AutoMirrored.Filled.DirectionsRun
    val Walk: ImageVector get() = Icons.AutoMirrored.Filled.DirectionsWalk
    val Hike: ImageVector get() = Icons.Filled.Hiking
    val Ride: ImageVector get() = Icons.AutoMirrored.Filled.DirectionsBike
    val Bike: ImageVector get() = Icons.Filled.PedalBike
    val EBike: ImageVector get() = Icons.Filled.ElectricBike
    val Indoor: ImageVector get() = Icons.Filled.FitnessCenter
    val Mountain: ImageVector get() = Icons.Filled.Terrain

    fun forTab(tab: AppTab): ImageVector = when (tab) {
        AppTab.Today -> Icons.Filled.Timeline
        AppTab.Record -> Icons.Filled.RadioButtonChecked
        AppTab.Progress -> Icons.Filled.EmojiEvents
        AppTab.Routes -> Icons.Filled.Map
    }

    fun forSport(sport: SportType): ImageVector = when (sport) {
        SportType.Run, SportType.TrailRun, SportType.Treadmill -> Run
        SportType.Walk -> Walk
        SportType.Hike -> Hike
        SportType.Ride, SportType.GravelRide -> Ride
        SportType.MountainBike -> Bike
        SportType.EBike -> EBike
        SportType.IndoorRide -> Indoor
    }
}
