package com.ads.module.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Main, never Default or IO: its delay() is a main-Handler post, the only clock the tests can advance.
internal val adMainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
