package com.example.amctl.services.logging

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

data class ServiceLogEntry(
    val id: Long,
    val timestampMs: Long,
    val source: String,
    val level: String,
    val message: String,
)

object ServiceLogBus {
    private val nextId = AtomicLong(0L)
    private val history = Collections.synchronizedList(mutableListOf<ServiceLogEntry>())
    private val _events = MutableSharedFlow<ServiceLogEntry>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val events: SharedFlow<ServiceLogEntry> = _events

    fun snapshot(): List<ServiceLogEntry> = synchronized(history) { history.toList() }

    fun clear() {
        synchronized(history) { history.clear() }
    }

    fun info(source: String, message: String) = append(source, "INFO", message)

    fun warn(source: String, message: String) = append(source, "WARN", message)

    fun error(source: String, message: String) = append(source, "ERROR", message)

    private fun append(source: String, level: String, message: String) {
        val entry = ServiceLogEntry(
            id = nextId.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            source = source,
            level = level,
            message = message,
        )
        synchronized(history) { history.add(entry) }
        _events.tryEmit(entry)
    }
}
