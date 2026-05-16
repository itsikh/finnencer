package io.itsikh.finnencer.ui.screens.keys

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.qr.KeysBundle
import io.itsikh.finnencer.data.qr.QrEncoder
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class QrShareState(
    val encrypt: Boolean = true,
    val passphrase: String = "",
    val bitmap: Bitmap? = null,
    val keyCount: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class QrShareViewModel @Inject constructor(
    private val keysBundle: KeysBundle,
    private val apiKeysRepository: ApiKeysRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(QrShareState())
    val state: StateFlow<QrShareState> = _state.asStateFlow()

    fun setEncrypt(value: Boolean) {
        _state.value = _state.value.copy(encrypt = value, bitmap = null, error = null)
    }

    fun setPassphrase(value: String) {
        _state.value = _state.value.copy(passphrase = value, bitmap = null, error = null)
    }

    fun generate() {
        val s = _state.value
        val pass = if (s.encrypt) s.passphrase else null
        val configuredCount = apiKeysRepository.configured.value.count { it.value }
        keysBundle.buildPayload(pass)
            .onSuccess { payload ->
                runCatching {
                    QrEncoder.encode(content = payload, sizePx = 900)
                }.onSuccess { bmp ->
                    _state.value = s.copy(
                        bitmap = bmp,
                        keyCount = configuredCount,
                        error = null,
                    )
                }.onFailure { t ->
                    _state.value = s.copy(error = "QR encode failed: ${t.message}")
                }
            }
            .onFailure { t ->
                _state.value = s.copy(error = t.message ?: "Could not build payload")
            }
    }
}
