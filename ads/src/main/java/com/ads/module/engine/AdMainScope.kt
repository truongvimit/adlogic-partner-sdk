package com.ads.module.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

// Main, never Default or IO: its delay() is a main-Handler post, the one clock tests can advance.
internal val adMainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// A delayed Handler post on adMainScope: the timer starts now; 0 ms still waits one loop turn.
internal fun launchAfter(delayMs: Long, block: () -> Unit): Job =
    adMainScope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (delayMs > 0) delay(delayMs) else yield()
        block()
    }
