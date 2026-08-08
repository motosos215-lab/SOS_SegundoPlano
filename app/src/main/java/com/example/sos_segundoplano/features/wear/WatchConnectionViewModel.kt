package com.example.sos_segundoplano.features.wear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.domain.auth.SessionState
import com.example.sos_segundoplano.domain.repository.AuthRepository
import com.example.sos_segundoplano.domain.wear.WatchConnectionRepository
import com.example.sos_segundoplano.domain.wear.WatchConnectionState
import com.example.sos_segundoplano.domain.wear.WatchDeclineReason
import com.example.sos_segundoplano.domain.wear.WatchHandshakeInfo
import com.example.sos_segundoplano.domain.wear.WatchRequestResult
import com.example.sos_segundoplano.domain.wear.WatchSensorSnapshot
import com.example.sos_segundoplano.domain.wear.WatchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de la pantalla de conexión con el reloj Wear OS. */
data class WatchConnectionUiState(
    val connection: WatchConnectionState = WatchConnectionState.Loading,
    val handshake: WatchHandshakeInfo? = null,
    val status: WatchStatus? = null,
    val snapshot: WatchSensorSnapshot? = null,
    val isRequestingStatus: Boolean = false,
    val isRequestingSnapshot: Boolean = false,
    val notice: WatchNotice? = null
) {
    override fun toString(): String =
        "WatchConnectionUiState(connection=$connection, handshake=${handshake != null}, " +
            "status=${status != null}, snapshot=${snapshot != null}, isRequestingStatus=$isRequestingStatus, " +
            "isRequestingSnapshot=$isRequestingSnapshot, notice=$notice)"
}

/** Mensaje transitorio de una solicitud al reloj. */
sealed interface WatchNotice {
    data class Timeout(val operation: WatchOperation) : WatchNotice
    data class Declined(val reason: WatchDeclineReason) : WatchNotice
    data class InvalidResponse(val operation: WatchOperation) : WatchNotice
}

enum class WatchOperation { Status, Snapshot }

