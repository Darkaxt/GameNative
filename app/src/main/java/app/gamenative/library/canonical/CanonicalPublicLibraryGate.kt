package app.gamenative.library.canonical

import app.gamenative.PrefManager
import javax.inject.Inject
import javax.inject.Singleton

fun interface CanonicalPublicLibraryGate {
    fun isEnabled(): Boolean
}

@Singleton
class PrefManagerCanonicalPublicLibraryGate @Inject constructor() : CanonicalPublicLibraryGate {
    override fun isEnabled(): Boolean =
        PrefManager.canonicalProjectionEnabled && PrefManager.canonicalPublicLibraryEnabled
}
