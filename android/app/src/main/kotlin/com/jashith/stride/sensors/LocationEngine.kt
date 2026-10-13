package com.jashith.stride.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.jashith.stride.core.model.Coord
import com.jashith.stride.core.recording.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * GPS through the platform [LocationManager], so it works on phones without Google Play Services.
 * Fix filtering is the same as iOS: no fix, stale cached fixes and the first couple of wildly
 * inaccurate readings a cold GPS produces are dropped.
 */
class LocationEngine(private val context: Context) {

    data class Status(
        val running: Boolean = false,
        /** Horizontal accuracy of the latest fix in metres, -1 when there is none. */
        val accuracy: Double = -1.0,
        /** 0 (none) to 3 (strong), for the GPS pips in the UI. */
        val signal: Int = 0,
        val latest: Coord? = null,
        val permitted: Boolean = false,
    )

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _status = MutableStateFlow(Status(permitted = hasPermission()))
    val status: StateFlow<Status> get() = _status

    /** Every accepted fix is handed to this closure, on the main thread. */
    var onFix: ((GpsFix) -> Unit)? = null

    private var warmupFixes = 0
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location)

        override fun onLocationChanged(locations: MutableList<Location>) {
            locations.lastOrNull()?.let(::handle)
        }

        @Deprecated("Required below API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            _status.update { it.copy(accuracy = -1.0, signal = 0) }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun refreshPermission() {
        _status.update { it.copy(permitted = hasPermission()) }
    }

    val isGpsEnabled: Boolean
        get() = try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }

    /** Starts receiving fixes. Used both for the pre-start warm-up on the Record screen and for recording. */
    @SuppressLint("MissingPermission")
    fun start() {
        refreshPermission()
        if (!hasPermission() || listening) return
        try {
            warmupFixes = 0
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            listening = true
            _status.update { it.copy(running = true) }
        } catch (_: Exception) {
            // No GPS provider on this device, or the permission was revoked a moment ago.
            _status.update { it.copy(running = false) }
        }
    }

    fun stop() {
        if (listening) {
            try {
                manager.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
        listening = false
        _status.update { it.copy(running = false, signal = 0, accuracy = -1.0) }
    }

    private fun handle(location: Location) {
        val horizontal = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0
        _status.update {
            it.copy(
                latest = Coord(location.latitude, location.longitude),
                accuracy = horizontal,
                signal = strength(horizontal),
            )
        }

        // Reject rubbish: no fix, stale cached fixes, and a cold GPS's first wildly inaccurate readings.
        if (!(horizontal > 0 && horizontal < 100)) return
        val ageSeconds = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1e9
        if (kotlin.math.abs(ageSeconds) >= 5) return
        if (warmupFixes < 2 && horizontal > 30) {
            warmupFixes += 1
            return
        }
        warmupFixes = 5
        onFix?.invoke(toFix(location, horizontal))
    }

    private fun toFix(location: Location, horizontal: Double): GpsFix {
        // Before Android 14 the platform reports height above the ellipsoid, which can be tens of
        // metres off sea level; from 14 the MSL altitude is available and is the one that matches iOS.
        val altitude = when {
            Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude() -> location.mslAltitudeMeters
            location.hasAltitude() -> location.altitude
            else -> 0.0
        }
        val vertical = if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
            location.verticalAccuracyMeters.toDouble()
        } else {
            -1.0
        }
        return GpsFix(
            lat = location.latitude,
            lon = location.longitude,
            altitude = altitude,
            horizontalAccuracy = horizontal,
            verticalAccuracy = vertical,
            speed = if (location.hasSpeed()) location.speed.toDouble() else -1.0,
            timestamp = Instant.ofEpochMilli(location.time),
        )
    }

    companion object {
        fun strength(accuracy: Double): Int = when {
            accuracy < 0 -> 0
            accuracy < 8 -> 3
            accuracy < 20 -> 2
            accuracy < 45 -> 1
            else -> 0
        }
    }
}
