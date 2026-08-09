package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.service.steam.SteamWebApiKeyRepository
import app.gamenative.service.steam.SteamWebApiKeySaveResult
import app.gamenative.service.steam.SteamWebApiKeyStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SteamWebApiKeySettingsState(
    val configured: Boolean = false,
    val invalidFormat: Boolean = false,
    val operationFailed: Boolean = false,
)

@HiltViewModel
class SteamWebApiKeySettingsViewModel @Inject constructor(
    private val repository: SteamWebApiKeyRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SteamWebApiKeySettingsState())
    val state: StateFlow<SteamWebApiKeySettingsState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun save(key: String) {
        viewModelScope.launch {
            try {
                when (repository.save(key)) {
                    SteamWebApiKeySaveResult.SAVED -> mutableState.value =
                        SteamWebApiKeySettingsState(configured = true)
                    SteamWebApiKeySaveResult.INVALID_FORMAT -> mutableState.update {
                        it.copy(invalidFormat = true, operationFailed = false)
                    }
                }
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true, invalidFormat = false) }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                repository.delete()
                mutableState.value = SteamWebApiKeySettingsState(configured = false)
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true, invalidFormat = false) }
            }
        }
    }

    fun clearFeedback() {
        mutableState.update { it.copy(invalidFormat = false, operationFailed = false) }
    }

    private fun refresh() {
        viewModelScope.launch {
            try {
                val configured = repository.status() == SteamWebApiKeyStatus.CONFIGURED
                mutableState.value = SteamWebApiKeySettingsState(configured = configured)
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true) }
            }
        }
    }
}
