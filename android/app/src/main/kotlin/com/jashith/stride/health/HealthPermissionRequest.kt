package com.jashith.stride.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** Returns a function that opens Health Connect's permission screen; [onDone] runs afterwards with the refreshed grants. */
@Composable
fun rememberHealthPermissionRequest(health: HealthConnectManager, onDone: () -> Unit = {}): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(health.permissionContract()) {
        scope.launch {
            health.refreshGranted()
            onDone()
        }
    }
    return remember(launcher) { { launcher.launch(health.allPermissions) } }
}
