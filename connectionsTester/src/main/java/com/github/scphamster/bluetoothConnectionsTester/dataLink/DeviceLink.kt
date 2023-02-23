package com.github.scphamster.bluetoothConnectionsTester.dataLink

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

interface DeviceLink {
    abstract val inputDataChannel: Channel<Collection<Byte>>
    abstract val outputDataChannel: Channel<Collection<Byte>>
    abstract val id: Int
    abstract val isReady: AtomicBoolean
    
    abstract suspend fun run(): Unit
    abstract fun stop()
}