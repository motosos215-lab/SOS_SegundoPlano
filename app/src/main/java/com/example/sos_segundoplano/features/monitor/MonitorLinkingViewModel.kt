package com.example.sos_segundoplano.features.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sos_segundoplano.domain.emergency.EmergencyContact
import com.example.sos_segundoplano.domain.emergency.EmergencyContactsRepository
import com.example.sos_segundoplano.domain.emergency.EmergencyContactsResult
import com.example.sos_segundoplano.domain.emergency.EmergencyInvitation
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatus
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatusRepository
import com.example.sos_segundoplano.domain.push.MonitorPushTokenStatusResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MonitorLinkingPhase {
    data object Idle : MonitorLinkingPhase
    data object LoadingInvitation : MonitorLinkingPhase
    data object InvitationReady : MonitorLinkingPhase
    data object Accepting : MonitorLinkingPhase
    data object Linked : MonitorLinkingPhase
}

sealed interface MonitorPushReadiness {
    data object Unknown : MonitorPushReadiness
    data object Checking : MonitorPushReadiness
    data class Ready(val status: MonitorPushTokenStatus) : MonitorPushReadiness
    data class Missing(val status: MonitorPushTokenStatus) : MonitorPushReadiness
    data class Error(val message: String) : MonitorPushReadiness
}

data class MonitorLinkingUiState(
    val code: String = "",
    val phase: MonitorLinkingPhase = MonitorLinkingPhase.Idle,
    val invitation: EmergencyInvitation? = null,
    val linkedContact: EmergencyContact? = null,
    val message: String? = null,
    val pushReadiness: MonitorPushReadiness = MonitorPushReadiness.Unknown
)

class MonitorLinkingViewModel(
    private val emergencyContactsRepository: EmergencyContactsRepository,
    private val pushTokenStatusRepository: MonitorPushTokenStatusRepository
) : ViewModel() {
    private val _state = MutableStateFlow(MonitorLinkingUiState())
    val state: StateFlow<MonitorLinkingUiState> = _state.asStateFlow()

    fun onCodeChanged(value: String) {
        _state.value = _state.value.copy(
            code = value.uppercase(),
            invitation = null,
            linkedContact = null,
            message = null,
            phase = MonitorLinkingPhase.Idle
        )
    }

    fun onQrPayload(payload: String) {
        val code = MonitorLinkingCodeParser.parse(payload)
        if (code == null) {
            _state.value = _state.value.copy(message = "El QR no contiene un código de vinculación válido.")
            return
        }
        _state.value = _state.value.copy(code = code, message = null)
        lookupInvitation()
    }

    fun onScannerFailure() {
        _state.value = _state.value.copy(message = "No se pudo leer el QR. Puedes ingresar el código manualmente.")
    }

    fun lookupInvitation() {
        val code = MonitorLinkingCodeParser.parse(_state.value.code)
        if (code == null) {
            _state.value = _state.value.copy(message = "Ingresa un código de vinculación válido.")
            return
        }
        _state.value = _state.value.copy(
            code = code,
            phase = MonitorLinkingPhase.LoadingInvitation,
            invitation = null,
            linkedContact = null,
            message = null
        )
        viewModelScope.launch {
            when (val result = emergencyContactsRepository.getInvitation(code)) {
                is EmergencyContactsResult.Success -> {
                    _state.value = _state.value.copy(
                        phase = MonitorLinkingPhase.InvitationReady,
                        invitation = result.value,
                        message = null
                    )
                }
                is EmergencyContactsResult.Failure -> {
                    _state.value = _state.value.copy(
                        phase = MonitorLinkingPhase.Idle,
                        message = invitationFailureMessage(result)
                    )
                }
            }
        }
    }

    fun acceptInvitation() {
        val code = MonitorLinkingCodeParser.parse(_state.value.code) ?: return
        if (_state.value.invitation == null || _state.value.phase == MonitorLinkingPhase.Accepting) return
        _state.value = _state.value.copy(phase = MonitorLinkingPhase.Accepting, message = null)
        viewModelScope.launch {
            when (val result = emergencyContactsRepository.acceptInvitation(code)) {
                is EmergencyContactsResult.Success -> {
                    _state.value = _state.value.copy(
                        phase = MonitorLinkingPhase.Linked,
                        linkedContact = result.value,
                        message = "Vinculación completada. Ya puedes recibir las alertas permitidas por el Rider."
                    )
                    refreshPushStatus()
                }
                is EmergencyContactsResult.Failure -> {
                    _state.value = _state.value.copy(
                        phase = MonitorLinkingPhase.InvitationReady,
                        message = invitationFailureMessage(result)
                    )
                }
            }
        }
    }

    fun refreshPushStatus() {
        if (_state.value.pushReadiness == MonitorPushReadiness.Checking) return
        _state.value = _state.value.copy(pushReadiness = MonitorPushReadiness.Checking)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                pushReadiness = when (val result = pushTokenStatusRepository.getStatus()) {
                    is MonitorPushTokenStatusResult.Success -> {
                        if (result.value.hasActiveAndroidFcm) MonitorPushReadiness.Ready(result.value)
                        else MonitorPushReadiness.Missing(result.value)
                    }
                    is MonitorPushTokenStatusResult.Failure -> MonitorPushReadiness.Error(pushFailureMessage(result))
                }
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun invitationFailureMessage(failure: EmergencyContactsResult.Failure): String = when (failure.errorCode) {
        "invitation_expired" -> "La invitación ya expiró. Solicita un código nuevo al Rider."
        "invitation_link_not_allowed" -> "Esta invitación no corresponde a tu cuenta Monitor."
        "invitation_already_linked" -> "La invitación ya está vinculada con otro Monitor."
        "forbidden", "role_not_allowed" -> "Sólo una cuenta Monitor puede aceptar esta invitación."
        else -> when (failure.statusCode) {
            404 -> "No encontramos una invitación activa con ese código."
            401 -> "Tu sesión expiró. Inicia sesión nuevamente."
            else -> "No pudimos validar la invitación. Revisa tu conexión e intenta nuevamente."
        }
    }

    private fun pushFailureMessage(failure: MonitorPushTokenStatusResult.Failure): String = when (failure.statusCode) {
        401 -> "No pudimos verificar las notificaciones porque la sesión ya no es válida."
        403 -> "El estado de notificaciones sólo está disponible para cuentas Monitor."
        else -> "No pudimos verificar el registro FCM. Intenta nuevamente."
    }
}
