package io.itsikh.finnencer.ui.screens.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    private val repo: ApiKeysRepository,
    private val validator: KeyValidator,
    val signingInfo: AppSigningInfo,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<Map<ApiKey, KeyCardState>> = _state.asStateFlow()

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
}
