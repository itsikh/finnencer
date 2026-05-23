package io.itsikh.finnencer.ui.screens.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import io.itsikh.finnencer.data.api.VertexOAuthSignIn
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import io.itsikh.finnencer.data.repo.KeyTestResult
import io.itsikh.finnencer.data.repo.KeyValidator
import io.itsikh.finnencer.util.AppSigningInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KeyCardState(
    val key: ApiKey,
    val configured: Boolean,
    val expanded: Boolean = false,
    val editing: Boolean = false,
    val draft: String = "",
    val reveal: Boolean = false,
    val testing: Boolean = false,
    val testResult: KeyTestResult? = null,
)

/**
 * State for the Vertex Google sign-in flow. The screen observes this
 * to decide whether to show "Sign in", a spinner, an inline error, or
 * to launch the consent dialog via ActivityResultLauncher.
 */
sealed interface VertexSignInState {
    object Idle : VertexSignInState
    object Working : VertexSignInState
    data class NeedsConsent(val intentSender: IntentSender) : VertexSignInState
    object Success : VertexSignInState
    data class Error(val message: String) : VertexSignInState
}

@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    private val repo: ApiKeysRepository,
    private val validator: KeyValidator,
    private val vertexSignIn: VertexOAuthSignIn,
    val signingInfo: AppSigningInfo,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<Map<ApiKey, KeyCardState>> = _state.asStateFlow()

    private val _vertexSignInState = MutableStateFlow<VertexSignInState>(VertexSignInState.Idle)
    val vertexSignInState: StateFlow<VertexSignInState> = _vertexSignInState.asStateFlow()

    init {
        // Reactively sync the "configured" flag from the repo.
        viewModelScope.launch {
            repo.configured.collect { configured ->
                _state.value = _state.value.mapValues { (k, v) ->
                    v.copy(configured = configured[k] == true)
                }
            }
        }
    }

    private fun initialState(): Map<ApiKey, KeyCardState> =
        ApiKey.entries.associateWith { k ->
            KeyCardState(key = k, configured = repo.isConfigured(k))
        }

    fun toggleExpand(key: ApiKey) {
        _state.value = _state.value.mapValues { (k, v) ->
            if (k == key) {
                v.copy(
                    expanded = !v.expanded,
                    editing = false,
                    draft = if (!v.expanded) repo.get(k) ?: "" else "",
                    testResult = null,
                )
            } else v
        }
    }

    fun startEdit(key: ApiKey) {
        _state.value = _state.value.mapValues { (k, v) ->
            if (k == key) v.copy(editing = true, draft = repo.get(k) ?: "", testResult = null) else v
        }
    }

    fun updateDraft(key: ApiKey, draft: String) {
        _state.value = _state.value.mapValues { (k, v) ->
            if (k == key) v.copy(draft = draft) else v
        }
    }

    fun toggleReveal(key: ApiKey) {
        _state.value = _state.value.mapValues { (k, v) ->
            if (k == key) v.copy(reveal = !v.reveal) else v
        }
    }

    fun save(key: ApiKey) {
        val draft = _state.value[key]?.draft?.trim() ?: return
        repo.save(key, draft)
        _state.value = _state.value.mapValues { (k, v) ->
            if (k == key) v.copy(editing = false, draft = "", testResult = null) else v
        }
    }

    fun clear(key: ApiKey) {
        repo.clear(key)
        _state.value = _state.value.mapValues { (k, v) ->
            if (k == key) v.copy(editing = false, draft = "", testResult = null) else v
        }
    }

    fun test(key: ApiKey) {
        _state.value = _state.value.mapValues { (k, v) ->
            if (k == key) v.copy(testing = true, testResult = null) else v
        }
        viewModelScope.launch {
            // Fast offline pre-check first; if format is obviously wrong we
            // never burn a network round-trip on the provider.
            val syntax = repo.checkSyntax(key)
            if (syntax is KeyTestResult.BadFormat) {
                _state.value = _state.value.mapValues { (k, v) ->
                    if (k == key) v.copy(testing = false, testResult = syntax) else v
                }
                return@launch
            }
            if (syntax is KeyTestResult.NotConfigured) {
                _state.value = _state.value.mapValues { (k, v) ->
                    if (k == key) v.copy(testing = false, testResult = syntax) else v
                }
                return@launch
            }
            // Real provider-side validation.
            val networkResult = validator.validate(key)
            _state.value = _state.value.mapValues { (k, v) ->
                if (k == key) v.copy(testing = false, testResult = networkResult) else v
            }
        }
    }

    /**
     * Save + immediately fire a real network probe so the user sees
     * "Validated against {provider}" or "Provider rejected the key" without
     * having to leave the screen and trigger a search.
     */
    fun saveAndValidate(key: ApiKey) {
        save(key)
        test(key)
    }

    /**
     * Kick off the Vertex Google sign-in. The screen passes its hosting
     * activity (needed by AuthorizationClient to show the consent
     * dialog). The web client ID is read from the stored
     * VERTEX_OAUTH_WEB_CLIENT_ID; failure to find it surfaces as a
     * config-shaped error so the UI can point the user to the right
     * field instead of an opaque Google error.
     */
    fun startVertexSignIn(activity: Activity) {
        val webClientId = repo.get(ApiKey.VERTEX_OAUTH_WEB_CLIENT_ID)
        if (webClientId.isNullOrBlank()) {
            _vertexSignInState.value = VertexSignInState.Error(
                "Save your Vertex OAuth Web Client ID first (the field just above), then tap Sign in."
            )
            return
        }
        _vertexSignInState.value = VertexSignInState.Working
        viewModelScope.launch {
            when (val res = vertexSignIn.startSignIn(activity, webClientId)) {
                is VertexOAuthSignIn.StartResult.Success ->
                    _vertexSignInState.value = VertexSignInState.Success
                is VertexOAuthSignIn.StartResult.NeedsConsent ->
                    _vertexSignInState.value = VertexSignInState.NeedsConsent(res.intentSender)
                is VertexOAuthSignIn.StartResult.Error ->
                    _vertexSignInState.value = VertexSignInState.Error(res.message)
            }
        }
    }

    /** Called from the ActivityResultLauncher callback after the
     *  consent dialog returns. [data] is the raw Intent; null when the
     *  user cancelled. */
    fun completeVertexSignIn(data: Intent?) {
        _vertexSignInState.value = VertexSignInState.Working
        viewModelScope.launch {
            when (val res = vertexSignIn.completeSignIn(data)) {
                is VertexOAuthSignIn.StartResult.Success ->
                    _vertexSignInState.value = VertexSignInState.Success
                is VertexOAuthSignIn.StartResult.NeedsConsent ->
                    // Shouldn't happen on the completion path, but
                    // surface defensively rather than dropping silently.
                    _vertexSignInState.value = VertexSignInState.NeedsConsent(res.intentSender)
                is VertexOAuthSignIn.StartResult.Error ->
                    _vertexSignInState.value = VertexSignInState.Error(res.message)
            }
        }
    }

    /** Reset to Idle after the UI has shown a success / error toast. */
    fun ackVertexSignInState() {
        _vertexSignInState.value = VertexSignInState.Idle
    }
}
