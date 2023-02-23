package com.github.scphamster.bluetoothConnectionsTester.device

import com.github.scphamster.bluetoothConnectionsTester.dataLink.ControllerResponse
import com.github.scphamster.bluetoothConnectionsTester.dataLink.DeviceLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

interface ControllerManagerI {
    abstract val dataLink: DeviceLink
    
    abstract suspend fun initialize()
    abstract fun setVoltageLevel(level: IoBoardsManager.VoltageLevel): Deferred<ControllerResponse>
    abstract fun updateAvailableBoards(): Deferred<ControllerResponse>
    abstract suspend fun runDataLink()
    abstract fun stop()
}