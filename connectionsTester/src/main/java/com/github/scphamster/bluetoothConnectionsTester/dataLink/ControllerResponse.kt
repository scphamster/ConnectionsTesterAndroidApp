package com.github.scphamster.bluetoothConnectionsTester.dataLink

enum class ControllerResponse(val byteValue: Byte) {
    CommandAcknowledge(1),
    CommandNoAcknowledge(2),
    CommandPerformanceFailure(3),
    CommandPerformanceSuccess(4),
    CommandAcknowledgeTimeout(5),
    CommandPerformanceTimeout(6),
    CommunicationFailure(7),
    DeviceIsInitializing(8)
}