class WatchConnectionViewModel(
    private val repository: WatchConnectionRepository,
    private val authRepository: AuthRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(WatchConnectionUiState())
    val uiState: StateFlow<WatchConnectionUiState> = mutableUiState.asStateFlow()

    private var sessionEpoch = 0L
    private var activeUserId: String? = null
    private var screenStarted = false
    private var connectionChangesJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.observeSession().collect(::onSessionEmitted)
        }
        viewModelScope.launch {
            repository.connectionState.collect(::onConnectionEmitted)
        }
    }

    /** Se llama al entrar a la pantalla; refresca nodos y handshake si aplica. */
    fun onScreenStarted() {
        screenStarted = true
        val currentSession = authRepository.observeSession().value
        if (currentSession is SessionState.Authenticated) {
            if (activeUserId == null) activeUserId = currentSession.user.id
            startConnectionObservation()
        }
        refreshConnection()
    }

    fun refreshConnection() {
        viewModelScope.launch {
            refreshConnectionSafely()
        }
    }

    fun requestStatus() {
        if (!canRequest()) return
        mutableUiState.update { it.copy(isRequestingStatus = true, notice = null) }
        viewModelScope.launch {
            val mySession = sessionEpoch
            val result = try {
                repository.requestStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                WatchRequestResult.InvalidResponse
            }
            if (sessionEpoch != mySession) return@launch
            applyStatusResult(result)
        }
    }

    fun requestSnapshot() {
        if (!canRequest()) return
        mutableUiState.update { it.copy(isRequestingSnapshot = true, notice = null) }
        viewModelScope.launch {
            val mySession = sessionEpoch
            val result = try {
                repository.requestSnapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                WatchRequestResult.InvalidResponse
            }
            if (sessionEpoch != mySession) return@launch
            applySnapshotResult(result)
        }
    }

    fun dismissNotice() {
        mutableUiState.update { it.copy(notice = null) }
    }

    // -------------------------------------------------------------- internals

    private fun canRequest(): Boolean {
        val current = mutableUiState.value
        if (current.isRequestingStatus || current.isRequestingSnapshot) return false
        return current.connection is WatchConnectionState.Connected ||
            current.connection is WatchConnectionState.Timeout
    }

    private fun onSessionEmitted(sessionState: SessionState) {
        when (sessionState) {
            SessionState.LoggedOut,
            SessionState.Expired,
            is SessionState.AccessDenied,
            SessionState.InactiveAccount,
            SessionState.StorageUnavailable -> {
                sessionEpoch++
                activeUserId = null
                stopConnectionObservation()
                repository.reset()
                mutableUiState.value = WatchConnectionUiState()
            }
            is SessionState.Authenticated -> {
                val userId = sessionState.user.id
                if (activeUserId != userId) {
                    sessionEpoch++
                    activeUserId = userId
                    mutableUiState.value = WatchConnectionUiState()
                    if (screenStarted) refreshConnection()
                }
                if (screenStarted) startConnectionObservation()
            }
            SessionState.Restoring,
            is SessionState.Refreshing -> Unit
        }
    }

    private fun startConnectionObservation() {
        if (connectionChangesJob != null) return
        connectionChangesJob = viewModelScope.launch {
            try {
                repository.connectionChanges.collect {
                    if (screenStarted && activeUserId != null) {
                        refreshConnectionSafely()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                connectionChangesJob = null
                mutableUiState.update { it.copy(connection = WatchConnectionState.DataLayerUnavailable) }
            }
        }
    }

    private suspend fun refreshConnectionSafely() {
        try {
            repository.refreshConnection()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            mutableUiState.update { it.copy(connection = WatchConnectionState.DataLayerUnavailable) }
        }
    }

    private fun stopConnectionObservation() {
        connectionChangesJob?.cancel()
        connectionChangesJob = null
    }

    private fun onConnectionEmitted(state: WatchConnectionState) {
        mutableUiState.update { current ->
            val keepReadings = when (state) {
                is WatchConnectionState.Connected,
                is WatchConnectionState.Timeout,
                is WatchConnectionState.RequestingStatus,
                is WatchConnectionState.RequestingSnapshot,
                is WatchConnectionState.Handshaking -> true
                else -> false
            }
            val keepHandshake = when (state) {
                is WatchConnectionState.Connected,
                is WatchConnectionState.Timeout,
                is WatchConnectionState.RequestingStatus,
                is WatchConnectionState.RequestingSnapshot -> true
                else -> false
            }
            current.copy(
                connection = state,
                handshake = if (state is WatchConnectionState.Connected) {
                    state.handshake
                } else if (keepHandshake) {
                    current.handshake
                } else {
                    null
                },
                status = if (keepReadings) current.status else null,
                snapshot = if (keepReadings) current.snapshot else null
            )
        }
    }

    private fun applyStatusResult(result: WatchRequestResult<WatchStatus>) {
        mutableUiState.update { current ->
            when (result) {
                is WatchRequestResult.Success -> current.copy(
                    isRequestingStatus = false,
                    status = result.value,
                    notice = null
                )
                WatchRequestResult.Timeout -> current.copy(
                    isRequestingStatus = false,
                    notice = WatchNotice.Timeout(WatchOperation.Status)
                )
                is WatchRequestResult.Declined -> current.copy(
                    isRequestingStatus = false,
                    notice = WatchNotice.Declined(result.reason)
                )
                WatchRequestResult.InvalidResponse -> current.copy(
                    isRequestingStatus = false,
                    notice = WatchNotice.InvalidResponse(WatchOperation.Status)
                )
                WatchRequestResult.NotConnected -> current.copy(isRequestingStatus = false)
            }
        }
    }

    private fun applySnapshotResult(result: WatchRequestResult<WatchSensorSnapshot>) {
        mutableUiState.update { current ->
            when (result) {
                is WatchRequestResult.Success -> current.copy(
                    isRequestingSnapshot = false,
                    snapshot = result.value,
                    notice = null
                )
                WatchRequestResult.Timeout -> current.copy(
                    isRequestingSnapshot = false,
                    notice = WatchNotice.Timeout(WatchOperation.Snapshot)
                )
                is WatchRequestResult.Declined -> current.copy(
                    isRequestingSnapshot = false,
                    notice = WatchNotice.Declined(result.reason)
                )
                WatchRequestResult.InvalidResponse -> current.copy(
                    isRequestingSnapshot = false,
                    notice = WatchNotice.InvalidResponse(WatchOperation.Snapshot)
                )
                WatchRequestResult.NotConnected -> current.copy(isRequestingSnapshot = false)
            }
        }
    }

    class Factory(
        private val repository: WatchConnectionRepository,
        private val authRepository: AuthRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WatchConnectionViewModel::class.java))
            return modelClass.cast(WatchConnectionViewModel(repository, authRepository))
                ?: throw IllegalArgumentException("Unsupported ViewModel class")
        }
    }
}
