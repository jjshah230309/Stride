package com.jashith.stride

import android.app.Application
import android.util.Log
import com.jashith.stride.core.store.StrideFiles
import com.jashith.stride.core.store.StrideStore
import com.jashith.stride.health.HealthConnectManager
import com.jashith.stride.sensors.HeartRateMonitor
import com.jashith.stride.sensors.LocationEngine
import com.jashith.stride.sensors.MotionEngine
import com.jashith.stride.service.RecordingController
import com.jashith.stride.voice.VoiceCoach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Everything with process lifetime, built once. There is no DI framework: this is the whole graph. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val files = StrideFiles(app.filesDir, log = { Log.w("Stride", it) })
    val store = StrideStore(files, scope)
    val health = HealthConnectManager(app)
    val voice = VoiceCoach(app)
    val strap = HeartRateMonitor(app)
    val location = LocationEngine(app)
    val motion = MotionEngine(app)
    val recorder = RecordingController(app, store, scope, location, motion, strap, health, voice)
}
