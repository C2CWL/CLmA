package com.clma.app.ui.pages.chat

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clma.app.diagnostics.FunctionalDiagnostics
import com.clma.app.ui.hooks.rememberSharedPreferenceBoolean
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ObserveDrawerDiagnostics(
    drawer: DrawerState,
    vm: ChatVM,
    assistantId: Uuid,
) {
    val functions by rememberSharedPreferenceBoolean(FunctionalDiagnostics.FUNCTION_KEY)
    val animation by rememberSharedPreferenceBoolean(FunctionalDiagnostics.ANIMATION_KEY)
    val lifecycleOwner = LocalLifecycleOwner.current
    val instance = remember(drawer) { System.identityHashCode(drawer) }
    DisposableEffect(drawer) {
        FunctionalDiagnostics.event("chat.attach", instance)
        FunctionalDiagnostics.animation(instance, "attached vm=${System.identityHashCode(vm)}")
        onDispose {
            FunctionalDiagnostics.event("chat.detach", instance)
            FunctionalDiagnostics.animation(instance, "detached")
        }
    }
    DisposableEffect(lifecycleOwner, functions) {
        val observer =
            LifecycleEventObserver { _, event ->
                FunctionalDiagnostics.event("chat.lifecycle.${event.name}", instance)
            }
        if (functions) lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(assistantId, functions) {
        FunctionalDiagnostics.event("chat.assistantObserved", instance)
    }
    LaunchedEffect(vm, functions) {
        if (functions) {
            vm.conversationJob.collect { job ->
                FunctionalDiagnostics.event("chat.busy", instance, if (job == null) 0 else 1)
            }
        }
    }
    LaunchedEffect(drawer, animation) {
        if (animation) {
            var previous = ""
            while (true) {
                // Poll instead of observing every animation frame; at most ten local writes/s.
                val state =
                    "current=${drawer.currentValue} target=${drawer.targetValue} " +
                        "running=${drawer.isAnimationRunning} offset=${drawer.currentOffset}"
                if (state != previous) {
                    FunctionalDiagnostics.animation(instance, state)
                    previous = state
                }
                delay(100)
            }
        }
    }
}
