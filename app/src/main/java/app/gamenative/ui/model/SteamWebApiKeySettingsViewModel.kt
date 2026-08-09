package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.service.steam.SteamWebApiKeyRepository
import app.gamenative.service.steam.SteamWebApiKeySaveResult
import app.gamenative.service.steam.SteamWebApiKeyStatus
import app.gamenative.service.steam.SteamWebApiKeyValidationResult
import app.gamenative.service.steam.SteamWebApiKeyValidator
import app.gamenative.service.steam.hasValidSteamWebApiKeyFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SteamWebApiKeyValidationState {
    UNTESTED,
    TESTING,
    VALID,
    INVALID,
    UNAVAILABLE,
}

data class SteamWebApiKeySettingsState(
    val configured: Boolean = false,
    val validation: SteamWebApiKeyValidationState = SteamWebApiKeyValidationState.UNTESTED,
    val saving: Boolean = false,
    val saveSucceeded: Boolean = false,
    val invalidFormat: Boolean = false,
    val operationFailed: Boolean = false,
)

@HiltViewModel
class SteamWebApiKeySettingsViewModel @Inject constructor(
    private val repository: SteamWebApiKeyRepository,
    private val validator: SteamWebApiKeyValidator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SteamWebApiKeySettingsState())
    val state: StateFlow<SteamWebApiKeySettingsState> = mutableState.asStateFlow()

    private var validationJob: Job? = null
    private var validatedKeyFingerprint: ByteArray? = null

    init {
        refresh()
    }

    fun test(key: String) {
        cancelValidation()
        if (!hasValidSteamWebApiKeyFormat(key)) {
            mutableState.update {
                it.copy(
                    validation = SteamWebApiKeyValidationState.INVALID,
                    invalidFormat = true,
                    operationFailed = false,
                    saveSucceeded = false,
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                validation = SteamWebApiKeyValidationState.TESTING,
                invalidFormat = false,
                operationFailed = false,
                saveSucceeded = false,
            )
        }
        validationJob = viewModelScope.launch {
            try {
                val result = validator.validate(key)
                if (result == SteamWebApiKeyValidationResult.VALID) {
                    validatedKeyFingerprint = key.fingerprint()
                }
                mutableState.update {
                    it.copy(validation = result.asUiState())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(validation = SteamWebApiKeyValidationState.UNAVAILABLE)
                }
            }
        }
    }

    fun save(key: String) {
        if (
            mutableState.value.saving ||
            mutableState.value.validation != SteamWebApiKeyValidationState.VALID ||
            !isValidatedKey(key)
        ) {
            return
        }
        mutableState.update {
            it.copy(saving = true, operationFailed = false, saveSucceeded = false)
        }
        viewModelScope.launch {
            try {
                when (repository.save(key)) {
                    SteamWebApiKeySaveResult.SAVED -> {
                        clearValidatedKey()
                        mutableState.value = SteamWebApiKeySettingsState(
                            configured = true,
                            saveSucceeded = true,
                        )
                    }
                    SteamWebApiKeySaveResult.INVALID_FORMAT -> mutableState.update {
                        it.copy(
                            saving = false,
                            validation = SteamWebApiKeyValidationState.INVALID,
                            invalidFormat = true,
                            operationFailed = false,
                        )
                    }
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(saving = false, operationFailed = true, invalidFormat = false)
                }
            }
        }
    }

    fun delete() {
        cancelValidation()
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
        cancelValidation()
        mutableState.update {
            it.copy(
                validation = SteamWebApiKeyValidationState.UNTESTED,
                saveSucceeded = false,
                invalidFormat = false,
                operationFailed = false,
            )
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            try {
                val configured = repository.status() == SteamWebApiKeyStatus.CONFIGURED
                mutableState.update { it.copy(configured = configured) }
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true) }
            }
        }
    }

    private fun cancelValidation() {
        validationJob?.cancel()
        clearValidatedKey()
    }

    private fun clearValidatedKey() {
        validatedKeyFingerprint?.fill(0)
        validatedKeyFingerprint = null
    }

    private fun String.fingerprint(): ByteArray {
        val bytes = toByteArray(StandardCharsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun isValidatedKey(key: String): Boolean {
        val fingerprint = key.fingerprint()
        return try {
            validatedKeyFingerprint?.let { MessageDigest.isEqual(it, fingerprint) } == true
        } finally {
            fingerprint.fill(0)
        }
    }

    private fun SteamWebApiKeyValidationResult.asUiState(): SteamWebApiKeyValidationState = when (this) {
        SteamWebApiKeyValidationResult.VALID -> SteamWebApiKeyValidationState.VALID
        SteamWebApiKeyValidationResult.INVALID -> SteamWebApiKeyValidationState.INVALID
        SteamWebApiKeyValidationResult.UNAVAILABLE -> SteamWebApiKeyValidationState.UNAVAILABLE
    }
}
