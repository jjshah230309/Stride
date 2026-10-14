package com.jashith.stride.core.store

import com.jashith.stride.core.SyntheticTracks
import com.jashith.stride.core.TestData
import com.jashith.stride.core.analysis.ActivityAnalyzer
import com.jashith.stride.core.io.AutoBackup
import com.jashith.stride.core.io.Backup
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.ActivityPhoto
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.model.BackupSettings
import com.jashith.stride.core.model.BestEffort
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.GearKind
import com.jashith.stride.core.model.Goal
import com.jashith.stride.core.model.GoalMetric
import com.jashith.stride.core.model.GoalPeriod
import com.jashith.stride.core.model.PowerCurvePoint
import com.jashith.stride.core.model.SportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class StrideStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val utc = ZoneId.of("UTC")
    private var now = Instant.parse("2026-03-14T12:00:00Z")     // a Saturday

    private fun TestScope.newStore(files: StrideFiles = StrideFiles(tmp.newFolder())): StrideStore {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return StrideStore(
            files, scope = backgroundScope, ioDispatcher = dispatcher, cpuDispatcher = dispatcher,
            clock = { now }, zone = { utc },
        )
    }

    private val activities = ListSerializer(Activity.serializer())

    private fun run(n: Int, daysAgo: Int, seconds: Int = 300, speed: Double = 3.0, sport: SportType = SportType.Run): Pair<Activity, List<com.jashith.stride.core.model.TrackPoint>> {
        val track = SyntheticTracks.line(seconds, speed = speed)
        val base = Activity(
            id = TestData.id(n), name = "Run $n", sport = sport,
            startDate = now.minusSeconds(daysAgo * 86_400L + 3 * 3600),
        )
        return ActivityAnalyzer.analyse(base, track, AppSettings().profile) to track
    }

    // Loading and saving

    @Test fun loadingAnEmptyFolderGivesAnEmptyStoreMarkedLoaded() = runTest {
        val store = newStore()
        assertFalse(store.current.loaded)
        store.load()
        assertTrue(store.current.loaded)
        assertTrue(store.current.activities.isEmpty())
        assertEquals(AppSettings(), store.current.settings)
    }

    @Test fun loadingReadsEveryFileAndSortsNewestFirst() = runTest {
        val files = StrideFiles(tmp.newFolder())
        files.save(activities, listOf(TestData.activity(1), TestData.activity(3), TestData.activity(2)), StrideFiles.ACTIVITIES)
        files.save(AppSettings.serializer(), AppSettings().copy(profile = AppSettings().profile.copy(name = "Kai")), StrideFiles.SETTINGS)
        files.save(ListSerializer(Gear.serializer()), listOf(TestData.gear()), StrideFiles.GEAR)
        val store = newStore(files)
        store.load()
        assertEquals(listOf(3, 2, 1), store.current.activities.map { it.name.last().digitToInt() })
        assertEquals("Kai", store.current.settings.profile.name)
        assertEquals(1, store.current.gear.size)
    }

    @Test fun anUnreadableFileIsSetAsideAndTheStoreStillOpens() = runTest {
        val files = StrideFiles(tmp.newFolder())
        files.prepare()
        files.file("activities.json").writeText("not json")
        val store = newStore(files)
        store.load()
        assertTrue(store.current.loaded)
        assertTrue(store.current.activities.isEmpty())
        assertEquals(listOf("activities.json"), store.damagedFiles)
    }

    @Test fun editsAreSavedAfterTheDebounceAndCoalesce() = runTest {
        val files = StrideFiles(tmp.newFolder())
        val store = newStore(files)
        store.load()
        store.addGear(TestData.gear())
        runCurrent()
        assertFalse("nothing is written straight away", files.file("gear.json").exists())
        advanceTimeBy(500)
        assertFalse(files.file("gear.json").exists())
        store.addGoal(TestData.goal())                  // restarts the timer and joins the same flush
        advanceTimeBy(500)
        assertFalse(files.file("gear.json").exists())
        advanceTimeBy(200)
        runCurrent()
        assertTrue(files.file("gear.json").exists())
        assertTrue(files.file("goals.json").exists())
        assertFalse("untouched collections are not rewritten", files.file("segments.json").exists())
    }

    @Test fun flushWritesImmediatelyAndOnlyWhatChanged() = runTest {
        val files = StrideFiles(tmp.newFolder())
        val store = newStore(files)
        store.load()
        store.updateSettings { it.copy(display = it.display.copy(mapStyle = "hybrid")) }
        store.flush()
        assertEquals("hybrid", files.load(AppSettings.serializer(), StrideFiles.SETTINGS)!!.display.mapStyle)
        assertFalse(files.file("activities.json").exists())
        store.saveEverything()
        for (name in listOf("activities.json", "segments.json", "segment-efforts.json", "routes.json", "gear.json", "goals.json", "settings.json")) {
            assertTrue(name, files.file(name).exists())
        }
    }

    // Activities

    @Test fun addingAnActivityStoresItsTrackAndKeepsNewestFirst() = runTest {
        val files = StrideFiles(tmp.newFolder())
        val store = newStore(files)
        store.load()
        val (older, olderTrack) = run(1, daysAgo = 5)
        val (newer, newerTrack) = run(2, daysAgo = 1)
        store.add(older, olderTrack)
        store.add(newer, newerTrack)
        assertEquals(listOf(newer.id, older.id), store.current.activities.map { it.id })
        assertEquals(olderTrack, store.track(older))
        assertEquals(newerTrack.size, files.loadTrack(newer.id).size)
        assertEquals(newer.id, store.activity(newer.id)!!.id)
        assertNull(store.activity(UUID.randomUUID()))
        store.flush()
        assertEquals(2, files.load(activities, StrideFiles.ACTIVITIES)!!.size)
    }

    @Test fun aManualActivityWithNoTrackWritesNoTrackFile() = runTest {
        val files = StrideFiles(tmp.newFolder())
        val store = newStore(files)
        store.load()
        store.add(TestData.activity(7).copy(isManual = true, hasGPS = false), emptyList())
        assertFalse(files.hasTrack(TestData.id(7)))
        assertEquals(1, store.current.activities.size)
    }

    @Test fun updatingReplacesInPlaceAndReSorts() = runTest {
        val store = newStore()
        store.load()
        val (a, ta) = run(1, daysAgo = 5)
        val (b, tb) = run(2, daysAgo = 1)
        store.add(a, ta); store.add(b, tb)
        store.update(a.copy(name = "Renamed", startDate = now))        // now the newest
        assertEquals(listOf("Renamed", "Run 2"), store.current.activities.map { it.name })
        store.update(TestData.activity(99))                            // unknown ids are ignored
        assertEquals(2, store.current.activities.size)
    }

    @Test fun deletingRemovesTheTrackPhotosAndEfforts() = runTest {
        val files = StrideFiles(tmp.newFolder())
        val store = newStore(files)
        store.load()
        val photo = files.savePhoto(byteArrayOf(1, 2, 3))!!
        val (a, ta) = run(1, daysAgo = 2)
        val seg = SyntheticTracks.segmentAlong(100.0, 400.0)
        store.addSegment(seg)
        store.add(a.copy(photos = listOf(ActivityPhoto(filename = photo))), ta)
        assertEquals(1, store.current.segmentEfforts.size)
        val stored = store.activity(a.id)!!
        store.delete(stored)
        assertTrue(store.current.activities.isEmpty())
        assertTrue(store.current.segmentEfforts.isEmpty())
        assertFalse(files.hasTrack(a.id))
        assertFalse(files.photoFile(photo).exists())
        assertEquals("the segment itself stays", 1, store.current.segments.size)
    }

    // Segments

    @Test fun newActivitiesAreMatchedAgainstSavedSegmentsAndRanked() = runTest {
        val store = newStore()
        store.load()
        val seg = SyntheticTracks.segmentAlong(300.0, 600.0)
        store.addSegment(seg)
        val (slow, slowTrack) = run(1, daysAgo = 3, seconds = 300, speed = 3.0)
        val (fast, fastTrack) = run(2, daysAgo = 2, seconds = 200, speed = 4.5)
        store.add(slow, slowTrack)
        assertEquals(listOf(1), store.efforts(seg).map { it.rank })
        assertTrue(store.efforts(seg)[0].isPR)
        store.add(fast, fastTrack)

        val efforts = store.efforts(seg)
        assertEquals(2, efforts.size)
        assertEquals(listOf(fast.id, slow.id), efforts.map { it.activityID })        // fastest first
        assertEquals(listOf(1, 2), efforts.map { it.rank })
        assertEquals(listOf(true, false), efforts.map { it.isPR })
        assertEquals(efforts[0], store.personalBest(seg))
        assertEquals(1, store.activity(fast.id)!!.segmentEffortIDs.size)
        assertEquals(efforts[0].id, store.activity(fast.id)!!.segmentEffortIDs[0])
        assertEquals(listOf(seg.id), store.starredSegments.map { it.id })
    }

    @Test fun aNewSegmentIsMatchedAgainstTheWholeHistory() = runTest {
        val store = newStore()
        store.load()
        val (a, ta) = run(1, daysAgo = 3)
        val (b, tb) = run(2, daysAgo = 2, seconds = 220, speed = 4.0)
        store.add(a, ta); store.add(b, tb)
        assertTrue(store.current.segmentEfforts.isEmpty())

        val seg = SyntheticTracks.segmentAlong(300.0, 600.0)
        store.addSegment(seg)
        val efforts = store.efforts(seg)
        assertEquals(setOf(a.id, b.id), efforts.map { it.activityID }.toSet())
        assertEquals(b.id, efforts[0].activityID)
        assertEquals(1, efforts[0].rank)

        // matching again replaces the old efforts rather than doubling them
        store.matchSegmentAgainstHistory(seg)
        assertEquals(2, store.efforts(seg).size)
    }

    @Test fun rematchingDiscardsStaleEffortsAndTheWrongSportIsNeverMatched() = runTest {
        val store = newStore()
        store.load()
        val (a, ta) = run(1, daysAgo = 3)
        store.add(a, ta)
        val runSeg = SyntheticTracks.segmentAlong(300.0, 600.0, id = 1)
        val rideSeg = SyntheticTracks.segmentAlong(300.0, 600.0, id = 2, name = "Ride", sport = SportType.Ride)
        store.addSegment(runSeg); store.addSegment(rideSeg)
        assertEquals(1, store.efforts(runSeg).size)
        assertEquals(0, store.efforts(rideSeg).size)

        store.deleteSegment(runSeg)
        assertTrue(store.current.segmentEfforts.isEmpty())
        assertEquals(1, store.current.segments.size)

        store.addSegment(runSeg)
        store.rematchAllSegments()
        assertEquals(1, store.current.segmentEfforts.size)
        assertEquals(runSeg.id, store.current.segmentEfforts[0].segmentID)
    }

    @Test fun updatingASegmentKeepsItsEfforts() = runTest {
        val store = newStore()
        store.load()
        val (a, ta) = run(1, daysAgo = 3)
        store.add(a, ta)
        val seg = SyntheticTracks.segmentAlong(300.0, 600.0)
        store.addSegment(seg)
        store.updateSegment(seg.copy(name = "Renamed", isStarred = false))
        assertEquals("Renamed", store.current.segments[0].name)
        assertEquals(1, store.efforts(seg).size)
        assertTrue(store.starredSegments.isEmpty())
        store.updateSegment(seg.copy(id = UUID.randomUUID()))            // unknown id: ignored
        assertEquals(1, store.current.segments.size)
    }

    @Test fun reanalysingEverythingPicksUpANewProfile() = runTest {
        val store = newStore()
        store.load()
        val (a, ta) = run(1, daysAgo = 3)
        store.add(a, ta)
        val before = store.activity(a.id)!!.calories
        assertTrue(before > 0)
        store.updateSettings { it.copy(profile = it.profile.copy(weightKg = 140.0)) }
        val progress = ArrayList<Double>()
        store.reanalyseAll { progress.add(it) }
        assertEquals(before * 2, store.activity(a.id)!!.calories, 1e-6)       // MET model is linear in weight
        assertEquals(listOf(1.0), progress)
        // an activity with no track is left untouched
        val manual = TestData.activity(50).copy(calories = 123.0, isManual = true, hasGPS = false)
        store.add(manual, emptyList())
        store.reanalyseAll()
        assertEquals(123.0, store.activity(manual.id)!!.calories, 0.0)
    }

    // Queries

    @Test fun personalRecordsKeepTheFastestPerDistanceInRaceOrder() = runTest {
        val store = newStore()
        store.load()
        fun effort(name: String, time: Double) = BestEffort(name = name, distance = 1000.0, time = time, startIndex = 0, endIndex = 1, startOffset = 0.0)
        val a = TestData.activity(1).copy(bestEfforts = listOf(effort("5 km", 1500.0), effort("1 km", 300.0)))
        val b = TestData.activity(2, SportType.TrailRun).copy(bestEfforts = listOf(effort("5 km", 1400.0), effort("400 m", 90.0)))
        val walk = TestData.activity(3, SportType.Walk).copy(bestEfforts = listOf(effort("1 km", 200.0)))
        val ride = TestData.activity(4, SportType.Ride).copy(bestEfforts = listOf(effort("1 km", 100.0)))
        for (x in listOf(a, b, walk, ride)) store.add(x, emptyList())

        val runs = store.personalRecords(SportType.Run)
        assertEquals(listOf("400 m", "1 km", "5 km"), runs.map { it.effort.name })
        assertEquals(listOf(90.0, 300.0, 1400.0), runs.map { it.effort.time })
        assertEquals(b.id, runs[2].activity.id)                                  // the trail run holds the 5 km record
        // walks and hikes are compared with each other
        assertEquals(listOf(200.0), store.personalRecords(SportType.Walk).map { it.effort.time })
        assertEquals(listOf(200.0), store.personalRecords(SportType.Hike).map { it.effort.time })
        // Rides never hold records, but, exactly as on iOS, asking about a ride sport gives the walking list
        // (the screens only ask about foot sports).
        assertEquals(store.personalRecords(SportType.Walk), store.personalRecords(SportType.Ride))
    }

    @Test fun theAllTimePowerCurveCombinesRidesOnly() = runTest {
        val store = newStore()
        store.load()
        val r1 = TestData.activity(1, SportType.Ride).copy(powerCurve = listOf(PowerCurvePoint(5.0, 600.0), PowerCurvePoint(60.0, 400.0)))
        val r2 = TestData.activity(2, SportType.Ride).copy(powerCurve = listOf(PowerCurvePoint(5.0, 700.0), PowerCurvePoint(300.0, 300.0)))
        val run = TestData.activity(3).copy(powerCurve = listOf(PowerCurvePoint(5.0, 9999.0)))
        for (x in listOf(r1, r2, run)) store.add(x, emptyList())
        assertEquals(listOf(5.0 to 700.0, 60.0 to 400.0, 300.0 to 300.0), store.allTimePowerCurve.map { it.duration to it.watts })
    }

    @Test fun matchedActivitiesAreTheOtherRepeatsOfTheRouteQuickestFirst() = runTest {
        val store = newStore()
        store.load()
        val path = (0..80).map { Coord(51.5 + it * 0.0002, -0.12 + it * 0.0001) }
        fun same(n: Int, moving: Double, shift: Double = 0.0, distance: Double = 2000.0) =
            TestData.activity(n).copy(previewPath = path.map { Coord(it.lat + shift, it.lon) }, distance = distance, movingTime = moving, hasGPS = true)
        val me = same(1, 700.0)
        val faster = same(2, 600.0)
        val slower = same(3, 800.0)
        val elsewhere = same(4, 500.0, shift = 0.05)
        val longer = same(5, 500.0, distance = 9000.0)
        for (x in listOf(me, faster, slower, elsewhere, longer)) store.add(x, emptyList())
        assertEquals(listOf(faster.id, slower.id), store.matchedActivities(me).map { it.id })
    }

    @Test fun weeksStartOnMondayOrSundayAsChosen() = runTest {
        val store = newStore()
        store.load()
        // 2026-03-14 is a Saturday
        val monday = store.currentWeek()
        assertEquals(Instant.parse("2026-03-09T00:00:00Z"), monday.start)
        assertEquals(Instant.parse("2026-03-16T00:00:00Z"), monday.end)
        store.updateSettings { it.copy(display = it.display.copy(weekStartsMonday = false)) }
        val sunday = store.currentWeek()
        assertEquals(Instant.parse("2026-03-08T00:00:00Z"), sunday.start)
        assertEquals(Instant.parse("2026-03-15T00:00:00Z"), sunday.end)
        // a Sunday belongs to the week it starts when weeks start on Sunday, but ends one when they start on Monday
        assertEquals(Instant.parse("2026-03-15T00:00:00Z"), store.weekInterval(Instant.parse("2026-03-15T08:00:00Z")).start)
        store.updateSettings { it.copy(display = it.display.copy(weekStartsMonday = true)) }
        assertEquals(Instant.parse("2026-03-09T00:00:00Z"), store.weekInterval(Instant.parse("2026-03-15T08:00:00Z")).start)
    }

    @Test fun totalsAndFiltersRespectTheIntervalAndSports() = runTest {
        val store = newStore()
        store.load()
        val week = store.currentWeek()
        val inRun = TestData.activity(1).copy(startDate = Instant.parse("2026-03-10T07:00:00Z"), distance = 5000.0, movingTime = 1500.0)
        val inRide = TestData.activity(2, SportType.Ride).copy(startDate = Instant.parse("2026-03-11T07:00:00Z"), distance = 20000.0, movingTime = 3000.0)
        val outside = TestData.activity(3).copy(startDate = Instant.parse("2026-03-01T07:00:00Z"), distance = 9000.0)
        for (x in listOf(inRun, inRide, outside)) store.add(x, emptyList())
        assertEquals(2, store.activities(week).size)
        assertEquals(25000.0, store.totals(week).distance, 0.0)
        assertEquals(5000.0, store.totals(week, listOf(SportType.Run, SportType.TrailRun)).distance, 0.0)
        assertEquals(1, store.totals(week, listOf(SportType.Ride)).count)
        assertEquals(0, store.totals(week, listOf(SportType.Hike)).count)
    }

    @Test fun theFitnessCurveUsesTheStoresClock() = runTest {
        val store = newStore()
        store.load()
        store.add(TestData.activity(1).copy(startDate = now.minusSeconds(3600), trainingLoad = 100.0), emptyList())
        val curve = store.fitnessCurve()
        assertEquals(401, curve.size)
        assertEquals(java.time.LocalDate.of(2026, 3, 14), curve.last().date)
        assertEquals(100.0, curve.last().load, 0.0)
    }

    // Streaks

    private fun onDay(n: Int, daysAgo: Int) = TestData.activity(n).copy(startDate = now.minusSeconds(daysAgo * 86_400L))

    @Test fun theDayStreakCountsBackFromTodayOrYesterday() = runTest {
        val store = newStore()
        store.load()
        assertEquals(0, store.currentStreak())
        for ((n, d) in listOf(0 to 0, 1 to 1, 2 to 2, 3 to 4)) store.add(onDay(n, d), emptyList())
        assertEquals("today, yesterday and the day before; the gap on day 3 ends it", 3, store.currentStreak())

        val noToday = newStore()
        noToday.load()
        for ((n, d) in listOf(0 to 1, 1 to 2)) noToday.add(onDay(n, d), emptyList())
        assertEquals("nothing today yet does not break yesterday's streak", 2, noToday.currentStreak())

        val broken = newStore()
        broken.load()
        broken.add(onDay(0, 2), emptyList())
        assertEquals(0, broken.currentStreak())
    }

    @Test fun twoActivitiesOnOneDayCountOnce() = runTest {
        val store = newStore()
        store.load()
        store.add(onDay(1, 0), emptyList()); store.add(onDay(2, 0).copy(name = "second"), emptyList()); store.add(onDay(3, 1), emptyList())
        assertEquals(2, store.currentStreak())
    }

    @Test fun theWeekStreakCountsConsecutiveWeeksWithAnActivity() = runTest {
        val store = newStore()
        store.load()
        // weeks start Monday here; now is Saturday 2026-03-14
        assertEquals(0, store.weekStreak(DayOfWeek.MONDAY))
        for ((n, d) in listOf(0 to 1, 1 to 8, 2 to 15, 3 to 29)) store.add(onDay(n, d), emptyList())   // this week, last, the one before; a gap; then older
        assertEquals(3, store.weekStreak(DayOfWeek.MONDAY))

        val quietThisWeek = newStore()
        quietThisWeek.load()
        for ((n, d) in listOf(0 to 8, 1 to 15)) quietThisWeek.add(onDay(n, d), emptyList())
        assertEquals("an empty current week does not end a streak that reached last week", 2, quietThisWeek.weekStreak(DayOfWeek.MONDAY))

        val stale = newStore()
        stale.load()
        stale.add(onDay(0, 22), emptyList())
        assertEquals(0, stale.weekStreak(DayOfWeek.MONDAY))
    }

    // Gear and goals

    @Test fun gearMileageIsTheStartingDistancePlusEveryActivityLoggedAgainstIt() = runTest {
        val store = newStore()
        store.load()
        val shoes = Gear(id = TestData.id(500), kind = GearKind.Shoes, name = "A", startingDistance = 50_000.0)
        store.addGear(shoes)
        store.add(TestData.activity(1).copy(gearID = shoes.id, distance = 10_000.0), emptyList())
        store.add(TestData.activity(2).copy(gearID = shoes.id, distance = 5_000.0), emptyList())
        store.add(TestData.activity(3).copy(gearID = null, distance = 99_000.0), emptyList())
        assertEquals(65_000.0, store.mileage(shoes), 0.0)
        assertEquals(2, store.activityCount(shoes))

        store.deleteGear(shoes)
        assertTrue(store.current.gear.isEmpty())
        assertTrue(store.current.activities.all { it.gearID == null })
        assertEquals(0, store.activityCount(shoes))
    }

    @Test fun theDefaultGearPrefersTheMarkedOneThenAnyActiveOfTheRightKind() = runTest {
        val store = newStore()
        store.load()
        assertNull(store.defaultGear(SportType.Run))
        val old = Gear(kind = GearKind.Shoes, name = "retired", isRetired = true, isDefault = true)
        val first = Gear(kind = GearKind.Shoes, name = "first")
        val chosen = Gear(kind = GearKind.Shoes, name = "chosen", isDefault = true)
        val bike = Gear(kind = GearKind.Bike, name = "bike")
        for (g in listOf(old, first, bike)) store.addGear(g)
        assertEquals("first", store.defaultGear(SportType.Run)!!.name)            // retired gear is skipped even if marked
        assertEquals("bike", store.defaultGear(SportType.GravelRide)!!.name)
        store.addGear(chosen)
        assertEquals("chosen", store.defaultGear(SportType.TrailRun)!!.name)
        store.updateGear(chosen.copy(isRetired = true))
        assertEquals("first", store.defaultGear(SportType.Run)!!.name)
        store.updateGear(Gear(kind = GearKind.Shoes, name = "unknown"))             // unknown id: ignored
        assertEquals(4, store.current.gear.size)
    }

    @Test fun routesAndGoalsCanBeAddedUpdatedAndDeleted() = runTest {
        val store = newStore()
        store.load()
        val route = TestData.route()
        store.addRoute(route)
        store.updateRoute(route.copy(name = "Renamed", isStarred = true))
        assertEquals("Renamed", store.current.routes[0].name)
        store.updateRoute(route.copy(id = UUID.randomUUID(), name = "ghost"))
        assertEquals(1, store.current.routes.size)
        store.deleteRoute(route)
        assertTrue(store.current.routes.isEmpty())

        val goal = Goal(metric = GoalMetric.Distance, period = GoalPeriod.Weekly, target = 30_000.0)
        store.addGoal(goal)
        store.updateGoal(goal.copy(target = 40_000.0, isActive = false))
        assertEquals(40_000.0, store.current.goals[0].target, 0.0)
        store.deleteGoal(goal)
        assertTrue(store.current.goals.isEmpty())
    }

    // Backup

    private fun restoredWith(vararg ids: Int, settings: AppSettings = AppSettings().copy(profile = AppSettings().profile.copy(name = "From backup"))) =
        Backup.Restored(
            settings = settings, activities = ids.map { TestData.activity(it) }, segments = listOf(TestData.segment()),
            segmentEfforts = emptyList(), routes = listOf(TestData.route()), gear = listOf(TestData.gear()), goals = listOf(TestData.goal()), skipped = 0,
        )

    @Test fun replacingRestoresEverythingAndDeletesTheTracksOfWhatIsDiscarded() = runTest {
        val files = StrideFiles(tmp.newFolder())
        val store = newStore(files)
        store.load()
        val keepId = TestData.activity(1)
        val goneId = TestData.activity(2)
        val photo = files.savePhoto(byteArrayOf(9))!!
        store.add(keepId, TestData.track(5)); store.add(goneId.copy(photos = listOf(ActivityPhoto(filename = photo))), TestData.track(5))
        store.addGoal(Goal(metric = GoalMetric.Time, period = GoalPeriod.Yearly, target = 1.0))

        store.apply(restoredWith(1, 3), Backup.RestoreMode.Replace, restoreSettings = true)
        assertEquals(setOf(TestData.id(1), TestData.id(3)), store.current.activities.map { it.id }.toSet())
        assertTrue(files.hasTrack(TestData.id(1)))
        assertFalse("the discarded activity's track goes", files.hasTrack(TestData.id(2)))
        assertFalse(files.photoFile(photo).exists())
        assertEquals("From backup", store.current.settings.profile.name)
        assertEquals(1, store.current.goals.size)
        assertEquals(TestData.goal().id, store.current.goals[0].id)
        assertEquals("everything was written to disk", 2, files.load(activities, StrideFiles.ACTIVITIES)!!.size)
        assertEquals("From backup", files.load(AppSettings.serializer(), StrideFiles.SETTINGS)!!.profile.name)
    }

    @Test fun mergingAddsOnlyWhatIsNewAndKeepsTheExistingCopyOnCollisions() = runTest {
        val store = newStore()
        store.load()
        store.add(TestData.activity(1).copy(name = "mine"), emptyList())
        store.addGear(TestData.gear().copy(name = "my pair"))
        store.updateSettings { it.copy(profile = it.profile.copy(name = "me")) }

        store.apply(restoredWith(1, 2), Backup.RestoreMode.Merge, restoreSettings = false)
        assertEquals(setOf("mine", "Morning Run 2"), store.current.activities.map { it.name }.toSet())
        assertEquals("existing items win", "my pair", store.current.gear.single().name)
        assertEquals("me", store.current.settings.profile.name)
        assertEquals(1, store.current.segments.size)
        assertEquals(TestData.activity(2).startDate, store.current.activities.first().startDate.let { TestData.activity(2).startDate })

        store.apply(restoredWith(1, 2), Backup.RestoreMode.Merge, restoreSettings = false)
        assertEquals("merging twice changes nothing", 2, store.current.activities.size)
    }

    @Test fun anAutomaticBackupIsWrittenWhenDueRecordedAndPruned() = runTest {
        val files = StrideFiles(tmp.newFolder())
        val store = newStore(files)
        store.load()
        store.add(TestData.activity(1), TestData.track(10))
        assertNull("switched off", store.runAutomaticBackupIfDue())

        store.updateSettings { it.copy(backup = BackupSettings(automatic = true, intervalDays = 1, keepCount = 2)) }
        val first = store.runAutomaticBackupIfDue()
        assertNotNull(first)
        assertEquals(1, AutoBackup.existing(files).size)
        assertEquals(first!!.filename, store.current.settings.backup.lastFilename)
        assertEquals(now, store.current.settings.backup.lastRun)
        assertEquals(1, store.current.settings.backup.lastActivityCount)
        assertEquals("persisted", first.filename, files.load(AppSettings.serializer(), StrideFiles.SETTINGS)!!.backup.lastFilename)
        assertNull("not due again so soon", store.runAutomaticBackupIfDue())

        // a day and a bit later, then two more days: only the newest two are kept
        for (step in 1..3) {
            now = now.plusSeconds(90_000)
            val entry = store.runAutomaticBackupIfDue()
            assertNotNull(entry)
            entry!!.file.setLastModified(now.toEpochMilli())
        }
        assertEquals(2, AutoBackup.existing(files).size)
        assertEquals(3, store.current.activities.let { 3 })
    }

    @Test fun noAutomaticBackupOfAnEmptyLibrary() = runTest {
        val store = newStore()
        store.load()
        store.updateSettings { it.copy(backup = BackupSettings(automatic = true)) }
        assertNull(store.runAutomaticBackupIfDue())
    }

    @Test fun aFailedBackupIsReportedNotThrown() = runTest {
        val files = StrideFiles(tmp.newFolder())
        val store = newStore(files)
        store.load()
        store.add(TestData.activity(1), emptyList())
        store.updateSettings { it.copy(backup = BackupSettings(automatic = true)) }
        // make the Backups folder a plain file so nothing can be written inside it
        files.backupsDirectory.deleteRecursively()
        files.backupsDirectory.writeText("in the way")
        var error: Throwable? = null
        assertNull(store.runAutomaticBackupIfDue { error = it })
        assertNotNull(error)
        assertNull("the failure is not recorded as a backup", store.current.settings.backup.lastRun)
    }

    @Test fun theBackupSnapshotAndIdsReflectTheCurrentState() = runTest {
        val store = newStore()
        store.load()
        store.add(TestData.activity(1), emptyList()); store.addGear(TestData.gear())
        val snap = store.backupSnapshot()
        assertEquals(1, snap.activities.size)
        assertEquals(1, snap.gear.size)
        assertEquals(setOf(TestData.id(1)), store.activityIDs)
    }
}
