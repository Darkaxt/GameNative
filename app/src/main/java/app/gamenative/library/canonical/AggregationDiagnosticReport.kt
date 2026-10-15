package app.gamenative.library.canonical

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.db.dao.CanonicalLibraryAggregate
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEvent
import app.gamenative.diagnostics.DiagnosticRedactor
import app.gamenative.library.canonical.artwork.ArtworkMatch
import app.gamenative.library.canonical.artwork.ArtworkMatchReason
import app.gamenative.library.canonical.catalog.SteamCatalogNormalization
import java.util.Locale

/** Only approved public fields enter this immutable, manually exported explanation. */
internal object AggregationDiagnosticReport {
    private const val MAX_CHARS = 262_144

    fun stale(): String = "Aggregation explanation: STALE\nThe entry or its native evidence changed or disappeared. Close this report and retry. No grouping or source action was changed."

    fun build(cards: List<CanonicalLibraryCard>, selected: CanonicalLibraryCard,
        aggregates: List<CanonicalLibraryAggregate>, simulation: AggregationArtworkSimulation,
        recorded: List<DiagnosticEvent> = emptyList()): String {
        if (simulation.stale) return stale()
        val focused = simulation.focused
        val labels = focused.mapIndexed { index, card -> card.canonicalId to
            "Entry ${index + 1} (${card.ownedSources.sortedBy { it.ordinal }.joinToString { it.name }}): ${public(DiagnosticAttribute.PUBLIC_TITLE, card.displayName)}" }.toMap()
        val selectedKeys = selected.copies.map { it.key }.toSet()
        val cachedRelationships = CanonicalGameFamilyProjection.relationships(cards, simulation.cachedEdges)
        val cachedProjection = CanonicalGameFamilyProjection.project(cards, simulation.cachedEdges)
        val replayProjection = CanonicalGameFamilyProjection.project(cards, simulation.replayEdges)
        val selectedFamily = cachedProjection.firstOrNull { card -> card.copies.any { it.key in selectedKeys } }
        val members = selectedFamily?.memberSteamAppIds?.keys?.ifEmpty { setOf(selectedFamily.canonicalId) }.orEmpty()
        val originals = simulation.originals.associateBy { it.reference }
        val report = buildString {
            appendLine("GameNative — Explain aggregation")
            appendLine("CURRENT SNAPSHOT SIMULATION — not a reconstruction of the original resolver decision.")
            appendLine("No catalog, grouping, override, installation, preference or comparison-cache writes. No Steam catalog search is rerun.")
            appendLine("Scope: selected entries, cached family and full-title peers; not a new library-wide artwork discovery scan.")
            appendLine()
            appendLine("1. Current presentation")
            appendLine("Admitted support: ${cachedRelationships.reason(members, selectedFamily?.copies?.size ?: 0)}")
            appendLine("Stored/cached projection contains ${selectedFamily?.copies?.size ?: 0} copies and ${members.size} original metadata entries.")
            appendLine("Title edges are immediate and additive. They do not wait for metadata or banner checks to fail.")
            appendLine("Fresh artwork replay below is isolated: even a different result does not regroup the library.")
            if (!cachedRelationships.valid) appendLine("INVALID MEMBERSHIP: family projection refused duplicate or malformed identity bindings.")
            appendLine()
            appendLine("2. Original entries and stored metadata decisions")
            for (card in focused) {
                appendLine(labels.getValue(card.canonicalId))
                appendLine("  Presentation admission: ${if (CanonicalGameFamilyProjection.eligible(card)) "eligible" else "SKIPPED — manual separation, independent/malformed binding or unavailable native copy"}")
                val titleKey = if (public(DiagnosticAttribute.PUBLIC_TITLE, card.displayName) == "[redacted]") {
                    "[redacted]"
                } else SteamCatalogNormalization.titleKey(card.displayName)
                appendLine("  Full title key: ${public(DiagnosticAttribute.PUBLIC_TITLE, titleKey)}")
                appendLine("  Catalog AppID: ${card.steamAppId?.let { public(DiagnosticAttribute.STEAM_APP_ID, it.toString()) } ?: "unavailable"}; native copies: ${card.copies.size}")
                for (copy in card.copies) {
                    appendLine("  ${copy.source}: ${public(DiagnosticAttribute.PUBLIC_TITLE, copy.nativeTitle)} — stored ${copy.matchMethod}/${copy.confidence}, ${copy.decisionSource}; resolver version ${copy.decisionResolverVersion}")
                }
                if (card.copies.size > 1) appendLine("  Metadata association: ACCEPTED/STORED — these copies already share one canonical metadata entry.")
            }
            appendLine("Stored review/unmatched states are not fresh failures. Historical provider/policy failure reasons may not have been retained.")
            appendLine()
            appendLine("3. Current local metadata check (same policy used by CanonicalGameResolver)")
            var metadataPairs = 0
            val aggregateById = aggregates.associateBy { it.game.canonicalId }
            for (source in focused) for (target in focused) {
                if (source.canonicalId == target.canonicalId) continue
                val evidence = aggregateById[source.canonicalId.value]
                val candidate = aggregateById[target.canonicalId.value]?.game
                val matches = evidence?.matches.orEmpty().filter { it.isPresent }
                if (candidate == null || matches.isEmpty()) {
                    appendLine("  ${entry(source.canonicalId, labels)} → ${entry(target.canonicalId, labels)}: UNAVAILABLE — no current metadata evidence")
                    continue
                }
                for (match in matches) {
                    val reason = CanonicalGameResolver.exactMetadataReason(match.evidenceTitleKey, match.evidenceAppType,
                        match.evidenceDeveloperKey, match.evidenceReleaseYear, candidate)
                    appendLine("  ${entry(source.canonicalId, labels)} ${match.source} → ${entry(target.canonicalId, labels)}: ${metadataReason(reason)}")
                    metadataPairs++
                }
            }
            if (metadataPairs == 0) appendLine("  SKIPPED — no separate metadata entry in the diagnostic scope.")
            appendLine("A compatible pair is not a new catalog acceptance: multiple candidates, direct/user decisions and retained Steam associations are separate resolver gates.")
            appendLine("The current local check does not prove the historical cause; persisted evidence can change while a prior decision remains.")
            appendLine()
            appendLine("4. Original banners/artwork")
            if (simulation.originals.isEmpty()) appendLine("  UNAVAILABLE — no admitted original references (missing/unsupported artwork, unavailable native copy or manual exclusion).")
            for ((index, original) in simulation.originals.withIndex()) {
                appendLine("  Original ${index + 1}: ${original.reference.source} ${public(DiagnosticAttribute.PUBLIC_URL, original.reference.url)}")
                appendLine("    Cached content: ${original.cachedContent?.let { public(DiagnosticAttribute.PUBLIC_CONTENT_ID, it.take(16)) } ?: "unavailable"}; descriptor age: ${if (original.cachedContent == null) "unknown" else if (original.cachedFresh) "fresh cache" else "stale cache"}")
                val inspection = original.current
                appendLine("    Current acquisition: ${inspection?.let { "${it.stage}${it.httpStatus?.let { status -> " (HTTP $status)" }.orEmpty()}" } ?: "SKIPPED — bounded replay not reached or native evidence unavailable"}")
                inspection?.features?.let { appendLine("    Current content: ${public(DiagnosticAttribute.PUBLIC_CONTENT_ID, it.contentHash.take(16))}; usable descriptors: ${it.count}") }
            }
            appendLine()
            appendLine("5. Banner comparisons (actual original pairs, not aggregate counts)")
            if (simulation.pairs.isEmpty()) appendLine("  SKIPPED — no separate admitted original pair in scope.")
            for (pair in simulation.pairs) {
                val left = simulation.originals.indexOfFirst { it.reference == pair.left } + 1
                val right = simulation.originals.indexOfFirst { it.reference == pair.right } + 1
                appendLine("  Original $left ↔ Original $right")
                appendLine("    Historical cached verdict: ${pair.cachedAccepted?.let { if (it) "ACCEPTED" else "REJECTED" } ?: "UNAVAILABLE / no completed cached comparison"}; original rejection reason and ORB metrics were not retained in the Boolean cache.")
                val result = pair.replay
                appendLine("    Current replay: ${result?.let(::matchResult) ?: "UNAVAILABLE/SKIPPED — usable originals missing or replay budget exhausted; not a rejected visual match"}")
                if (result?.completed == true) appendLine("    ${metrics(result)}")
                val oldLeft = originals[pair.left]?.cachedContent
                val oldRight = originals[pair.right]?.cachedContent
                val newLeft = originals[pair.left]?.current?.features?.contentHash
                val newRight = originals[pair.right]?.current?.features?.contentHash
                if (oldLeft != null && oldRight != null && newLeft != null && newRight != null && (oldLeft != newLeft || oldRight != newRight)) {
                    appendLine("    CONTENT CHANGED — cached verdict concerns different original bytes; do not equate it with the current replay.")
                }
            }
            appendLine("ORB-v1 thresholds unchanged: ≥24 inliers; ratio ≥0.55; hull ≥0.18 and ≥7 cells in both images; spans ≥0.45/0.40; scale 0.15–6; rotation ≤25°; residual ≤2.5px.")
            appendLine()
            appendLine("6. Full-title fallback and graph result")
            for (left in focused.indices) for (right in left + 1 until focused.size) {
                val a = focused[left]; val b = focused[right]
                val key = SteamCatalogNormalization.titleKey(a.displayName)
                val equal = key.isNotBlank() && key == SteamCatalogNormalization.titleKey(b.displayName)
                val eligible = CanonicalGameFamilyProjection.eligible(a) && CanonicalGameFamilyProjection.eligible(b) && cachedRelationships.valid
                appendLine("  Entry ${left + 1} ↔ Entry ${right + 1}: ${if (!eligible) "SKIPPED — manual/native/identity safeguard" else if (equal) "ACCEPTED — exact full normalized title; title alone is sufficient for this relationship" else "REJECTED — full titles differ or are blank (no fuzzy/edition-base match)"}")
            }
            val replayFamily = replayProjection.firstOrNull { card -> card.copies.any { it.key in selectedKeys } }
            val replayMembers = replayFamily?.memberSteamAppIds?.keys?.ifEmpty { setOf(replayFamily.canonicalId) }.orEmpty()
            appendLine("Isolated current replay support: ${CanonicalGameFamilyProjection.relationships(cards, simulation.replayEdges).reason(replayMembers, replayFamily?.copies?.size ?: 0)}")
            appendLine("Presentation paths preserve all original member catalog and source identities. Artwork edges can connect unrelated titles; metadata equality is not an artwork prerequisite.")
            appendLine()
            appendLine("7. Retained public-name observations (not exact historical member attribution)")
            val publicTitles = focused.flatMap { it.copies.map { copy -> public(DiagnosticAttribute.PUBLIC_TITLE, copy.nativeTitle) } }.toSet()
            val summaries = recorded.filter { event ->
                event.attributes["public_title"] in publicTitles &&
                    (event.attributes["operation"] == "STEAM_CATALOG_RESOLUTION" ||
                        (event.attributes["operation"] == "ARTWORK_TRACE" && event.attributes["confidence"]?.startsWith("pair=") != true && event.attributes["public_url_id"] == null))
            }
            for (event in summaries.takeLast(24)) {
                val allowed = event.attributes.filterKeys { it in setOf("public_title", "source", "stage", "reason", "grouping_reason", "family_size", "initial_match_method", "initial_match_confidence") }
                appendLine("  ${event.outcome}: ${DiagnosticRedactor.sanitizePersisted(allowed).entries.joinToString { "${it.key}=${it.value}" }}")
            }
            if (summaries.isEmpty()) appendLine("  UNAVAILABLE — no retained attributed resolver/publication summary. No historical failure cause is invented.")
            if (summaries.size > 24) appendLine("  Older summaries omitted: ${summaries.size - 24}")
            appendLine()
            appendLine("Bounds: ≤${AggregationArtworkSimulator.MAX_ENTRIES} entries, ≤${AggregationArtworkSimulator.MAX_ORIGINALS} originals, ≤${AggregationArtworkSimulator.MAX_PAIRS} pairs, 60s fresh-replay budget.")
            appendLine("Omitted: ${simulation.omittedEntries} entries, ${simulation.omittedOriginals} originals, ${simulation.omittedPairs} pairs. Budget exhausted: ${simulation.budgetExhausted}.")
            if (simulation.budgetExhausted || simulation.omittedEntries + simulation.omittedOriginals + simulation.omittedPairs > 0) {
                appendLine("INCOMPLETE REPLAY — skipped/unavailable checks are not failed comparisons or proof that artwork cannot group these entries.")
            }
        }
        return if (report.length <= MAX_CHARS) report else report.take(MAX_CHARS) + "\nREPORT TRUNCATED at $MAX_CHARS characters; omitted text is not evidence of a failed check."
    }

