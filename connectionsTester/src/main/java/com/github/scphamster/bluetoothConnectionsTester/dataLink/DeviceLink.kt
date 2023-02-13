package com.github.scphamster.bluetoothConnectionsTester.dataLink

import kotlinx.coroutines.channels.Channel

interface DeviceLink {
    abstract val inputDataChannel: Channel<Collection<Byte>>
    abstract val outputDataChannel: Channel<Collection<Byte>>
}