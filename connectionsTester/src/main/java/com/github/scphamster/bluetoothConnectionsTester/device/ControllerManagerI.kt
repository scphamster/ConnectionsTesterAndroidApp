package com.github.scphamster.bluetoothConnectionsTester.device

import com.github.scphamster.bluetoothConnectionsTester.dataLink.ControllerResponse
import com.github.scphamster.bluetoothConnectionsTester.dataLink.DeviceLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

interface ControllerManagerI {
    abstract val dataLink: DeviceLink
    
    abstract fun setVoltageLevel(level: IoBoardsManager.VoltageLevel) : Deferred<ControllerResponse>
    abstract suspend fun getAllBoards() : ControllerResponse
    abstract fun startSocket(): Deferred<Unit>
}