package com.jashith.stride.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.jashith.stride.core.analysis.HeartRateSample
import com.jashith.stride.core.analysis.HeartRateBackfill
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Health Connect, the Android counterpart of HealthKit: finished workouts go out (with their route),
 * heart rate and body weight come in. Every call is safe on a phone without Health Connect and returns
 * "nothing" rather than throwing.
 */
class HealthConnectManager(private val context: Context) {

    enum class Availability { Available, NeedsInstall, NeedsUpdate, Unsupported }

    /** Permission names the app asks for. The read ones can be revoked without losing the write ones. */
    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
    )
    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )
    val allPermissions: Set<String> = writePermissions + readPermissions

    private val _granted = MutableStateFlow<Set<String>>(emptySet())
    val granted: StateFlow<Set<String>> get() = _granted

    /** The newest heart rate sample seen during a recording. */
    data class LiveHeartRate(val bpm: Double, val time: Instant, val fromWatch: Boolean)

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NeedsUpdate
        HealthConnectClient.SDK_UNAVAILABLE -> Availability.NeedsInstall
        else -> Availability.Unsupported
    }

    val isAvailable: Boolean get() = availability() == Availability.Available

    private val client: HealthConnectClient? by lazy {
        if (isAvailable) HealthConnectClient.getOrCreate(context) else null
    }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun refreshGranted(): Set<String> {
        val c = client ?: return emptySet<String>().also { _granted.value = it }
        val set = try {
            c.permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            emptySet()
        }
        _granted.value = set
        return set
    }

    val canWrite: Boolean get() = _granted.value.containsAll(writePermissions)
    val canReadHeartRate: Boolean get() = HealthPermission.getReadPermission(HeartRateRecord::class) in _granted.value
    val canReadWeight: Boolean get() = HealthPermission.getReadPermission(WeightRecord::class) in _granted.value

    // Reading

    suspend fun latestBodyMassKg(): Double? {
        val c = client ?: return null
        if (!canReadWeight) return null
        return try {
            val now = Instant.now()
            c.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(3L * 365 * 86400), now),
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records.firstOrNull()?.weight?.inKilograms
        } catch (_: Exception) {
            null
        }
    }

    /** Samples are returned oldest first and filtered to plausible values. */
    suspend fun heartRateSamples(from: Instant, to: Instant): List<HeartRateSample> {
        val c = client ?: return emptyList()
        if (!canReadHeartRate) return emptyList()
        return try {
            val out = ArrayList<HeartRateSample>()
            var token: String? = null
            do {
                val response = c.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        pageSize = 5000,
                        pageToken = token,
                    ),
                )
                for (record in response.records) {
                    val watch = record.metadata.device?.type == Device.TYPE_WATCH
                    for (sample in record.samples) {
                        val bpm = sample.beatsPerMinute.toDouble()
                        if (HeartRateBackfill.isPlausible(bpm)) out.add(HeartRateSample(sample.time, bpm, watch))
                    }
                }
                token = response.pageToken
            } while (token != null)
            out.sortedBy { it.time }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The newest reading from the last ten minutes. Health Connect has no live push, so a recording
     * polls this every few seconds; a watch that syncs in bursts simply shows up late.
     */
    suspend fun latestHeartRate(now: Instant = Instant.now()): LiveHeartRate? =
        heartRateSamples(now.minusSeconds(600), now.plusSeconds(60)).lastOrNull()
            ?.let { LiveHeartRate(it.bpm, it.time, it.fromWatch) }

    // Writing

    /** Writes the workout, its route, calories and distance. The activity id is the client record id, so saving twice replaces. */
    suspend fun save(activity: Activity, points: List<TrackPoint>): Boolean {
        val c = client ?: return false
        if (!canWrite) return false
        return try {
            val start = activity.startDate
            val end = start.plusMillis((maxOf(1.0, activity.elapsed) * 1000).toLong())
            val zone = ZoneId.systemDefault().rules.getOffset(start)
            val endZone = ZoneId.systemDefault().rules.getOffset(end)
            val device = Device(type = Device.TYPE_PHONE)

            val route = routeFor(points, start, end)
            val records = ArrayList<androidx.health.connect.client.records.Record>()
            records += ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = zone,
                endTime = end,
                endZoneOffset = endZone,
                exerciseType = exerciseType(activity.sport),
                title = activity.name,
                notes = activity.notes.ifBlank { null },
                metadata = Metadata.activelyRecorded(device, clientRecordId = "${activity.id}"),
                exerciseRoute = route,
            )
            if (activity.calories > 0) {
                records += ActiveCaloriesBurnedRecord(
                    startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = endZone,
                    energy = Energy.kilocalories(activity.calories),
                    metadata = Metadata.activelyRecorded(device, clientRecordId = "${activity.id}-energy"),
                )
            }
            if (activity.distance > 0) {
                records += DistanceRecord(
                    startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = endZone,
                    distance = Length.meters(activity.distance),
                    metadata = Metadata.activelyRecorded(device, clientRecordId = "${activity.id}-distance"),
                )
            }
            c.insertRecords(records)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun routeFor(points: List<TrackPoint>, start: Instant, end: Instant): ExerciseRoute? {
        val valid = points.filter { it.coord.isValid }
        if (valid.size < 2) return null
        // Health Connect caps a record at about a megabyte; a few thousand points is plenty of detail.
        val step = maxOf(1, valid.size / MAX_ROUTE_POINTS + if (valid.size % MAX_ROUTE_POINTS == 0) 0 else 1)
        val locations = valid.filterIndexed { i, _ -> i % step == 0 || i == valid.lastIndex }.mapNotNull { p ->
            val whole = Math.floor(p.t).toLong()
            val time = start.plusSeconds(whole).plusNanos(((p.t - whole) * 1e9).toLong())
            if (time.isBefore(start) || time.isAfter(end)) return@mapNotNull null
            ExerciseRoute.Location(
                time = time,
                latitude = p.lat,
                longitude = p.lon,
                horizontalAccuracy = Length.meters(maxOf(1.0, p.acc)),
                altitude = Length.meters(p.alt),
            )
        }
        return if (locations.size < 2) null else ExerciseRoute(locations)
    }

    companion object {
        private const val MAX_ROUTE_POINTS = 3000

        fun exerciseType(sport: SportType): Int = when (sport) {
            SportType.Run, SportType.TrailRun -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            SportType.Treadmill -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            SportType.Walk -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            SportType.Hike -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            SportType.IndoorRide -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
            SportType.MountainBike, SportType.GravelRide, SportType.EBike, SportType.Ride -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        }
    }
}
