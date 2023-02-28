package com.github.scphamster.bluetoothConnectionsTester.device

import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.circuit.PinAffinityAndId
import com.github.scphamster.bluetoothConnectionsTester.circuit.SimpleConnectivityDescription
import com.github.scphamster.bluetoothConnectionsTester.circuit.SingleBoardVoltages
import com.github.scphamster.bluetoothConnectionsTester.dataLink.ControllerResponse
import com.github.scphamster.bluetoothConnectionsTester.dataLink.DeviceLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel

interface ControllerManagerI {
    enum class State {
        Initializing,
        GettingBoards,
        NoBoardsFound,
        Operating,
        Failed
    }
    
    abstract val dataLink: DeviceLink
    
    suspend fun setVoltageLevel(level: IoBoardsManager.VoltageLevel): ControllerResponse
    suspend fun updateAvailableBoards(): ControllerResponse
    fun stop()
    suspend fun measureAllVoltages(): Array<SingleBoardVoltages>?
    fun cancelAllJobs()

    suspend fun initialize()
    suspend fun getBoards(): Array<IoBoard>
    suspend fun setVoltageAtPin(pinAffinityAndId: PinAffinityAndId): ControllerResponse
    suspend fun checkSingleConnection(pinAffinityAndId: PinAffinityAndId,
                                      connectionsChannel: Channel<SimpleConnectivityDescription>)
    suspend fun checkConnectionsForLocalBoards(connectionsChannel: Channel<SimpleConnectivityDescription>)
    suspend fun disableOutput(): ControllerResponse
}