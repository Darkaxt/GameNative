package app.gamenative.library.canonical

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class CanonicalProjectionReadiness @Inject constructor() {
    private val mutableReady = MutableStateFlow(false)

    val isReady: StateFlow<Boolean> = mutableReady.asStateFlow()

    internal fun markSucceeded() {
        mutableReady.value = true
    }
}
