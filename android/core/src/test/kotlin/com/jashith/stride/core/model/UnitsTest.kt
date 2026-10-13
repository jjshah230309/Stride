package com.jashith.stride.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @Test fun durations() {
        assertEquals("1:02:03", Fmt.duration(3723.0))
        assertEquals("5:03", Fmt.duration(303.0))
        assertEquals("--:--", Fmt.duration(-1.0))
        assertEquals("0:00:09", Fmt.durationPadded(9.0))
        assertEquals("1h 24m", Fmt.durationCompact(5040.0))
        assertEquals("45s", Fmt.durationCompact(45.0))
    }

    @Test fun paceAndSpeed() {
        assertEquals("4:32", Fmt.paceFromSeconds(272.0))
        assertEquals("--:--", Fmt.paceFromSeconds(0.0))
        assertEquals("5:00", Fmt.pace(1000.0, 300.0, UnitSystem.Metric))
        assertEquals("8:03", Fmt.pace(1609.344, 483.0, UnitSystem.Imperial))
        assertEquals("--:--", Fmt.paceFromSpeed(0.1, UnitSystem.Metric))
        assertEquals("36.0", Fmt.speed(10.0, UnitSystem.Metric))
        assertEquals("22.4", Fmt.speed(10.0, UnitSystem.Imperial))
    }

    @Test fun scalars() {
        assertEquals("1", Fmt.trimmed(1.0))
        assertEquals("0.5", Fmt.trimmed(0.5))
        assertEquals("0.25", Fmt.trimmed(0.25))
        assertEquals("+3", Fmt.signed(3.0))
        assertEquals("-2.5", Fmt.signed(-2.5, 1))
        assertEquals("0", Fmt.signed(0.0))
        assertEquals("--", Fmt.int(null))
        assertEquals("43%", Fmt.percent(0.426))
        assertEquals("1.50", Fmt.distance(1500.0, UnitSystem.Metric))
        assertEquals("0.93", Fmt.distance(1500.0, UnitSystem.Imperial))
    }

    @Test fun defaultNameFollowsTheHour() {
        val zone = java.time.ZoneId.of("UTC")
        fun at(h: Int) = java.time.ZonedDateTime.of(2026, 1, 1, h, 0, 0, 0, zone).toInstant()
        assertEquals("Morning Run", Activity.defaultName(SportType.Run, at(7), zone))
        assertEquals("Afternoon Ride", Activity.defaultName(SportType.Ride, at(13), zone))
        assertEquals("Evening MTB", Activity.defaultName(SportType.MountainBike, at(18), zone))
        assertEquals("Night Walk", Activity.defaultName(SportType.Walk, at(2), zone))
    }

    @Test fun sportRules() {
        assertEquals(true, SportType.TrailRun.isRun)
        assertEquals(true, SportType.EBike.isRide)
        assertEquals(true, SportType.Hike.isFoot)
        assertEquals(false, SportType.Hike.isRun)
        assertEquals(GearKind.Bike, SportType.GravelRide.gearKind)
        assertEquals(GearKind.Shoes, SportType.Treadmill.gearKind)
        assertEquals(1.2, SportType.Ride.autoPauseThreshold, 0.0)
        assertEquals(9.0, SportType.Run.maxPlausibleSpeed, 0.0)
    }
}
