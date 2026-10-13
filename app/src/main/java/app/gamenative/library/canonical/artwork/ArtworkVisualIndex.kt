package app.gamenative.library.canonical.artwork

import java.util.Arrays

/** Visual-only shortlist. Compact primitive postings avoid retaining native Mats or entire descriptor libraries. */
internal class ArtworkVisualIndex {
    private val ids = arrayListOf<String>()
    private val known = hashSetOf<String>()
    private var postings = LongArray(0)
    private val additions = arrayListOf<LongArray>()

    fun add(features: ArtworkFeatures) {
        if (!features.isValid() || !known.add(features.contentHash)) return
        val id = ids.size
        ids += features.contentHash
        additions += keys(features).map { (it.toLong() shl 32) or id.toLong() }.toLongArray()
        if (additions.size >= 32) compact()
    }

    fun candidates(features: ArtworkFeatures): List<String> {
        if (ids.size <= MAX_CANDIDATES) return ids.filter { it != features.contentHash }.sorted()
        val desired = keys(features)
        val votes = IntArray(ids.size)
        for (key in desired) {
            val start = key.toLong() shl 32
            var index = lowerBound(start)
            while (index < postings.size && (postings[index] ushr 32).toInt() == key) {
                votes[postings[index].toInt()]++
                index++
            }
        }
        val desiredSet = desired.toHashSet()
        for (batch in additions) for (posting in batch) {
            if ((posting ushr 32).toInt() in desiredSet) votes[posting.toInt()]++
        }
        return ids.indices.filter { ids[it] != features.contentHash && votes[it] >= MIN_VOTES }
            .sortedWith(compareByDescending<Int> { votes[it] }.thenBy { ids[it] })
            .take(MAX_CANDIDATES).map(ids::get)
    }

    private fun lowerBound(value: Long): Int {
        var low = 0
        var high = postings.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (postings[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }

    private fun compact() {
        val incoming = LongArray(additions.sumOf { it.size })
        var offset = 0
        additions.forEach { it.copyInto(incoming, offset); offset += it.size }
        Arrays.sort(incoming)
        val merged = LongArray(postings.size + incoming.size)
        var left = 0
        var right = 0
        var target = 0
        while (left < postings.size || right < incoming.size) {
            merged[target++] = if (right == incoming.size || (left < postings.size && postings[left] <= incoming[right]))
                postings[left++] else incoming[right++]
        }
        postings = merged
        additions.clear()
    }

    private fun keys(features: ArtworkFeatures): IntArray {
        val selected = minOf(features.count, INDEX_DESCRIPTORS)
        val result = hashSetOf<Int>()
        for (sample in 0 until selected) {
            // Evenly sample ORB's octave-ordered descriptors, rather than selecting only a logo's strongest corners.
            val row = sample * features.count / selected
            for (table in 0 until TABLES) {
                var bits = 0
                for (bit in 0 until 16) {
                    val byte = (table * 43 + bit * 17) and 31
                    val shift = (table * 3 + bit * 5) and 7
                    bits = bits or (((features.descriptors[row * 32 + byte].toInt() ushr shift) and 1) shl bit)
                }
                result += (table shl 16) or bits
            }
        }
        return result.sorted().toIntArray()
    }

    companion object {
        const val MAX_CANDIDATES = 64
        const val MIN_VOTES = 8
        private const val INDEX_DESCRIPTORS = 256
        private const val TABLES = 4
    }
}
