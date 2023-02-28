package com.github.scphamster.bluetoothConnectionsTester.dataLink

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

interface DeviceLink {
    val inputDataChannel: Channel<Collection<Byte>>
    val outputDataChannel: Channel<Collection<Byte>>
    val id: Int
    val isReady: AtomicBoolean
    val lastInputOperationTimeStamp: Long
    
    suspend fun run()
    fun stop()
}