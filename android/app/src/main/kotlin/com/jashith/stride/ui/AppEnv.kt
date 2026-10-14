package com.jashith.stride.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.jashith.stride.AppGraph
import com.jashith.stride.core.model.UnitSystem
import com.jashith.stride.core.store.StoreState

val LocalGraph = compositionLocalOf<AppGraph> { error("AppGraph not provided") }
val LocalNavigator = compositionLocalOf<Navigator> { error("Navigator not provided") }

/** The store's whole state; recomposes the caller whenever anything in it changes. */
@Composable
fun rememberStoreState(): StoreState = LocalGraph.current.store.state.collectAsState().value

@Composable
fun units(): UnitSystem = rememberStoreState().settings.units

@Composable
fun <T> rememberStoreState(select: (StoreState) -> T): T {
    val state = rememberStoreState()
    return remember(state) { select(state) }
}
