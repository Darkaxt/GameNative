package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMetadataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    private val repository: GameMetadataRepository,
) : ViewModel() {
    private val selectedCanonicalId = MutableStateFlow<CanonicalGameId?>(null)
    private val reloadRevision = MutableStateFlow(0L)

    val state = combine(selectedCanonicalId, reloadRevision) { canonicalId, _ -> canonicalId }
        .flatMapLatest { canonicalId ->
            canonicalId?.let(repository::observe) ?: flowOf(GameDetailState.Loading)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = GameDetailState.Loading,
        )

    fun load(canonicalId: CanonicalGameId) {
        selectedCanonicalId.value = canonicalId
    }

    fun retry() {
        if (selectedCanonicalId.value != null) {
            reloadRevision.value += 1L
        }
    }
}
