package com.github.scphamster.bluetoothConnectionsTester.device

import com.github.scphamster.bluetoothConnectionsTester.dataLink.ControllerResponse
import com.github.scphamster.bluetoothConnectionsTester.dataLink.DeviceLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

interface ControllerManagerI {
    abstract val dataLink: DeviceLink
    
    abstract fun initialize(): Job
    abstract fun setVoltageLevel(level: IoBoardsManager.VoltageLevel): Deferred<ControllerResponse>
    abstract fun updateAvailableBoards(): Deferred<ControllerResponse>
    abstract fun startSocket(): Deferred<Unit>
    abstract fun stop()
}