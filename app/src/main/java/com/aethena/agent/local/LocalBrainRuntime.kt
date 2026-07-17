package com.aethena.agent.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalBrainState(
    val phase: String = "Not installed",
    val detail: String = "Install the verified local uncensored brain.",
    val progressPercent: Int = 0,
    val online: Boolean = false,
    val modelVerified: Boolean = false
)

object LocalBrainRuntime {
    private val mutableState = MutableStateFlow(LocalBrainState())
    val state: StateFlow<LocalBrainState> = mutableState.asStateFlow()

    @Volatile
    var serverProcess: Process? = null

    fun update(
        phase: String,
        detail: String,
        progressPercent: Int = mutableState.value.progressPercent,
        online: Boolean = false,
        modelVerified: Boolean = mutableState.value.modelVerified
    ) {
        mutableState.value = LocalBrainState(
            phase = phase,
            detail = detail,
            progressPercent = progressPercent.coerceIn(0, 100),
            online = online,
            modelVerified = modelVerified
        )
    }
}