    private fun public(attribute: DiagnosticAttribute, value: String): String =
        DiagnosticRedactor.sanitize(mapOf(attribute to value)).getValue(attribute.wireName)

    private fun entry(id: CanonicalGameId, labels: Map<CanonicalGameId, String>): String = labels[id]?.substringBefore(" (") ?: "Entry outside scope"

    private fun metadataReason(reason: CanonicalGameResolver.ExactMetadataReason): String = when (reason) {
        CanonicalGameResolver.ExactMetadataReason.COMPATIBLE -> "ACCEPTED — current local metadata is compatible"
        CanonicalGameResolver.ExactMetadataReason.TITLE_DIFFERENT -> "REJECTED — normalized source/candidate titles differ or are blank"
        CanonicalGameResolver.ExactMetadataReason.TYPE_UNKNOWN -> "UNAVAILABLE — source or candidate product type is unknown"
        CanonicalGameResolver.ExactMetadataReason.TYPE_DIFFERENT -> "REJECTED — known product types differ"
        CanonicalGameResolver.ExactMetadataReason.DEVELOPER_CONFLICT -> "REJECTED — known developer keys disagree"
        CanonicalGameResolver.ExactMetadataReason.YEAR_CONFLICT -> "REJECTED — known release years differ by more than one"
        CanonicalGameResolver.ExactMetadataReason.CORROBORATION_MISSING -> "UNAVAILABLE — neither a matching known developer nor release years within one are available"
    }

    private fun matchResult(result: ArtworkMatch): String = when {
        !result.completed -> "UNAVAILABLE — ${result.reason}; native matching did not complete"
        result.accepted -> "ACCEPTED — ${result.reason}"
        result.reason == ArtworkMatchReason.REASON_NOT_RETAINED -> "REJECTED — reason not retained; no specific failing gate can be attributed"
        else -> "REJECTED — ${result.reason} (matcher-owned first failing gate)"
    }

    private fun metrics(result: ArtworkMatch): String = String.format(Locale.ROOT,
        "matches=%d; inliers=%d; ratio=%.4f; hull=%.4f; cells=%d; residual=%.3fpx",
        result.matches, result.inliers, result.inlierRatio, result.coverage, result.occupiedCells, result.meanError)
}
