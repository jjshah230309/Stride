package com.jashith.stride.core

import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.GearKind
import com.jashith.stride.core.model.Goal
import com.jashith.stride.core.model.GoalMetric
import com.jashith.stride.core.model.GoalPeriod
import com.jashith.stride.core.model.SavedRoute
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SegmentEffort
import com.jashith.stride.core.model.Split
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import java.time.Instant
import java.util.UUID

object TestData {
    val started: Instant = Instant.parse("2026-03-14T07:30:05Z")

    fun id(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    fun track(n: Int = 120, withHr: Boolean = true): List<TrackPoint> = (0 until n).map { i ->
        TrackPoint(
            t = i.toDouble(),
            lat = 51.5 + i * 0.00002,
            lon = -0.12 + i * 0.00001,
            alt = 20.0 + i * 0.05,
            d = i * 3.0,
            v = 3.0,
            hr = if (withHr && i % 2 == 0) 140.0 + i % 10 else null,
            cad = if (i % 3 == 0) 170.0 else null,
            pw = null,
            moving = i % 50 != 49,
            acc = 4.5,
        )
    }

    fun activity(n: Int, sport: SportType = SportType.Run, start: Instant = started.plusSeconds(n * 86_400L)): Activity =
        Activity(
            id = id(n),
            name = "Morning Run $n",
            sport = sport,
            startDate = start,
            elapsed = 360.0,
            movingTime = 355.0,
            distance = 357.0,
            elevationGain = 6.0,
            avgHR = 145.0,
            maxHR = 151.0,
            splitsKm = listOf(Split(id = id(1000 + n), index = 1, distance = 357.0, elapsed = 360.0, moving = 355.0, elevGain = 6.0, elevLoss = 0.0, isPartial = true)),
            hrZoneTimes = listOf(0.0, 100.0, 255.0, 0.0, 0.0),
            previewPath = listOf(Coord(51.5, -0.12), Coord(51.502, -0.119)),
            startCoord = Coord(51.5, -0.12),
            gearID = id(500),
        )

    fun segment(n: Int = 1) = Segment(
        id = id(2000 + n), name = "Hill $n", sport = SportType.Run,
        path = listOf(Coord(51.5, -0.12), Coord(51.501, -0.119), Coord(51.502, -0.118)),
        distance = 250.0, elevationGain = 12.0, averageGrade = 0.048, maxGrade = 0.09,
        createdAt = started, createdFromActivityID = id(1),
    )

    fun effort(n: Int = 1) = SegmentEffort(
        id = id(3000 + n), segmentID = id(2001), activityID = id(1), date = started,
        time = 60.0, startIndex = 3, endIndex = 40, avgSpeed = 4.1, isPR = true, rank = 1,
    )

    fun route() = SavedRoute(
        id = id(4000), name = "Loop", sport = SportType.Ride,
        path = listOf(Coord(51.5, -0.12), Coord(51.51, -0.11)), elevations = listOf(10.0, 15.0),
        distance = 1400.0, elevationGain = 5.0, createdAt = started,
    )

    fun gear() = Gear(id = id(500), kind = GearKind.Shoes, name = "Pegasus", brand = "Nike", model = "Pegasus 41", addedDate = started)

    fun goal() = Goal(id = id(6000), metric = GoalMetric.Distance, period = GoalPeriod.Weekly, sports = listOf(SportType.Run), target = 30_000.0, createdAt = started)
}
