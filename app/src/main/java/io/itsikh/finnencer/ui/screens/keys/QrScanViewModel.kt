package io.itsikh.finnencer.ui.screens.keys

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.qr.KeysBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed interface ScanStage {
    object Scanning : ScanStage
    data class NeedsPassphrase(val rawPayload: String) : ScanStage
    data class Preview(val rawPayload: String, val parsed: Map<String, String>) : ScanStage
    data class Error(val message: String) : ScanStage
    object Imported : ScanStage
}

data class QrScanState(
    val stage: ScanStage = ScanStage.Scanning,
    val passphrase: String = "",
    val overwriteExisting: Boolean = true,
    val cameraGranted: Boolean = false,
)

@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val keysBundle: KeysBundle,
) : ViewModel() {

    private val _state = MutableStateFlow(QrScanState())
    val state: StateFlow<QrScanState> = _state.asStateFlow()

    fun setCameraGranted(granted: Boolean) {
        _state.value = _state.value.copy(cameraGranted = granted)
    }

    fun onScannedRaw(raw: String) {
        // Try parsing without passphrase first
        keysBundle.parsePayload(raw)
            .onSuccess { parsed ->
                _state.value = _state.value.copy(stage = ScanStage.Preview(raw, parsed))
            }
            .onFailure { t ->
                val msg = t.message ?: "Could not parse QR"
                if (msg.contains("passphrase", ignoreCase = true)) {
                    _state.value = _state.value.copy(stage = ScanStage.NeedsPassphrase(raw))
                } else {
                    _state.value = _state.value.copy(stage = ScanStage.Error(msg))
                }
            }
    }

    fun setPassphrase(value: String) {
        _state.value = _state.value.copy(passphrase = value)
    }

    fun tryDecryptWithPassphrase() {
        val s = _state.value
        val stage = s.stage as? ScanStage.NeedsPassphrase ?: return
        keysBundle.parsePayload(stage.rawPayload, s.passphrase)
            .onSuccess { parsed ->
                _state.value = s.copy(stage = ScanStage.Preview(stage.rawPayload, parsed))
            }
            .onFailure { t ->
                _state.value = s.copy(stage = ScanStage.Error(t.message ?: "Decrypt failed"))
            }
    }

    fun setOverwrite(value: Boolean) {
        _state.value = _state.value.copy(overwriteExisting = value)
    }

    fun confirmImport() {
        val stage = _state.value.stage as? ScanStage.Preview ?: return
        keysBundle.importPayload(stage.parsed, overwrite = _state.value.overwriteExisting)
        _state.value = _state.value.copy(stage = ScanStage.Imported)
    }

    fun reset() {
        _state.value = _state.value.copy(stage = ScanStage.Scanning, passphrase = "")
    }
}
