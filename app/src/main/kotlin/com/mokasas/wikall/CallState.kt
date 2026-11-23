package com.mokasas.wikall

import java.util.UUID

internal enum class CallState { IDLE, CALLING, RINGING, ACCEPTED, REJECTED, MISSED, BUSY, ENDED }

internal class TestCall {
    var id: UUID? = null
        private set
    var state: CallState = CallState.IDLE
        private set
    private val recent = LinkedHashSet<UUID>()

    fun dial(): Signal? {
        if (id != null) return null
        val next = UUID.randomUUID()
        id = next
        state = CallState.CALLING
        return Signal(SignalType.INVITE, next)
    }

    fun remote(message: Signal): Signal? {
        if (message.type == SignalType.INVITE) {
            if (message.id in recent || message.id == id || message.id == probeId) return null
            if (id != null) {
                remember(message.id)
                return Signal(SignalType.BUSY, message.id)
            }
            id = message.id
            state = CallState.RINGING
            return null
        }
        if (message.id != id) return null
        when (message.type) {
            SignalType.ACCEPT -> if (state == CallState.CALLING) state = CallState.ACCEPTED
            SignalType.REJECT -> if (state == CallState.CALLING) complete(CallState.REJECTED)
            SignalType.TIMEOUT -> if (state == CallState.CALLING) complete(CallState.MISSED)
            SignalType.BUSY -> if (state == CallState.CALLING) complete(CallState.BUSY)
            SignalType.END -> complete(CallState.ENDED)
            else -> Unit
        }
        return null
    }

    fun decide(type: SignalType): Signal? {
        val active = id ?: return null
        if (type != SignalType.END && state != CallState.RINGING) return null
        when (type) {
            SignalType.ACCEPT -> state = CallState.ACCEPTED
            SignalType.REJECT -> complete(CallState.REJECTED)
            SignalType.TIMEOUT -> complete(CallState.MISSED)
            SignalType.END -> complete(CallState.ENDED)
            else -> return null
        }
        return Signal(type, active)
    }

    fun disconnected() {
        if (id != null) complete(CallState.ENDED)
    }

    private fun complete(next: CallState) {
        id?.let(::remember)
        id = null
        state = next
    }

    private fun remember(call: UUID) {
        recent.add(call)
        if (recent.size > 256) recent.remove(recent.first())
    }
}
