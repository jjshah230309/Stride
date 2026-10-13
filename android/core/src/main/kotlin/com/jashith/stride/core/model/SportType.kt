package com.jashith.stride.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SportType(val raw: String) {
    @SerialName("run") Run("run"),
    @SerialName("trailRun") TrailRun("trailRun"),
    @SerialName("treadmill") Treadmill("treadmill"),
    @SerialName("walk") Walk("walk"),
    @SerialName("hike") Hike("hike"),
    @SerialName("ride") Ride("ride"),
    @SerialName("gravelRide") GravelRide("gravelRide"),
    @SerialName("mountainBike") MountainBike("mountainBike"),
    @SerialName("eBike") EBike("eBike"),
    @SerialName("indoorRide") IndoorRide("indoorRide");

    val displayName: String
        get() = when (this) {
            Run -> "Run"
            TrailRun -> "Trail Run"
            Treadmill -> "Treadmill Run"
            Walk -> "Walk"
            Hike -> "Hike"
            Ride -> "Ride"
            GravelRide -> "Gravel Ride"
            MountainBike -> "Mountain Bike Ride"
            EBike -> "E-Bike Ride"
            IndoorRide -> "Indoor Ride"
        }

    val shortName: String
        get() = when (this) {
            TrailRun -> "Trail"
            MountainBike -> "MTB"
            GravelRide -> "Gravel"
            IndoorRide -> "Indoor"
            EBike -> "E-Bike"
            else -> displayName
        }

    val isRun: Boolean get() = this == Run || this == TrailRun || this == Treadmill
    val isRide: Boolean get() = this == Ride || this == GravelRide || this == MountainBike || this == EBike || this == IndoorRide
    val isFoot: Boolean get() = isRun || this == Walk || this == Hike
    val isIndoor: Boolean get() = this == Treadmill || this == IndoorRide

    /** Runs and walks are read in pace; rides are read in speed. */
    val usesPace: Boolean get() = isFoot

    val gearKind: GearKind get() = if (isRide) GearKind.Bike else GearKind.Shoes

    /** Speed below which we consider the athlete stopped, in m/s. */
    val autoPauseThreshold: Double get() = if (isRide) 1.2 else 0.5

    /** Speeds above this are GPS noise, in m/s. */
    val maxPlausibleSpeed: Double get() = if (isRide) 30.0 else 9.0

    /** Rough MET value at moderate effort, used when heart rate is unavailable. */
    val baseMET: Double
        get() = when (this) {
            Run, TrailRun, Treadmill -> 9.8
            Walk -> 3.8
            Hike -> 6.0
            Ride, GravelRide, IndoorRide -> 8.0
            MountainBike -> 8.5
            EBike -> 5.0
        }

    companion object {
        val runGroup: List<SportType> = listOf(Run, TrailRun, Treadmill, Walk, Hike)
        val rideGroup: List<SportType> = listOf(Ride, GravelRide, MountainBike, EBike, IndoorRide)
    }
}
