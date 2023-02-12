package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.dataLink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class ControllerManager(scope: CoroutineScope, val socket: WorkSocket) {
    companion object {
        private const val MESSAGES_CHANNEL_SIZE = 10
        private const val Tag = "ControllerManager"
    }
    
    private val inputDataCh = socket.inputDataChannel
    private val outputDataCh = socket.outputDataChannel
    private val inputMessagesChannel = Channel<MessageFromController>(MESSAGES_CHANNEL_SIZE)
    private val outputMessagesChannel = Channel<MTC>(MESSAGES_CHANNEL_SIZE)
    
    var boards = emptyList<IoBoard>()
        private set
    
    private var voltageLevel = IoBoardsManager.VoltageLevel.Low
    
    init {
        scope.launch { rawDataReceiverTask() }
        scope.launch { inputMessagesHandlerTask() }
        scope.launch { testTask() }
        scope.launch { outputMessagesHandlerTask() }
    }
    
    fun initialize(voltageLevel: IoBoardsManager.VoltageLevel) {
    }
    
    private suspend fun testTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            Log.d(Tag, "Milestone")
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
                Log.d(Tag, "ctl: $byte")
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
            val result = inputMessagesChannel.receiveCatching()
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
