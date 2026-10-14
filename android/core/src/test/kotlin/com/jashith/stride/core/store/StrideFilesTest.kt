package com.jashith.stride.core.store

import com.jashith.stride.core.TestData
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.Lap
import com.jashith.stride.core.model.SportType
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class StrideFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files(now: Instant = Instant.parse("2026-03-14T10:00:00Z")) =
        StrideFiles(tmp.newFolder(), now = { now })

    private val activities = ListSerializer(Activity.serializer())

    @Test fun saveAndLoadRoundTripWithNoTempFileLeftBehind() {
        val f = files()
        val list = listOf(TestData.activity(1), TestData.activity(2))
        assertTrue(f.save(activities, list, StrideFiles.ACTIVITIES))
        assertEquals(list, f.load(activities, StrideFiles.ACTIVITIES))
        assertFalse(f.file("activities.json.tmp").exists())
    }

    @Test fun savingOverAnExistingFileReplacesIt() {
        val f = files()
        f.save(activities, listOf(TestData.activity(1)), StrideFiles.ACTIVITIES)
        f.save(activities, listOf(TestData.activity(2), TestData.activity(3)), StrideFiles.ACTIVITIES)
        assertEquals(2, f.load(activities, StrideFiles.ACTIVITIES)!!.size)
    }

    @Test fun aMissingFileLoadsAsNullWithoutQuarantine() {
        val f = files()
        assertNull(f.load(activities, StrideFiles.ACTIVITIES))
        assertTrue(f.damagedFiles.isEmpty())
    }

    @Test fun anUnreadableFileIsMovedAsideNotOverwritten() {
        val f = files()
        f.prepare()
        f.file("activities.json").writeText("{ this is not json")
        assertNull(f.load(activities, StrideFiles.ACTIVITIES))
        assertEquals(listOf("activities.json"), f.damagedFiles)
        assertFalse(f.file("activities.json").exists())
        val aside = f.file("activities.json.damaged-${Instant.parse("2026-03-14T10:00:00Z").epochSecond}")
        assertTrue(aside.exists())
        assertEquals("{ this is not json", aside.readText())
        // a fresh save now succeeds without touching the saved-aside bytes
        f.save(activities, listOf(TestData.activity(1)), StrideFiles.ACTIVITIES)
        assertEquals("{ this is not json", aside.readText())
    }

    @Test fun tracksUseUpperCaseUuidFileNames() {
        val f = files()
        val id = TestData.id(7)
        assertTrue(f.saveTrack(TestData.track(10), id))
        assertEquals("00000000-0000-0000-0000-000000000007.json", f.trackFile(id).name)
        assertEquals(TestData.track(10), f.loadTrack(id))
        assertTrue(f.hasTrack(id))
        f.deleteTrack(id)
        assertFalse(f.hasTrack(id))
        assertEquals(emptyList<Any>(), f.loadTrack(id))
    }

    @Test fun anUnreadableTrackLoadsEmptyAndIsLeftInPlace() {
        val f = files()
        val id = TestData.id(8)
        f.prepare()
        f.trackFile(id).writeText("garbage")
        assertEquals(emptyList<Any>(), f.loadTrack(id))
        assertTrue(f.trackFile(id).exists())
        assertTrue(f.damagedFiles.isEmpty())
    }

    @Test fun photosAreSavedUnderAUuidJpgName() {
        val f = files()
        val name = f.savePhoto(byteArrayOf(1, 2, 3))
        assertNotNull(name)
        assertTrue(name!!.endsWith(".jpg"))
        assertEquals(name.uppercase().removeSuffix(".JPG") + ".jpg", name)
        assertEquals(3, f.photoFile(name).length())
        f.deletePhoto(name)
        assertFalse(f.photoFile(name).exists())
    }

    @Test fun recoverySnapshotRoundTrips() {
        val f = files()
        val snap = RecoverySnapshot(
            activityID = TestData.id(9), sport = SportType.Ride, startDate = TestData.started,
            points = TestData.track(30), elapsed = 30.0, pausedDuration = 1.0,
            laps = listOf(Lap(index = 1, startTime = 0.0, endTime = 10.0, distance = 30.0, moving = 10.0, elevGain = 0.0)),
            savedAt = Instant.parse("2026-03-14T07:31:00Z"),
        )
        assertTrue(f.writeRecovery(snap))
        assertEquals(snap.copy(laps = snap.laps), f.readRecovery()!!.copy(laps = snap.laps))
        assertEquals(snap.points, f.readRecovery()!!.points)
        f.clearRecovery()
        assertNull(f.readRecovery())
    }

    @Test fun recoveryFileUsesTheIosNamesForItsKeys() {
        val f = files()
        f.writeRecovery(
            RecoverySnapshot(TestData.id(9), SportType.Run, TestData.started, emptyList(), 0.0, 0.0, emptyList(), TestData.started),
        )
        val text = f.file("recording-in-progress.json").readText()
        for (key in listOf("activityID", "sport", "startDate", "points", "elapsed", "pausedDuration", "laps", "savedAt")) {
            assertTrue("missing $key in $text", text.contains("\"$key\""))
        }
    }
}
