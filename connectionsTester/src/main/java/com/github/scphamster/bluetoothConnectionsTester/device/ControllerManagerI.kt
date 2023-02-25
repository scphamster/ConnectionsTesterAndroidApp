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
    abstract val dataLink: DeviceLink
    
    fun setVoltageLevel(level: IoBoardsManager.VoltageLevel): Deferred<ControllerResponse>
    fun updateAvailableBoards(): Deferred<ControllerResponse>
    fun stop()
    fun measureAllVoltages(): Deferred<Array<SingleBoardVoltages>?>
    fun cancelAllJobs()

    suspend fun initialize()
    suspend fun getBoards(): Array<IoBoard>
    suspend fun setVoltageAtPin(pinAffinityAndId: PinAffinityAndId): ControllerResponse
    suspend fun checkSingleConnection(pinAffinityAndId: PinAffinityAndId,
                                      connectionsChannel: Channel<SimpleConnectivityDescription>)
    suspend fun checkConnectionsForLocalBoards(connectionsChannel: Channel<SimpleConnectivityDescription>)
}