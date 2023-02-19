package com.github.scphamster.bluetoothConnectionsTester.device

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoardInternalParameters
import com.github.scphamster.bluetoothConnectionsTester.circuit.SimpleConnectivityDescription
import com.github.scphamster.bluetoothConnectionsTester.circuit.SingleBoardVoltages
import com.github.scphamster.bluetoothConnectionsTester.dataLink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class ControllerManager(override val dataLink: DeviceLink) : ControllerManagerI,
                                                             CoroutineScope by CoroutineScope(Dispatchers.Default) {
    companion object {
        private const val MESSAGES_CHANNEL_SIZE = 10
        private const val BaseTag = "ControllerManager"
        private const val COMMAND_ACK_TIMEOUT_MS = 1000.toLong()
    }
    
    //val id: Int //todo: implement
    
    val initialized = AtomicBoolean(false)
    
    val notReadyMtx = Mutex()
    private var boards = CopyOnWriteArrayList<IoBoard>()
    private var voltageLevel = IoBoardsManager.VoltageLevel.Low
    
    private val Tag: String
        get() = BaseTag + ":${dataLink.id}"
    private val inputDataCh = dataLink.inputDataChannel
    private val outputDataCh = dataLink.outputDataChannel
    private val inputMessagesChannel = Channel<MessageFromController>(MESSAGES_CHANNEL_SIZE)
    private val outputMessagesChannel = Channel<MasterToControllerMsg>(MESSAGES_CHANNEL_SIZE)
    private val mutex = Mutex()
    
    init {
        launch { rawDataReceiverTask() }
        launch { inputMessagesHandlerTask() }
        launch { outputMessagesHandlerTask() } //        scope.launch { testTask() }
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
    
    fun measureAllVoltages() = async<SingleBoardVoltages?> {
        if (!initialized.get()) {
            Log.e(Tag, "Sending commands before controller is ready is prohibited!")
            return@async null
        }
        
        outputMessagesChannel.send(MeasureAllVoltages())
        val cmdAck = checkAcknowledge().await()
        
        if (cmdAck != ControllerResponse.CommandAcknowledge) {
            Log.e(Tag, "No ack for command: Measure all voltages! Received: ${cmdAck.name}")
            return@async null
        }
        
        val result = try {
            withTimeout(2000) {
                inputMessagesChannel.receiveCatching()
            }
        }
        catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Measure all voltages command timed out!")
            return@async null
        }
        catch (e: Exception) {
            Log.e(Tag, "Unsuccessful retrieval of AllBoardsVoltages! E: ${e.message}")
            return@async null
        }.getOrNull()
        
        if (result == null) {
            Log.e(Tag, "Voltages are null!")
            return@async null
        }
        
        when (result) {
            is MessageFromController.OperationStatus -> {
                Log.e(Tag, "Unsuccessful measure all retrieval, operation result is ${result.response.name}");
                return@async null
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
        return@async null
    }
    
    //faster function to check connectivity if there is only one controller online
    suspend fun checkConnectionsForLocalBoards(connectionsChannel: Channel<SimpleConnectivityDescription>) =
        withContext(Dispatchers.Default) /* overall operation result */ {
            outputMessagesChannel.send(FindAllConnections())
            
            val ack = checkAcknowledge().await()
            
            if (ack != ControllerResponse.CommandAcknowledge) {
                Log.e(Tag, "No ack for find all connections command")
                return@withContext
            }
            var pinCounter = 0
            mutex.withLock {
                repeat(boards.size * IoBoard.pinsCountOnSingleBoard) {
                    val msg = try {
                        withTimeout(FindAllConnections.SINGLE_PIN_RESULT_TIMEOUT_MS) {
                            inputMessagesChannel.receiveCatching()
                                .getOrNull()
                        }
                    }
                    catch (e: TimeoutCancellationException) {
                        Log.e(Tag, "Single pin results timeout!")
                        return@withContext
                    }
                    catch (e: Exception) {
                        Log.e(Tag,
                              "Unexpected exception while waiting for single pin connectivity results: ${e.message}")
                        return@withContext
                    }
                    
                    if (msg == null) {
                        Log.e(Tag, "Msg is null while getting connectivity info!")
                        return@withContext
                    }
                    
                    when (msg) {
                        is MessageFromController.Connectivity -> {
                            Log.d(Tag, "Connections for pin: ${msg.masterPin}")
                            
                            connectionsChannel.send(SimpleConnectivityDescription(msg.masterPin, msg.connections))
                        }
                        
                        is MessageFromController.OperationStatus -> {
                            Log.e(Tag,
                                  "Operation status message arrived while getting connectivity info: ${msg.response}")
                            return@withContext
                        }
                        
                        else -> {
                            Log.e(Tag, "Unexpected message obtained while getting connectivity info")
                            return@withContext
                        }
                    }
                    
                    pinCounter++
                }
                
                Log.d(Tag, "All pins connectivity info arrived!")
                
            } //wait for all pins info
        }
    
    suspend fun getBoards() = notReadyMtx.withLock<Array<IoBoard>> {
        return boards.toTypedArray()
    }
    
    override fun initialize() = launch {
        while (true) {
            val response = updateAvailableBoards().await();
            if (response == ControllerResponse.DeviceIsInitializing) {
                delay(1000)
                continue
            }
            else if (response != ControllerResponse.CommandPerformanceSuccess) {
                Log.e(Tag, "boards update failed with result : $response")
            }
            else {
                initialized.set(true);
                return@launch
            }
        }
    }
    
    override fun startSocket() = async {
        Log.d(Tag, "Starting new controller: ${dataLink.id}")
        dataLink.start()
    }
    
    override fun setVoltageLevel(level: IoBoardsManager.VoltageLevel): Deferred<ControllerResponse> = async {
        launch(Dispatchers.Default) {
            outputMessagesChannel.send(SetOutputVoltageLevel(level))
        }
        val ackResult = checkAcknowledge().await()
        if (ackResult != ControllerResponse.CommandAcknowledge) {
            Log.e(Tag, "No ack for set voltage level command! Got: $ackResult")
            return@async ControllerResponse.CommandNoAcknowledge
        }
        return@async checkCommandSuccess(300).await()
    }
    
    override fun updateAvailableBoards() = async<ControllerResponse> {
        launch {
            outputMessagesChannel.send(GetBoardsOnline())
        }
        val command_status = checkAcknowledge().await()
        
        if (command_status != ControllerResponse.CommandAcknowledge) return@async command_status
        
        val newBoards = try {
            withTimeout(GetBoardsOnline.RESULT_TIMEOUT_MS) {
                inputMessagesChannel.receiveCatching()
            }
        }
        catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Timeout while getting all boards! ${e.message}")
            return@async ControllerResponse.CommandPerformanceTimeout
        }
        catch (e: Exception) {
            Log.e(Tag, "Unexpected error occurred while getting all boards! ${e.message}")
            return@async ControllerResponse.CommandPerformanceFailure
        }.getOrNull() as MessageFromController.Boards?
        
        if (newBoards == null) {
            Log.e(Tag, "New boards are null!")
            return@async ControllerResponse.CommandPerformanceFailure
        }
        
        if (addNewBoards(newBoards)) {
            Log.d(Tag, "New boards obtained! Boards count :${boards.size}")
            if (boards.size == 0) {
                Log.e(Tag, "Controller without boards!")
            }
        }
        
        return@async ControllerResponse.CommandPerformanceSuccess
    }
    
    protected fun finalize() {
        try {
            cancelAllJobs()
        }
        catch (e: Exception) {
            Log.e(Tag, "Error in finalize method : ${e.message}")
        }
    }
    
    private suspend fun addNewBoards(newBoards: MessageFromController.Boards) = notReadyMtx.withLock {
        boards.clear()
        return@withLock boards.addAll(newBoards.boardsInfo.map { b ->
            IoBoard(b.address.toInt(),
                    internalParams = IoBoardInternalParameters(b.internals.inR1,
                                                               b.internals.outR1,
                                                               b.internals.inR2,
                                                               b.internals.outR2,
                                                               b.internals.shuntR,
                                                               b.internals.outVLow,
                                                               b.internals.outVHigh
            
                    ),
                    voltageLevel = b.voltageLevel,
                    belongsToController = WeakReference(this))
        })
    }
    
    private fun testTask() = launch {
        while (isActive) {
            measureAllVoltages()
            delay(1000)
        }
    }
    
    private fun checkAcknowledge() = async<ControllerResponse> {
        val ackResult = try {
            withTimeout(COMMAND_ACK_TIMEOUT_MS.toLong()) {
                async<ControllerResponse>(Dispatchers.Default) {
                    val msg = mutex.withLock {
                        inputMessagesChannel.receiveCatching()
                            .getOrNull()
                    }
                    
                    if (msg == null) {
                        Log.e(Tag, "Was waiting for acknowledge but got NULL msg!");
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
            return@async ControllerResponse.CommandAcknowledgeTimeout
        }
        
        if (ackResult == ControllerResponse.CommandAcknowledge) return@async ackResult
        
        Log.e(Tag, "expected Command Acknowledge but got: ${ackResult}")
        when (ackResult) {
            ControllerResponse.CommandNoAcknowledge -> return@async ackResult
            ControllerResponse.CommandAcknowledgeTimeout -> return@async ackResult
            ControllerResponse.CommunicationFailure -> return@async ackResult
            ControllerResponse.DeviceIsInitializing -> return@async ackResult
            else -> return@async ControllerResponse.CommunicationFailure
        }
    }
    
    private fun checkCommandSuccess(delayToCheck: Long) = async(Dispatchers.Default) {
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
            return@async ControllerResponse.CommandPerformanceTimeout
        }
        
        when (commandResult) {
            ControllerResponse.CommandPerformanceSuccess -> {
                Log.d(Tag, "Command success!")
                return@async commandResult
            }
            
            ControllerResponse.CommandPerformanceFailure -> {
                Log.e(Tag, "Command performance Failure!")
                return@async commandResult
            }
            
            else -> {
                Log.e(Tag, "Unexpected response! Result: $commandResult")
                return@async ControllerResponse.CommunicationFailure
            }
        }
    }
    
    private fun rawDataReceiverTask() = async {
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
                MessageFromController.deserialize(bytes.iterator())
            }
            catch (e: Exception) {
                Log.e("$Tag:RWRT", "Error while creating fromControllerMessage: ${e.message}")
                for ((index, byte) in bytes.withIndex()) {
                    Log.e("$Tag:RWRT", "$index:${byte.toUByte()}")
                }
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
    
    private fun inputMessagesHandlerTask() = async {
        while (isActive) {
            while (inputMessagesChannel.isEmpty) continue
            val deadline = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < deadline) continue
            if (mutex.isLocked || inputMessagesChannel.isEmpty) continue
            
            val msg = inputMessagesChannel.receiveCatching()
                .getOrNull()
            if (msg == null) {
                Log.e("${Tag}:IMHT", "Unhandled message is null!")
                continue
            }
            
            Log.d("$Tag:IMHT", "New unhandled message arrived!")
        }
    }
    
    private fun outputMessagesHandlerTask() = async {
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
