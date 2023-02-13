package com.github.scphamster.bluetoothConnectionsTester.device

import com.github.scphamster.bluetoothConnectionsTester.dataLink.ControllerResponse

interface ControllerManagerI {
    abstract suspend fun setVoltageLevel(level: IoBoardsManager.VoltageLevel) : ControllerResponse
    
}