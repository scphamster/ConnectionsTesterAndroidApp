package com.github.scphamster.bluetoothConnectionsTester.device

import android.os.Message
import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.circuit.SingleBoardVoltages
import com.github.scphamster.bluetoothConnectionsTester.dataLink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ControllerManager(override val dataLink: DeviceLink) : ControllerManagerI,
                                                    CoroutineScope by CoroutineScope(Dispatchers.Default) {
    companion object {
        private const val MESSAGES_CHANNEL_SIZE = 10
        private const val BaseTag = "ControllerManager"
        private const val COMMAND_ACK_TIMEOUT_MS = 200
    }
    
    var isReadyToOperate = false
    var boards = emptyList<IoBoard>()
        private set
    private val Tag: String
        get() = BaseTag + "${dataLink.id}"
    private val inputDataCh = dataLink.inputDataChannel
    private val outputDataCh = dataLink.outputDataChannel
    private val inputMessagesChannel = Channel<MessageFromController>(MESSAGES_CHANNEL_SIZE)
    private val outputMessagesChannel = Channel<MasterToControllerMsg>(MESSAGES_CHANNEL_SIZE)
    private val mutex = Mutex()
    private var voltageLevel = IoBoardsManager.VoltageLevel.Low
    
    init {
        launch { rawDataReceiverTask() }
        launch { inputMessagesHandlerTask() }
        launch { outputMessagesHandlerTask() } //        scope.launch { testTask() }
    }
    
    fun initialize() = launch {
        val response = getAllBoards();
        
        when (response) {
            ControllerResponse.DeviceIsInitializing -> {
                Log.d(Tag, "Controller is initializing answer obtained");
                delay(2000);
                
                val response2 = getAllBoards();
                when (response2) {
                    ControllerResponse.CommandPerformanceSuccess -> Log.d(Tag,
                                                                          "Successful command execution after retry");
                    ControllerResponse.DeviceIsInitializing -> Log.e(Tag, "Device is still initializing after retry!");
                    else -> Log.e(Tag, "Error obtaining all boards: ${response2.name}")
                }
            }
            
            ControllerResponse.CommandPerformanceSuccess -> {
                isReadyToOperate = true;
            }
            
            else -> Log.e(Tag, "Failed to obtain all boards: ${response.name}")
        }
        
        launch {
            Log.d(Tag, "Starting test task")
            testTask()
        }
    }
    
    fun cancelAllJobs() {
        try {
            Log.d(Tag, "Finalize method called!")
            cancel("Object destructed")
        }
        catch (e: Exception) {
            Log.e(Tag, "Exception in finalize method! E: ${e.message}")
        }
    }
    
    override fun startSocket() = async {
        Log.d(Tag, "Starting new controller: ${dataLink.id}")
        dataLink.start()
    }
    
    protected fun finalize() {
        try {
            cancelAllJobs()
        }
        catch (e: Exception) {
            Log.e(Tag, "Error in finalize method : ${e.message}")
        }
    }
    
    private fun testTask() = launch {
        while (isActive) {
            measureAllVoltages()
            delay(1000)
        }
    }
    
    override fun setVoltageLevel(level: IoBoardsManager.VoltageLevel) = async {
        launch(Dispatchers.Default) {
            outputMessagesChannel.send(SetOutputVoltageLevel(level))
        }
        val ackResult = checkAcknowledge()
        if (ackResult != ControllerResponse.CommandAcknowledge) {
            Log.e(Tag, "No ack for set voltage level command! Got: $ackResult")
            return@async ControllerResponse.CommandNoAcknowledge
        }
        return@async checkCommandSuccess(300)
    }
    
    override suspend fun getAllBoards() = withContext<ControllerResponse>(Dispatchers.Default) {
        launch {
            outputMessagesChannel.send(GetBoardsOnline())
        }
        val command_status = checkAcknowledge()
        
        if (command_status != ControllerResponse.CommandAcknowledge) return@withContext command_status
        
        val newBoards = try {
            withTimeout(2000) {
                inputMessagesChannel.receiveCatching()
            }
        }
        catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Timeout while getting all boards! ${e.message}")
            return@withContext ControllerResponse.CommandPerformanceTimeout
        }
        catch (e: Exception) {
            Log.e(Tag, "Unexpected error occurred while getting all boards! ${e.message}")
            return@withContext ControllerResponse.CommandPerformanceFailure
        }.getOrNull() as MessageFromController.Boards?
        
        if (newBoards == null) {
            Log.e(Tag, "New boards are null!")
            return@withContext ControllerResponse.CommandPerformanceFailure
        }
        
        boards = newBoards.boards.map { b -> IoBoard(b) }
        
        return@withContext ControllerResponse.CommandPerformanceSuccess
    }
    
    suspend fun measureAllVoltages() = withContext<SingleBoardVoltages?>(Dispatchers.Default) {
        if (!isReadyToOperate) {
            Log.e(Tag, "Sending commands before controller is ready is prohibited!")
            return@withContext null
        }
        
        outputMessagesChannel.send(MeasureAllVoltages())
        val cmdAck = checkAcknowledge()
        
        if (cmdAck != ControllerResponse.CommandAcknowledge) {
            Log.e(Tag, "No ack for command: Measure all voltages! Received: ${cmdAck.name}")
            return@withContext null
        }
        
        val result = try {
            withTimeout(2000) {
                inputMessagesChannel.receiveCatching()
            }
        }
        catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Measure all voltages command timed out!")
            return@withContext null
        }
        catch (e: Exception) {
            Log.e(Tag, "Unsuccessful retrieval of AllBoardsVoltages! E: ${e.message}")
            return@withContext null
        }.getOrNull()
        
        if (result == null) {
            Log.e(Tag, "Voltages are null!")
            return@withContext null
        }
        
        when (result) {
            is MessageFromController.OperationStatus -> {
                Log.e(Tag, "Unsuccessful measure all retrieval, operation result is ${result.response.name}");
                return@withContext null
            }
            
            is MessageFromController.Voltages -> {
                Log.d(Tag,
                      "Successful retrieval of all voltages!") //                for (board in result.boardsVoltages) {
                //                    for (voltage in board.voltages) {
                //                        Log.d(Tag, "${voltage.pin} : ${voltage.voltage}")
                //                    }
                //                }
            }
            
            else -> {
                Log.e(Tag, "Unexpected message arrived!")
            }
        }
        
        //test
        return@withContext null
    }
    
    private suspend fun checkAcknowledge() = withContext<ControllerResponse>(Dispatchers.Default) {
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
                        return@async (msg as MessageFromController.OperationStatus).response
                    }
                }.await()
            }
        }
        catch (e: Exception) {
            Log.e(Tag, "Acknowledge timeout! E: ${e.message}")
            return@withContext ControllerResponse.CommandAcknowledgeTimeout
        }
        
        if (ackResult == ControllerResponse.CommandAcknowledge) return@withContext ackResult
        
        Log.e(Tag, "expected Command Acknowledge but got: ${ackResult}")
        when (ackResult) {
            ControllerResponse.CommandNoAcknowledge -> return@withContext ackResult
            ControllerResponse.CommandAcknowledgeTimeout -> return@withContext ackResult
            ControllerResponse.CommunicationFailure -> return@withContext ackResult
            ControllerResponse.DeviceIsInitializing -> return@withContext ackResult
            else -> return@withContext ControllerResponse.CommunicationFailure
        }
    }
    
    private suspend fun checkCommandSuccess(delayToCheck: Long) = withContext(Dispatchers.Default) {
        val commandResult = try {
            withTimeout(delayToCheck.toLong()) {
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
                        return@async (msg as MessageFromController.OperationStatus).response
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
