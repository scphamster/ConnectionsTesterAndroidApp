package com.github.scphamster.bluetoothConnectionsTester.device

import android.media.tv.CommandRequest
import android.service.controls.Control
import android.util.Log
import java.util.TimerTask
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.dataLink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

class ControllerManager(val scope: CoroutineScope, val dataLink: DeviceLink) : ControllerManagerI {
    companion object {
        private const val MESSAGES_CHANNEL_SIZE = 10
        private const val Tag = "ControllerManager"
        private const val COMMAND_ACK_TIMEOUT_MS = 200
    }
    
    var boards = emptyList<IoBoard>()
        private set
    
    private val inputDataCh = dataLink.inputDataChannel
    private val outputDataCh = dataLink.outputDataChannel
    private val inputMessagesChannel = Channel<MessageFromController>(MESSAGES_CHANNEL_SIZE)
    private val outputMessagesChannel = Channel<MasterToControllerMsg>(MESSAGES_CHANNEL_SIZE)
    private val mutex = Mutex()
    private var voltageLevel = IoBoardsManager.VoltageLevel.Low
    
    init {
        scope.launch { rawDataReceiverTask() }
        scope.launch { inputMessagesHandlerTask() }
        scope.launch { testTask() }
        scope.launch { outputMessagesHandlerTask() }
    }
    
    fun initialize(voltageLevel: IoBoardsManager.VoltageLevel) {
    }
    
    override suspend fun setVoltageLevel(level: IoBoardsManager.VoltageLevel): ControllerResponse {
        scope.launch(Dispatchers.Default) {
            outputMessagesChannel.send(SetOutputVoltageLevel(level))
        }
        
        return checkResponse(300)
    }
    
    private suspend fun checkResponse(delayToCheckCmdResult: Int) =
        withContext<ControllerResponse>(Dispatchers.Default) {
            val ackResult = try {
                withTimeout(COMMAND_ACK_TIMEOUT_MS.toLong()) {
                    async<ControllerResponse>(Dispatchers.Default) {
                        val msg = mutex.withLock {
                            inputMessagesChannel.receiveCatching()
                                .getOrNull()
                        }
                        
                        if (msg == null) {
                            Log.e(Tag, "MSG is null, waiting for response on setVoltageLevel");
                            return@async ControllerResponse.CommunicationFailure
                        }
                        else {
                            return@async (msg as MessageFromController.OperationConfirmation).response
                        }
                    }.await()
                }
            }
            catch (e: Exception) {
                Log.e(Tag, "Acknowledge timeout! E: ${e.message}")
                return@withContext ControllerResponse.CommandAcknowledgeTimeout
            }
            
            if (ackResult != ControllerResponse.CommandAcknowledge) {
                Log.e(Tag, "expected Command Acknowledge but got: ${ackResult}")
                return@withContext ControllerResponse.CommunicationFailure
            }
            
            val commandResult = try {
                withTimeout(delayToCheckCmdResult.toLong()) {
                    async<ControllerResponse>(Dispatchers.Default) {
                        val msg = mutex.withLock {
                            inputMessagesChannel.receiveCatching()
                                .getOrNull()
                        }
                        
                        if (msg == null) {
                            Log.e(Tag, "MSG is null, waiting for response on setVoltageLevel");
                            return@async ControllerResponse.CommunicationFailure
                        }
                        else {
                            return@async (msg as MessageFromController.OperationConfirmation).response
                        }
                    }.await()
                }
            }
            catch (e: Exception) {
                Log.e(Tag, "Timeout while getting command result. E: ${e.message}")
                return@withContext ControllerResponse.CommandPerformanceTimeout
            }
            
            when (commandResult) {
                ControllerResponse.CommandPerformanceSuccess -> {
                    Log.d(Tag, "Command success!")
                    return@withContext commandResult
                }
                
                ControllerResponse.CommandPerformanceFailure -> {
                    Log.e(Tag, "Command performance Failure!")
                    return@withContext commandResult
                }
                
                else -> {
                    Log.e(Tag, "Unexpected response! Result: $commandResult")
                    return@withContext ControllerResponse.CommunicationFailure
                }
            }
        }
    
    private suspend fun testTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            outputMessagesChannel.send(MeasureAllCommand())
            delay(1000)
        }
    }
    
    private suspend fun rawDataReceiverTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            val result = inputDataCh.receiveCatching()
            
            val bytes = result.getOrNull()
            if (bytes == null) {
                Log.e(Tag, "bytes are null!")
                continue
            }
            
            for (byte in bytes) {
                Log.d("$Tag:RWRT", "$byte")
            }
            
            val msg = try {
                MessageFromController.deserialize(bytes.toList())
            }
            catch (e: Exception) {
                Log.e(Tag, "Error while creating fromControllerMessage: ${e.message}")
                continue
            }
            
            if (msg == null) {
                Log.e(Tag, "Message is null")
                continue
            }
            else {
                Log.d(Tag, "New Msg: ${msg.toString()}")
            }
            
            inputMessagesChannel.send(msg)
        }
    }
    
    private suspend fun inputMessagesHandlerTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            while (inputMessagesChannel.isEmpty) continue
            val deadline = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < deadline) continue
            if (mutex.isLocked || inputMessagesChannel.isEmpty) continue
            
            val result = inputMessagesChannel.receiveCatching()
            Log.d("$Tag:IMHT", "Unhandled message arrived")
            val msg = result.getOrNull()
            if (msg == null) {
                Log.e("${Tag}:IMHT", "Message is null!")
                continue
            }
            
            Log.d("$Tag:IMHT", "New message arrived!")
        }
    }
    
    private suspend fun outputMessagesHandlerTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            val result = outputMessagesChannel.receiveCatching()
            val newMsg = result.getOrNull()
            if (newMsg == null) {
                Log.e("$Tag:OMHT", "new msg is null!")
                continue
            }
            
            Log.d("$Tag:OMHT", "sending new msg")
            
            val bytes = newMsg.serialize()
            bytes.forEach {
                Log.d("$Tag:OMHT", "$it")
            }
            
            outputDataCh.send(bytes)
        }
    }
}
