package com.yy.suidu

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A failed worker cancels siblings; already committed checkpoints remain valid. */
object BoundedAnalysis {
    suspend fun <T> run(items: List<T>, concurrency: Int = 3, action: suspend (T) -> Unit) = coroutineScope {
        require(concurrency in 1..3)
        val slots = Semaphore(concurrency)
        items.map { item -> async { slots.withPermit { action(item) } } }.awaitAll()
        Unit
    }
}
