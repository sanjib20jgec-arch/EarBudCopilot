package com.copilot.earbud.service

import com.copilot.earbud.domain.model.EarphoneConnectionState
import com.copilot.earbud.domain.model.LogEntry
import com.copilot.earbud.domain.model.PipelineStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton reactive event/state bus bridging [AmbientListeningService] and [CopilotViewModel].
 */
object CopilotStateBus {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _pipelineStage = MutableStateFlow(PipelineStage.IDLE)
    val pipelineStage: StateFlow<PipelineStage> = _pipelineStage.asStateFlow()

    private val _currentRms = MutableStateFlow(0f)
    val currentRms: StateFlow<Float> = _currentRms.asStateFlow()

    private val _earphoneState = MutableStateFlow(EarphoneConnectionState())
    val earphoneState: StateFlow<EarphoneConnectionState> = _earphoneState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (!running) {
            _pipelineStage.value = PipelineStage.IDLE
            _currentRms.value = 0f
        }
    }

    fun setPipelineStage(stage: PipelineStage) {
        _pipelineStage.value = stage
    }

    fun updateRms(rms: Float) {
        _currentRms.value = rms
    }

    fun updateEarphoneState(state: EarphoneConnectionState) {
        _earphoneState.value = state
    }

    fun log(tag: String, message: String, isHighlight: Boolean = false, isError: Boolean = false) {
        val entry = LogEntry(
            timestamp = synchronized(timeFormat) { timeFormat.format(Date()) },
            tag = tag,
            message = message,
            isHighlight = isHighlight,
            isError = isError
        )
        _logs.update { current ->
            (listOf(entry) + current).take(150)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
