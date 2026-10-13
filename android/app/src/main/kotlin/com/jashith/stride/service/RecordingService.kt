package com.jashith.stride.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
