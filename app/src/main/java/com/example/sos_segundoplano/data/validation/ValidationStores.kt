package com.example.sos_segundoplano.data.validation

import com.example.sos_segundoplano.domain.validation.AlertDispatchRequest
import com.example.sos_segundoplano.domain.validation.FalsePositiveValidationConfig
import com.example.sos_segundoplano.domain.validation.LocalIncident
import com.example.sos_segundoplano.domain.validation.MinorEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

interface BoundedValidationStore<T> {
    val items: StateFlow<List<T>>
    val droppedCount: Long
    fun add(item: T): T
    fun clear()
}

class InMemoryBoundedValidationStore<T>(
    private val capacity: Int
) : BoundedValidationStore<T> {
    init {
        require(capacity > 0)
    }

    private val dropped = AtomicLong(0L)
    private val _items = MutableStateFlow<List<T>>(emptyList())
    override val items: StateFlow<List<T>> = _items
    override val droppedCount: Long get() = dropped.get()

    override fun add(item: T): T {
        val next = _items.value + item
        _items.value = if (next.size > capacity) {
            dropped.incrementAndGet()
            next.takeLast(capacity)
        } else {
            next
        }
        return item
    }

    override fun clear() {
        _items.value = emptyList()
        dropped.set(0L)
    }
}

typealias MinorEventStore = BoundedValidationStore<MinorEvent>
typealias LocalIncidentStore = BoundedValidationStore<LocalIncident>
typealias AlertDispatchRequestStore = BoundedValidationStore<AlertDispatchRequest>

object IncidentStoreProvider {
    private val config = FalsePositiveValidationConfig()
    val minorEvents: MinorEventStore = InMemoryBoundedValidationStore(config.maxMinorEvents)
    val incidents: LocalIncidentStore = InMemoryBoundedValidationStore(config.maxIncidents)
    val dispatchRequests: AlertDispatchRequestStore = InMemoryBoundedValidationStore(config.maxAlertRequests)
    val minorEventIds = AtomicLong(0L)
    val incidentIds = AtomicLong(0L)
    val dispatchRequestIds = AtomicLong(0L)
}
