package com.jashith.stride.core.golden

import com.jashith.stride.core.analysis.ActivityAnalyzer
import com.jashith.stride.core.analysis.SegmentMatcher
import com.jashith.stride.core.io.Backup
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.model.GearKind
import com.jashith.stride.core.model.GoalMetric
import com.jashith.stride.core.model.GoalPeriod
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.Goal
import com.jashith.stride.core.model.SavedRoute
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SegmentEffort
import com.jashith.stride.core.model.SpokenMetric
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.speech.SpeechStyle
import com.jashith.stride.core.store.RecoverySnapshot
import com.jashith.stride.core.store.StrideFiles
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * The folder and backup file below were written by the iOS app's own persistence code. If the Kotlin
 * side reads them, an iPhone's data can move to Android; the reverse is checked by writing a folder
 * for Tools/swift-reference to decode (see [writesAFolderTheIosModelsCanRead]).
 */
class GoldenDocumentsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val documents = Golden.read("ios-documents.json", DocumentsGolden.serializer())

    /** Copies the iOS folder out of the classpath so StrideFiles can open it. */
    private fun iosFolder(): StrideFiles {
        val root = tmp.newFolder("ios")
        for (name in listOf("activities.json", "segments.json", "segment-efforts.json", "routes.json", "gear.json", "goals.json", "settings.json", "recording-in-progress.json")) {
            File(root, name).writeBytes(Golden.bytes("ios-documents/$name"))
        }
        File(root, "Tracks").mkdirs()
        val trackName = documents.activityIDWithTrack.toString().uppercase() + ".json"
        File(root, "Tracks/$trackName").writeBytes(Golden.bytes("ios-documents/Tracks/$trackName"))
        return StrideFiles(root)
    }

    @Test fun everyFileTheIosAppSavesLoads() {
        val files = iosFolder()
        val activities = files.load(ListSerializer(Activity.serializer()), StrideFiles.ACTIVITIES)!!
        assertEquals(4, activities.size)
        assertEquals(documents.activities.map { it.id }, activities.map { it.id })
        assertEquals(9, files.load(ListSerializer(Segment.serializer()), StrideFiles.SEGMENTS)!!.size)
        assertEquals(6, files.load(ListSerializer(SegmentEffort.serializer()), StrideFiles.SEGMENT_EFFORTS)!!.size)
        assertEquals(1, files.load(ListSerializer(SavedRoute.serializer()), StrideFiles.ROUTES)!!.size)
        assertEquals(1, files.load(ListSerializer(Gear.serializer()), StrideFiles.GEAR)!!.size)
        assertEquals(1, files.load(ListSerializer(Goal.serializer()), StrideFiles.GOALS)!!.size)
        assertTrue("nothing should have been moved aside as damaged", files.damagedFiles.isEmpty())

        val track = files.loadTrack(documents.activityIDWithTrack)
        assertEquals(240, track.size)

        val recovery: RecoverySnapshot = files.readRecovery()!!
        assertEquals(40, recovery.points.size)
        assertEquals(SportType.Run, recovery.sport)
        assertEquals(1, recovery.laps.size)
        assertEquals(3.0, recovery.pausedDuration, 0.0)
    }

    @Test fun theIosSettingsFileKeepsEveryChosenValue() {
        val s = StrideFilesOf(iosFolder()).settings()
        assertEquals(documents.settings, s)
        assertEquals(UnitSystem.Imperial, s.display.units)
        assertEquals("hybrid", s.display.mapStyle)
        assertEquals(SportType.Ride, s.display.defaultSport)
        assertEquals(SpeechStyle.Detailed, s.voice.style)
        assertEquals(listOf(SpokenMetric.HeartRate, SpokenMetric.SplitNumber, SpokenMetric.GradeAdjustedPace, SpokenMetric.Calories), s.voice.runMetrics)
        assertEquals(0.42, s.voice.rate, 0.0)
        assertEquals("com.apple.voice.premium.en-GB.Malcolm", s.voice.voiceIdentifier)
        assertEquals(14, s.backup.intervalDays)
        assertEquals(Instant.parse("2026-03-01T09:00:00Z"), s.backup.lastRun)
        assertEquals(240.0 + 10.0, s.profile.powerZones.ftp, 0.0)
        assertEquals(186.0, s.profile.hrZones.maxHR, 0.0)
    }

    @Test fun anIosBackupRestoresEverythingWithNothingSkipped() {
        val files = StrideFiles(tmp.newFolder("restored"))
        val bytes = Golden.bytes("ios.stridebackup")
        val summary = Backup.inspect(ByteArrayInputStream(bytes))
        assertEquals(4, summary.activities)
        assertEquals(9, summary.segments)

        val restored = Backup.read(ByteArrayInputStream(bytes), files, emptySet(), Backup.RestoreMode.Replace)
        assertEquals(0, restored.skipped)
        assertEquals(4, restored.activities.size)
        assertEquals(9, restored.segments.size)
        assertEquals(6, restored.segmentEfforts.size)
        assertEquals(1, restored.routes.size)
        assertEquals(1, restored.gear.size)
        assertEquals(1, restored.goals.size)
        assertEquals(documents.settings, restored.settings)
        assertEquals(documents.activities.map { it.id }, restored.activities.map { it.id })
        for (a in restored.activities) assertTrue("track for ${a.name}", files.hasTrack(a.id))
        assertEquals(240, files.loadTrack(documents.activityIDWithTrack).size)
        assertEquals(listOf(true, true, true, true), restored.activities.map { files.loadTrack(it.id).isNotEmpty() })
    }

    @Test fun mergingAnIosBackupTwiceAddsNothingNew() {
        val files = StrideFiles(tmp.newFolder("merge"))
        val bytes = Golden.bytes("ios.stridebackup")
        val ids = documents.activities.map { it.id }.toSet()
        val second = Backup.read(ByteArrayInputStream(bytes), files, ids, Backup.RestoreMode.Merge)
        assertTrue(second.activities.isEmpty())
        assertFalse(files.hasTrack(documents.activityIDWithTrack))
    }

    @Test fun backupsWrittenHereReadBackIdenticallyToTheIosOne() {
        val source = StrideFiles(tmp.newFolder("src"))
        val original = Backup.read(ByteArrayInputStream(Golden.bytes("ios.stridebackup")), source, emptySet(), Backup.RestoreMode.Replace)
        val out = ByteArrayOutputStream()
        Backup.write(
            Backup.Snapshot(original.settings, original.activities, original.segments, original.segmentEfforts, original.routes, original.gear, original.goals),
            source, out,
        )
        val again = Backup.read(ByteArrayInputStream(out.toByteArray()), StrideFiles(tmp.newFolder("again")), emptySet(), Backup.RestoreMode.Replace)
        assertEquals(original.activities, again.activities)
        assertEquals(original.segments, again.segments)
        assertEquals(original.segmentEfforts, again.segmentEfforts)
        assertEquals(original.routes, again.routes)
        assertEquals(original.gear, again.gear)
        assertEquals(original.goals, again.goals)
        assertEquals(original.settings, again.settings)
        assertEquals(0, again.skipped)
    }

    /**
     * Writes a complete data folder and a backup into core/build/interop. Run
     * `Tools/swift-reference/run.sh verify android/core/build/interop` to decode them with the iOS app's
     * own models, which is the strictest check that an iPhone could open what Android wrote.
     */
    @Test fun writesAFolderTheIosModelsCanRead() {
        val out = File("build/interop")
        out.deleteRecursively()
        val files = StrideFiles(out)
        val source = StrideFiles(tmp.newFolder("interop-src"))
        val restored = Backup.read(ByteArrayInputStream(Golden.bytes("ios.stridebackup")), source, emptySet(), Backup.RestoreMode.Replace)

        // Things only Kotlin created: random UUIDs, "now" with nanoseconds, analysis run here.
        val analysisCases = Golden.readList("analysis.json", AnalysisCase.serializer())
        val nativeActivities = analysisCases.map { c ->
            val profile = c.profile.copy(birthYear = java.time.Year.now().value - c.ageYears)
            ActivityAnalyzer.analyse(
                Activity(name = "Native ${c.key} \u00e9 \"quoted\"", sport = c.base.sport, startDate = Instant.now(), notes = "caf\u00e9 \u2615", perceivedExertion = 6),
                c.track.take(400), profile,
            )
        }
        val nativeSegment = Segment.make(
            "Native segment", SportType.Run,
            analysisCases[0].track.subList(100, 300).map { it.coord }, analysisCases[0].track.subList(100, 300).map { it.alt },
        )
        val nativeRoute = SavedRoute(
            name = "Native route", sport = SportType.Ride, path = listOf(Coord(51.5, -0.12), Coord(51.51, -0.11)),
            elevations = listOf(10.0, 15.5), distance = 1400.0, elevationGain = 5.5,
        )
        val nativeGear = Gear(kind = GearKind.Bike, name = "Native bike", weightKg = 9.5, retirementDistance = 20_000_000.0)
        val nativeGoal = Goal(metric = GoalMetric.Elevation, period = GoalPeriod.Monthly, sports = listOf(SportType.Ride, SportType.GravelRide), target = 5000.0)
        val nativeSettings = restored.settings.copy(
            display = restored.settings.display.copy(units = UnitSystem.Metric, mapStyle = "standard", countdownSeconds = 10),
            voice = restored.settings.voice.copy(style = SpeechStyle.Brief, rate = 0.5, voiceIdentifier = "en-gb-x-gbd-local"),
            backup = restored.settings.backup.copy(lastRun = Instant.now()),
        )
        val nativeEfforts = SegmentMatcher.rank(SegmentMatcher.findEfforts(nativeActivities[0], nativeActivities[0].id, analysisCases[0].track.take(400), listOf(nativeSegment)))

        val activities = restored.activities + nativeActivities
        val segments = restored.segments + nativeSegment
        val efforts = restored.segmentEfforts + nativeEfforts
        val routes = restored.routes + nativeRoute
        val gear = restored.gear + nativeGear
        val goals = restored.goals + nativeGoal
        files.save(ListSerializer(Activity.serializer()), activities, StrideFiles.ACTIVITIES)
        files.save(ListSerializer(Segment.serializer()), segments, StrideFiles.SEGMENTS)
        files.save(ListSerializer(SegmentEffort.serializer()), efforts, StrideFiles.SEGMENT_EFFORTS)
        files.save(ListSerializer(SavedRoute.serializer()), routes, StrideFiles.ROUTES)
        files.save(ListSerializer(Gear.serializer()), gear, StrideFiles.GEAR)
        files.save(ListSerializer(Goal.serializer()), goals, StrideFiles.GOALS)
        files.save(AppSettings.serializer(), nativeSettings, StrideFiles.SETTINGS)
        for (a in restored.activities) files.saveTrack(source.loadTrack(a.id), a.id)
        nativeActivities.forEachIndexed { i, a -> files.saveTrack(analysisCases[i].track.take(400), a.id) }
        files.writeRecovery(iosFolder().readRecovery()!!)
        Backup.writeToFile(
            Backup.Snapshot(nativeSettings, activities, segments, efforts, routes, gear, goals),
            files, File(out, "android.stridebackup"),
        )
        assertTrue(nativeEfforts.isNotEmpty())
        assertTrue(File(out, "android.stridebackup").length() > 1000)
        assertNotNull(files.loadTrack(restored.activities[0].id))
    }
}

private class StrideFilesOf(private val files: StrideFiles) {
    fun settings(): AppSettings = files.load(AppSettings.serializer(), StrideFiles.SETTINGS)!!
}
