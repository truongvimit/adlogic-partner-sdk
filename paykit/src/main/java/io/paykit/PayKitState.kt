package io.paykit

/** Lifecycle of the paywall configuration, exposed by [PayKit.state]. */
sealed interface PayKitState {

    data object Idle : PayKitState

    data object Syncing : PayKitState

    data class Ready(val configVersion: Int, val packageCount: Int) : PayKitState

    data class Error(val message: String) : PayKitState
}
