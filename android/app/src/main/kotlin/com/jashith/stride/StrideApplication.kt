package com.jashith.stride

import android.app.Application
import kotlinx.coroutines.launch

class StrideApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            graph.store.load()
            graph.recorder.checkForRecovery()
        }
    }
}
