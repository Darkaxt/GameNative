package app.gamenative.ui.model

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.CanonicalLibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface AggregationDiagnosticState {
    data object Closed : AggregationDiagnosticState
    data object Loading : AggregationDiagnosticState
    data object Failed : AggregationDiagnosticState
    data class Content(
        val report: String,
        val exportStatus: AggregationExportStatus = AggregationExportStatus.IDLE,
    ) : AggregationDiagnosticState
}

internal enum class AggregationExportStatus { IDLE, CHOOSING, WRITING, SUCCEEDED, FAILED }

/** Captures the already-displayed report; a late document picker must not export a new selection. */
internal data class AggregationExportRequest internal constructor(
    internal val session: Long,
    internal val id: Long,
    internal val report: String,
)

@HiltViewModel
internal class AggregationDiagnosticViewModel(
    private val explain: suspend (CanonicalLibraryCard) -> String,
    private val write: suspend (Uri, String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    @Inject constructor(
        repository: CanonicalLibraryRepository,
        @ApplicationContext context: Context,
    ) : this(
        explain = repository::explainAggregation,
        write = { uri, report -> writeAggregationReport(context.contentResolver.openOutputStream(uri, "wt"), report) },
    )

    private val mutableState = MutableStateFlow<AggregationDiagnosticState>(AggregationDiagnosticState.Closed)
    val state = mutableState.asStateFlow()
    private var selection: CanonicalLibraryCard? = null
    private var session = 0L
    private var exportId = 0L
    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var pendingExport: AggregationExportRequest? = null

    fun bindSelection(selected: CanonicalLibraryCard) {
        if (selection == selected) return
        close()
        selection = selected
    }

    fun open(selected: CanonicalLibraryCard) {
        bindSelection(selected)
        close()
        val currentSession = session
        mutableState.value = AggregationDiagnosticState.Loading
        loadJob = viewModelScope.launch {
            val result = try {
                AggregationDiagnosticState.Content(withContext(ioDispatcher) { explain(selected) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AggregationDiagnosticState.Failed
            }
            if (session == currentSession && selection == selected) mutableState.value = result
        }
    }

    fun retry() { selection?.let(::open) }

    fun close() {
        session++
        loadJob?.cancel()
        loadJob = null
        exportJob?.cancel()
        exportJob = null
        pendingExport = null
        mutableState.value = AggregationDiagnosticState.Closed
    }

    fun prepareExport(): AggregationExportRequest? {
        val content = mutableState.value as? AggregationDiagnosticState.Content ?: return null
        if (content.exportStatus == AggregationExportStatus.CHOOSING || content.exportStatus == AggregationExportStatus.WRITING) return null
        val request = AggregationExportRequest(session, ++exportId, content.report)
        pendingExport = request
        mutableState.value = content.copy(exportStatus = AggregationExportStatus.CHOOSING)
        return request
    }

    fun exportLaunchFailed(request: AggregationExportRequest) {
        if (pendingExport != request || session != request.session) return
        pendingExport = null
        setExportStatus(request, AggregationExportStatus.FAILED)
    }

    fun completeExport(request: AggregationExportRequest, uri: Uri?) {
        if (pendingExport != request || session != request.session) return
        pendingExport = null
        if (uri == null) {
            setExportStatus(request, AggregationExportStatus.IDLE)
            return
        }
        setExportStatus(request, AggregationExportStatus.WRITING)
        exportJob = viewModelScope.launch {
            val status = try {
                withContext(ioDispatcher) { write(uri, request.report) }
                AggregationExportStatus.SUCCEEDED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AggregationExportStatus.FAILED
            }
            setExportStatus(request, status)
        }
    }

    private fun setExportStatus(request: AggregationExportRequest, status: AggregationExportStatus) {
        val content = mutableState.value as? AggregationDiagnosticState.Content ?: return
        if (session == request.session && content.report == request.report) {
            mutableState.value = content.copy(exportStatus = status)
        }
    }
}

internal fun writeAggregationReport(stream: OutputStream?, report: String) {
    val destination = stream ?: throw IOException("Document output unavailable")
    destination.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
}
