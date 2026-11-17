package net.khoango.mavencentralmcp.maven.cache

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Small thread-safe LRU cache with a per-entry time-to-live.
 *
 * Bounded by [maxEntries]; the least recently used entry is evicted first. Expired entries are
 * dropped lazily on access.
 */
class TtlCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttl: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Entry<V>(val value: V, val expiresAt: TimeMark)

    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean = size > maxEntries
    }

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttl.isPositive()) { "ttl must be positive" }
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (entry.expiresAt.hasPassedNow()) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, timeSource.markNow() + ttl)
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()
}
