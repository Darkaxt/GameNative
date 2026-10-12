package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.dlc.DlcRepository
import app.gamenative.library.dlc.NativeDlcCopyAuthority
import app.gamenative.library.dlc.NativeDlcSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import app.gamenative.library.dlc.DlcState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class DlcUiState(val selectedCopy: OwnedCopyKey? = null, val detail: DlcState = DlcState.Idle)

@HiltViewModel
internal class DlcViewModel(
    private val repository: DlcRepository,
    private val invalidations: Flow<Unit>,
) : ViewModel() {
    @Inject constructor(
        authority: NativeDlcCopyAuthority,
        source: NativeDlcSource,
        runtimes: OwnedCopyRuntimeRegistry,
    ) : this(DlcRepository(authority, source), runtimes.invalidations())

    private val mutableState = MutableStateFlow(DlcUiState())
    val state = mutableState.asStateFlow()
    private var copies = emptyList<OwnedCopyKey>()
    private var offline = false
    private var loadJob: Job? = null
    private var invalidationJob: Job? = null
    private var requestVersion = 0L

    fun bindCopies(keys: List<OwnedCopyKey>, offline: Boolean) {
        val current = keys.distinct()
        if (copies == current && this.offline == offline) return
        copies = current
        this.offline = offline
        if (current.isEmpty()) {
            clear()
            return
        }
        if (invalidationJob == null) {
            invalidationJob = viewModelScope.launch { invalidations.collect { retry() } }
        }
        val selected = mutableState.value.selectedCopy?.takeIf { it in current } ?: current.singleOrNull()
        load(selected)
    }

    fun select(key: OwnedCopyKey) {
        if (key !in copies) return
        load(key)
    }

    fun retry() {
        val selected = mutableState.value.selectedCopy ?: return
        load(selected)
    }

    fun clear() {
        requestVersion++
        loadJob?.cancel()
        loadJob = null
        invalidationJob?.cancel()
        invalidationJob = null
        copies = emptyList()
        mutableState.value = DlcUiState()
    }

    private fun load(key: OwnedCopyKey?) {
        val version = ++requestVersion
        loadJob?.cancel()
        loadJob = null
        mutableState.value = DlcUiState(key, when {
            key == null -> DlcState.Idle
            offline -> DlcState.Unavailable
            else -> DlcState.Loading
        })
        if (key == null || offline) return
        loadJob = viewModelScope.launch {
            val result = withTimeoutOrNull(60_000) { repository.load(key) } ?: DlcState.Unavailable
            if (version == requestVersion && key in copies && mutableState.value.selectedCopy == key && !offline) {
                mutableState.value = DlcUiState(key, result)
            }
        }
    }
}
