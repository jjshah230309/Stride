package com.jashith.stride.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.jashith.stride.core.recording.Sensors

/**
 * The barometer and the step detector. The barometer gives far better elevation than GPS altitude;
 * the step detector gives running cadence without a watch. Readings are written straight into the
 * recording engine's [Sensors], on the main thread, which is also where the engine runs.
 */
class MotionEngine(private val context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressure: Sensor? = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val stepDetector: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val hasBarometer: Boolean get() = pressure != null

    private var sensors: Sensors? = null
    private var baseline: Double? = null
    private var smoothed = 0.0
    private val stepTimes = ArrayDeque<Long>()
    private var running = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_PRESSURE -> onPressure(event.values[0])
                Sensor.TYPE_STEP_DETECTOR -> onStep(event.timestamp)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(target: Sensors, trackSteps: Boolean) {
        sensors = target
        target.hasBarometer = pressure != null
        if (!running) {
            running = true
            pressure?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        if (trackSteps && stepDetector != null && canCountSteps()) {
            manager.registerListener(listener, stepDetector, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun reset() {
        baseline = null
        smoothed = 0.0
        stepTimes.clear()
        sensors?.relativeAltitude = 0.0
        sensors?.cadence = 0.0
    }

    fun stop() {
        manager.unregisterListener(listener)
        running = false
        stepTimes.clear()
        sensors?.cadence = 0.0
        sensors = null
    }

    /** Called once a second: lets cadence fall to zero when the steps stop arriving. */
    fun tick() {
        val s = sensors ?: return
        s.cadence = cadence(SystemClock.elapsedRealtimeNanos())
    }

    private fun canCountSteps(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun onPressure(hectopascal: Float) {
        val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hectopascal).toDouble()
        val base = baseline ?: altitude.also { baseline = it; smoothed = it }
        // The barometer is noisy at the decimetre level; a light low-pass keeps it from jittering the gain.
        smoothed += 0.2 * (altitude - smoothed)
        sensors?.relativeAltitude = smoothed - base
    }

    private fun onStep(timestampNanos: Long) {
        stepTimes.addLast(timestampNanos)
        trim(timestampNanos)
        sensors?.cadence = cadence(timestampNanos)
    }

    private fun trim(nowNanos: Long) {
        while (stepTimes.isNotEmpty() && nowNanos - stepTimes.first() > WINDOW_NANOS) stepTimes.removeFirst()
    }

    private fun cadence(nowNanos: Long): Double {
        trim(nowNanos)
        if (stepTimes.size < MIN_STEPS) return 0.0
        // No step for three seconds means the athlete has stopped.
        if (nowNanos - stepTimes.last() > STALE_NANOS) return 0.0
        val span = (stepTimes.last() - stepTimes.first()) / 1e9
        if (span <= 0.0) return 0.0
        return (stepTimes.size - 1) / span * 60.0
    }

    private companion object {
        const val WINDOW_NANOS = 8_000_000_000L
        const val STALE_NANOS = 3_000_000_000L
        const val MIN_STEPS = 5
    }
}
