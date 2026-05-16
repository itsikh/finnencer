package io.itsikh.finnencer.ui.screens.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import javax.inject.Inject

/**
 * Thin Hilt-injected wrapper used by [SettingsScreen] to read the
 * [ApiKeysRepository] without touching the template's [SettingsViewModel].
 */
@HiltViewModel
class ApiKeysHolderViewModel @Inject constructor(
    val repo: ApiKeysRepository,
) : ViewModel()
