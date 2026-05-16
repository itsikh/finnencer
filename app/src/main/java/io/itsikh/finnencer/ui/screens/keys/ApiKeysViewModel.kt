package io.itsikh.finnencer.ui.screens.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import io.itsikh.finnencer.data.repo.KeyTestResult
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
            val result = repo.checkSyntax(key)
            _state.value = _state.value.mapValues { (k, v) ->
                if (k == key) v.copy(testing = false, testResult = result) else v
            }
        }
    }
}
