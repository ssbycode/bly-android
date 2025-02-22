package com.ssbycode.bly.domain.firebase

import android.util.LruCache
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.HashMap

// Structure to store signals with timestamp
data class CachedSignal(
    val signal: Map<String, Any>,
    val timestamp: Date = Date()
)

class SignalCache {
    // Main cache using LruCache for automatic memory management
    private val cache: LruCache<String, CachedSignal>

    // Map of timestamps for cleanup
    private val timestamps: MutableMap<String, Date> = HashMap()

    // Configuration
    private val maxCacheAge: Long = 60_000 // 1 minute in milliseconds
    private val cleanupInterval: Long = 30_000 // 30 seconds in milliseconds
    private val maxCacheSize = 1000 // Maximum number of items

    // Coroutine scope for cleanup
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        cache = object : LruCache<String, CachedSignal>(maxCacheSize) {
            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: CachedSignal,
                newValue: CachedSignal?
            ) {
                super.entryRemoved(evicted, key, oldValue, newValue)
                // Remove timestamp when entry is evicted
                if (evicted) {
                    timestamps.remove(key)
                }
            }
        }
        setupCleanupTimer()
    }

    // MARK: - Cache Operations

    fun store(signalId: String, signal: Map<String, Any>) {
        val cachedSignal = CachedSignal(signal)
        cache.put(signalId, cachedSignal)
        timestamps[signalId] = Date()

        println("📥 Signal stored in cache: $signalId")
    }

    fun retrieve(signalId: String): Map<String, Any>? {
        val cachedSignal = cache.get(signalId) ?: return null

        // Check if signal has expired
        if (Date().time - cachedSignal.timestamp.time > maxCacheAge) {
            remove(signalId)
            return null
        }

        println("📤 Signal retrieved from cache: $signalId")
        return cachedSignal.signal
    }

    fun remove(signalId: String) {
        cache.remove(signalId)
        timestamps.remove(signalId)
        println("🗑️ Signal removed from cache: $signalId")
    }

    fun clear() {
        cache.evictAll()
        timestamps.clear()
        println("🧹 Cache completely cleared")
    }

    // MARK: - Cache Cleanup

    private fun setupCleanupTimer() {
        cleanupScope.launch {
            while (isActive) {
                cleanExpiredSignals()
                delay(cleanupInterval)
            }
        }
    }

    private fun cleanExpiredSignals() {
        val currentTime = Date().time

        // Remove expired signals
        timestamps.entries
            .filter { (_, timestamp) ->
                currentTime - timestamp.time > maxCacheAge
            }
            .forEach { (signalId, _) ->
                remove(signalId)
            }
    }

    // MARK: - Statistics

    val statistics: Map<String, Any>
        get() = mapOf(
            "totalItems" to cache.maxSize(),
            "maxAge" to maxCacheAge,
            "cleanupInterval" to cleanupInterval,
            "currentSize" to cache.size(),
            "hitCount" to cache.hitCount(),
            "missCount" to cache.missCount()
        )

    // Cleanup when the cache is no longer needed
    fun destroy() {
        cleanupScope.cancel()
        clear()
    }
}