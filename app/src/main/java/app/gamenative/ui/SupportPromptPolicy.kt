package app.gamenative.ui

private const val SUPPORT_PROMPT_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1_000L

internal fun isSupportPromptDue(lastShownAtMillis: Long, nowMillis: Long): Boolean {
    if (lastShownAtMillis <= 0L) return true
    if (nowMillis < lastShownAtMillis) return false
    return nowMillis - lastShownAtMillis >= SUPPORT_PROMPT_INTERVAL_MILLIS
}

internal suspend fun claimSupportPrompt(
    lastShownAtMillis: Long,
    nowMillis: Long,
    persistShownAt: suspend (Long) -> Unit,
): Boolean {
    if (!isSupportPromptDue(lastShownAtMillis, nowMillis)) return false
    persistShownAt(nowMillis)
    return true
}
