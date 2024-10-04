package com.dracula.core.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Composable
fun <T> ObserveAsEvents(
	flow: Flow<T>,
	key1: Any? = null,
	key2: Any? = null,
	onEvent: (T) -> Unit,
) {
	val lifecycleOwner = LocalLifecycleOwner.current
	LaunchedEffect(flow, lifecycleOwner, key1, key2) {
		lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
			withContext(Dispatchers.Main.immediate) {
				flow.collect(onEvent)
			}
		}
	}

}

@Composable
fun <T> Flow<T>.ObserverAsEvents(
	key1: Any? = null,
	key2: Any? = null,
	onEvent: (T) -> Unit,
) {
	ObserveAsEvents(
		flow = this,
		key1 = key1,
		key2 = key2,
		onEvent = onEvent
	)
}