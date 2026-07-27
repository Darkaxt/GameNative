package app.gamenative.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.gamenative.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class DiagnosticReportHeader(
    val appVersion: String,
    val buildFlavor: String,
    val device: String,
    val androidVersion: String,
)

object DiagnosticReportBuilder {
    private val json = Json { encodeDefaults = true }

    fun build(context: Context, eventLimit: Int = 1_000): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return build(
            header = DiagnosticReportHeader(
                appVersion = "${packageInfo.versionName} (${PackageInfoCompat.getLongVersionCode(packageInfo)})",
                buildFlavor = BuildConfig.FLAVOR,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
            ),
            events = FeatureDiagnostics.recent(eventLimit),
        )
    }

    internal fun build(
        header: DiagnosticReportHeader,
        events: List<DiagnosticEvent>,
    ): String = buildString {
        appendLine("GameNative feature diagnostic report")
        appendLine("App: ${header.appVersion}")
        appendLine("Flavor: ${header.buildFlavor}")
        appendLine("Device: ${header.device}")
        appendLine("Android: ${header.androidVersion}")
        appendLine("Events: ${events.size}")
        appendLine("Upload: manual export only")
        appendLine()
        events.forEach { event -> appendLine(json.encodeToString(event)) }
    }
}
