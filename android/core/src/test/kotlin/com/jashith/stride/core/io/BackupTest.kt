package com.jashith.stride.core.io

import com.jashith.stride.core.TestData
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.store.StrideFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files() = StrideFiles(tmp.newFolder())

    private fun snapshot(f: StrideFiles, count: Int = 3, withTracks: Boolean = true): Backup.Snapshot {
        val activities = (1..count).map { TestData.activity(it) }
        if (withTracks) activities.forEach { f.saveTrack(TestData.track(40 + it.hashCode() % 3), it.id) }
        return Backup.Snapshot(
            settings = AppSettings(),
            activities = activities,
            segments = listOf(TestData.segment()),
            segmentEfforts = listOf(TestData.effort()),
            routes = listOf(TestData.route()),
            gear = listOf(TestData.gear()),
            goals = listOf(TestData.goal()),
        )
    }

    private fun bytes(f: StrideFiles, s: Backup.Snapshot, now: Instant = TestData.started): ByteArray {
        val out = ByteArrayOutputStream()
        Backup.write(s, f, out, now)
        return out.toByteArray()
    }

    @Test fun layoutIsNdjsonWithAHeaderThenOneLinePerActivity() {
        val f = files()
        val data = bytes(f, snapshot(f, count = 3)).toString(Charsets.UTF_8)
        assertTrue("every line, including the last, ends with a newline", data.endsWith("\n"))
        val lines = data.removeSuffix("\n").split("\n")
        assertEquals(4, lines.size)

        val header = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals(setOf("manifest", "settings", "segments", "segmentEfforts", "routes", "gear", "goals"), header.keys)
        val manifest = header["manifest"]!!.jsonObject
        assertEquals("1", manifest["version"].toString())
        assertEquals("\"2026-03-14T07:30:05Z\"", manifest["createdAt"].toString())
        assertEquals("3", manifest["activityCount"].toString())
        assertEquals("3", manifest["trackCount"].toString())

        for (line in lines.drop(1)) {
            val entry = Json.parseToJsonElement(line).jsonObject
            assertEquals(setOf("activity", "track"), entry.keys)
        }
    }

    @Test fun trackCountCountsOnlyActivitiesThatHaveATrackFile() {
        val f = files()
        val s = snapshot(f, count = 2, withTracks = false)
        f.saveTrack(TestData.track(10), s.activities[0].id)
        val header = bytes(f, s).toString(Charsets.UTF_8).lineSequence().first()
        assertEquals("1", Json.parseToJsonElement(header).jsonObject["manifest"]!!.jsonObject["trackCount"].toString())
    }

    @Test fun roundTripRestoresEverythingAndWritesTracksToDisk() {
        val source = files()
        val s = snapshot(source)
        val data = bytes(source, s)

        val target = files()
        val restored = Backup.read(ByteArrayInputStream(data), target, emptySet(), Backup.RestoreMode.Replace)

        assertEquals(s.activities, restored.activities)
        assertEquals(s.segments, restored.segments)
        assertEquals(s.segmentEfforts, restored.segmentEfforts)
        assertEquals(s.routes, restored.routes)
        assertEquals(s.gear, restored.gear)
        assertEquals(s.goals, restored.goals)
        assertEquals(s.settings, restored.settings)
        assertEquals(0, restored.skipped)
        for (a in s.activities) assertEquals(source.loadTrack(a.id), target.loadTrack(a.id))
    }

    @Test fun mergeSkipsActivitiesThatAlreadyExistAndDoesNotTouchTheirTracks() {
        val source = files()
        val s = snapshot(source)
        val data = bytes(source, s)

        val target = files()
        target.saveTrack(TestData.track(5), s.activities[0].id) // pretend this one already lives here
        val existing = setOf(s.activities[0].id)
        val restored = Backup.read(ByteArrayInputStream(data), target, existing, Backup.RestoreMode.Merge)

        assertEquals(s.activities.drop(1), restored.activities)
        assertEquals("existing track must be left alone", 5, target.loadTrack(s.activities[0].id).size)
        assertTrue(target.hasTrack(s.activities[1].id))
    }

    @Test fun aTruncatedFileRestoresEverythingUpToTheBreak() {
        val source = files()
        val s = snapshot(source, count = 4)
        val text = bytes(source, s).toString(Charsets.UTF_8)
        val cut = text.substring(0, text.length - 400) // chop into the final activity line

        val restored = Backup.read(ByteArrayInputStream(cut.toByteArray()), files(), emptySet(), Backup.RestoreMode.Replace)
        assertEquals(3, restored.activities.size)
        assertEquals(1, restored.skipped)
    }

    @Test fun aBadEntryLineIsSkippedAndCounted() {
        val source = files()
        val lines = bytes(source, snapshot(source, count = 2)).toString(Charsets.UTF_8).removeSuffix("\n").split("\n").toMutableList()
        lines.add(2, "{\"activity\": 12}")
        lines.add(1, "")
        val restored = Backup.read(ByteArrayInputStream((lines.joinToString("\n") + "\n").toByteArray()), files(), emptySet(), Backup.RestoreMode.Replace)
        assertEquals(2, restored.activities.size)
        assertEquals(1, restored.skipped)
    }

    @Test fun windowsLineEndingsAreTolerated() {
        val source = files()
        val crlf = bytes(source, snapshot(source, count = 2)).toString(Charsets.UTF_8).replace("\n", "\r\n")
        val restored = Backup.read(ByteArrayInputStream(crlf.toByteArray()), files(), emptySet(), Backup.RestoreMode.Replace)
        assertEquals(2, restored.activities.size)
    }

    @Test fun inspectReportsTheContentsWithoutReadingEntries() {
        val source = files()
        val summary = Backup.inspect(ByteArrayInputStream(bytes(source, snapshot(source, count = 3))))
        assertEquals(3, summary.activities)
        assertEquals(1, summary.segments)
        assertEquals(1, summary.routes)
        assertEquals(1, summary.gear)
        assertEquals(1, summary.goals)
        assertEquals(TestData.started, summary.createdAt)
    }

    @Test fun anythingThatIsNotABackupIsRefused() {
        for (junk in listOf("", "hello", "{\"manifest\": 3}", "<gpx></gpx>", "[]")) {
            assertThrows(Backup.Failure.NotABackup::class.java) { Backup.inspect(ByteArrayInputStream(junk.toByteArray())) }
            assertThrows(Backup.Failure.NotABackup::class.java) {
                Backup.read(ByteArrayInputStream(junk.toByteArray()), files(), emptySet(), Backup.RestoreMode.Merge)
            }
        }
    }

    @Test fun filenameMatchesTheIosPattern() {
        val at = ZonedDateTime.of(2026, 3, 14, 7, 5, 0, 0, ZoneId.of("Europe/London"))
        assertEquals("Stride-2026-03-14-0705.stridebackup", Backup.suggestedFilename(at))
    }

    @Test fun writeToFileLeavesNoTempFileAndReplacesAnExistingBackup() {
        val f = files()
        val dest = java.io.File(f.backupsDirectory, "Stride-test.stridebackup")
        Backup.writeToFile(snapshot(f, 2), f, dest, TestData.started)
        val first = dest.length()
        Backup.writeToFile(snapshot(f, 3), f, dest, TestData.started)
        assertTrue(dest.length() > first)
        assertFalse(java.io.File(dest.path + ".tmp").exists())
    }

    @Test fun autoBackupKeepsOnlyTheNewestFew() {
        val f = files()
        val s = snapshot(f, 1)
        val times = (0 until 5).map { ZonedDateTime.of(2026, 3, 10 + it, 9, 0, 0, 0, ZoneId.of("UTC")) }
        for ((i, at) in times.withIndex()) {
            val entry = AutoBackup.run(s, f, keep = 3, now = at.toInstant(), zone = ZoneId.of("UTC"))
            entry.file.setLastModified(at.toInstant().toEpochMilli() + i) // make ordering unambiguous
        }
        val remaining = AutoBackup.existing(f).map { it.filename }
        assertEquals(
            listOf("Stride-2026-03-14-0900.stridebackup", "Stride-2026-03-13-0900.stridebackup", "Stride-2026-03-12-0900.stridebackup"),
            remaining,
        )
    }

    @Test fun pruneWithANonPositiveKeepDeletesNothing() {
        val f = files()
        val s = snapshot(f, 1)
        AutoBackup.run(s, f, keep = 0, now = Instant.parse("2026-03-10T09:00:00Z"), zone = ZoneId.of("UTC"))
        AutoBackup.run(s, f, keep = 0, now = Instant.parse("2026-03-11T09:00:00Z"), zone = ZoneId.of("UTC"))
        assertEquals(2, AutoBackup.existing(f).size)
    }

    @Test fun backupSettingsDueLogic() {
        val now = Instant.parse("2026-03-14T10:00:00Z")
        val off = com.jashith.stride.core.model.BackupSettings(automatic = false)
        assertFalse(off.isDue(now))
        val never = com.jashith.stride.core.model.BackupSettings(automatic = true)
        assertTrue(never.isDue(now))
        assertEquals(now, never.nextDue(now))
        val recent = never.copy(intervalDays = 7, lastRun = now.minusSeconds(6 * 86_400))
        assertFalse(recent.isDue(now))
        assertTrue(recent.copy(lastRun = now.minusSeconds(7 * 86_400)).isDue(now))
        assertEquals("Every 2 weeks", never.copy(intervalDays = 14).intervalName)
        assertEquals("Every 10 days", never.copy(intervalDays = 10).intervalName)
    }
}
