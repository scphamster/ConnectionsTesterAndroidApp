package com.github.scphamster.bluetoothConnectionsTester.device

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.*
import com.github.scphamster.bluetoothConnectionsTester.dataLink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

class ControllerManager(override val dataLink: DeviceLink,
                        val exHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, ex ->
                            Log.e("CM", "Exception : $ex")
                        },
                        val onFatalErrorCallback: () -> Unit) : ControllerManagerI,
                                                                CoroutineScope by CoroutineScope(Dispatchers.Default + exHandler) {
    companion object {
        private const val MESSAGES_CHANNEL_SIZE = 10
        private const val BaseTag = "ControllerManager"
        private const val COMMAND_ACK_TIMEOUT_MS = 1000.toLong()
    }
    
    val initialized = AtomicBoolean(false)
    
    val notReadyMtx = Mutex()
    private var boards = CopyOnWriteArrayList<IoBoard>()
    private var voltageLevel = IoBoardsManager.VoltageLevel.Low
    
    private val Tag: String
        get() = BaseTag + ":${dataLink.id}"
    private val inputDataCh = dataLink.inputDataChannel
    private val outputDataCh = dataLink.outputDataChannel
    private val inputMessagesChannel = Channel<MessageFromController>(MESSAGES_CHANNEL_SIZE)
    private val inputChannels = mutableMapOf<KClass<*>, Channel<MessageFromController>>()
    private val outputMessagesChannel = Channel<MasterToControllerMsg>(MESSAGES_CHANNEL_SIZE)
    private val inputMessagesChannelMutex = Mutex()
    
    init {
        launch { rawDataReceiverTask() }
        launch { inputMessagesHandlerTask() }
        launch { outputMessagesHandlerTask() }
        launch {
            Log.d(Tag, "Initialization controller manager")
            
            launch {
                runDataLink()
            }
            
            while (!dataLink.isReady.get()) continue
            
            Log.d(Tag, "Socket started, getting all boards!")
            
            //todo: add timeout
            try {
                initialize()
            }
            catch (e: Exception) {
                Log.e(Tag, "Exception during controller boards obtainment! ${e.message}")
                onFatalErrorCallback()
            }
            
            initialized.set(true);
            Log.d(Tag, "Controller initialized!")
            
        }
    }
    
    override fun cancelAllJobs() {
        initialized.set(false)
        
        try {
            dataLink.stop()
            cancel("Object destructed")
        }
        catch (e: Exception) {
            Log.e(Tag, "CancelAllJobs exception caught: ${e.message}")
        }
    }
    
    override fun measureAllVoltages() = async<Array<SingleBoardVoltages>?> {
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
            withTimeout(MessageFromController.Voltages.TIME_TO_WAIT_FOR_RESULT_MS) {
                val type = MessageFromController.Voltages::class
                while (!inputChannels.containsKey(type)) continue
                
                val voltages = inputChannels[type]?.receiveCatching()
                    ?.getOrNull() as MessageFromController.Voltages?
                
                if (voltages == null) {
                    Log.e(Tag, "voltage table arrived as null")
                    null
                }
                else {
                    voltages.boardsAndVoltages.map { boardAndVoltages ->
                        val pinsVoltages = boardAndVoltages.pinsAndVoltages.map { pinAndVoltage ->
                            Pair(pinAndVoltage.pin.toInt(), pinAndVoltage.voltage)
                        }
                        
                        SingleBoardVoltages(boardAndVoltages.boardId.toInt(), pinsVoltages.toTypedArray())
                    }.toTypedArray()
                }
            }
        }
        catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Measure all voltages command timed out!")
            return@async null
        }
        catch (e: Exception) {
            Log.e(Tag, "Unsuccessful retrieval of AllBoardsVoltages! E: ${e.message}")
            return@async null
        }
        
        if (result == null) {
            Log.e(Tag, "Voltages are null!")
            return@async null
        }
        
        if (result.size != boards.size) {
            Log.e(Tag, "Not all boards voltages are available!")
        }
        
        Log.d(Tag, "Successful retrieval of all voltages! Size: ${result.size}")
        
        return@async result
    }
    
    override fun stop() {
        cancelAllJobs()
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
        outputMessagesChannel.send(GetBoardsOnline())
        
        val command_status = checkAcknowledge().await()
        
        if (command_status != ControllerResponse.CommandAcknowledge) return@async command_status
        
        val newBoards = try {
            withTimeout(GetBoardsOnline.RESULT_TIMEOUT_MS) {
                while (!inputChannels.containsKey(MessageFromController.Boards::class)) continue
                
                inputChannels[MessageFromController.Boards::class]?.receiveCatching()
                    ?.getOrNull() as MessageFromController.Boards?
            }
        }
        catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Timeout while getting all boards! $e")
            return@async ControllerResponse.CommandPerformanceTimeout
        }
        catch (e: Exception) {
            Log.e(Tag, "Unexpected error occurred while getting all boards! $e")
            return@async ControllerResponse.CommandPerformanceFailure
        }
        
        if (newBoards == null) {
            Log.e(Tag, "New boards are null!")
            return@async ControllerResponse.CommandPerformanceFailure
        }
        
        if (newBoards.boardsInfo.size == 0) {
            Log.e(Tag, "Controller without boards!")
            return@async ControllerResponse.DeviceIsInitializing
        }
        
        if (addNewBoards(newBoards)) {
            Log.d(Tag, "New boards obtained! Boards count :${boards.size}")
        }
        
        return@async ControllerResponse.CommandPerformanceSuccess
    }
    
    /**
     * @brief fast connections check method if there is only one ControllerManager connected
     */
    
    override suspend fun initialize() = withContext(Dispatchers.Default) {
        while (isActive) {
            val response = updateAvailableBoards().await();
            if (response == ControllerResponse.DeviceIsInitializing) {
                delay(1000)
                continue
            }
            else if (response != ControllerResponse.CommandPerformanceSuccess) {
                Log.e(Tag, "boards update failed with result : $response")
            }
            else return@withContext
        }
    }
    
    override suspend fun getBoards() = notReadyMtx.withLock<Array<IoBoard>> {
        return boards.toTypedArray()
    }
    
    override suspend fun setVoltageAtPin(pinAffinityAndId: PinAffinityAndId): ControllerResponse {
        outputMessagesChannel.send(SetVoltageAtPin(pinAffinityAndId))
        val ack = checkAcknowledge().await()
        
        if (ack != ControllerResponse.CommandAcknowledge) Log.e(Tag,
                                                                "Failed to set voltage at pin: ${pinAffinityAndId}")
        
        return ack
    }
    
    override suspend fun checkSingleConnection(pinAffinityAndId: PinAffinityAndId,
                                               connectionsChannel: Channel<SimpleConnectivityDescription>) =
        withContext(Dispatchers.Default) {
            outputMessagesChannel.send(FindConnection(pinAffinityAndId))
            
            val ack = checkAcknowledge().await()
            
            if (ack != ControllerResponse.CommandAcknowledge) {
                Log.e(Tag, "Check single connection failed: no acknowledge $ack")
                return@withContext
            }
            
            val msg = catchConnectivityMsg()
            
            if (msg == null) {
                Log.e(Tag, "Check connectivity failed: connectivity msg is null")
                return@withContext
            }
            
            Log.d(Tag,
                  "Connections info arrived for pin: ${msg.masterPin}, connections number: ${msg.connections.size}")
            connectionsChannel.send(SimpleConnectivityDescription(msg.masterPin, msg.connections))
        }
    
    override suspend fun checkConnectionsForLocalBoards(connectionsChannel: Channel<SimpleConnectivityDescription>) =
        withContext(Dispatchers.Default) /* overall operation result */ {
            outputMessagesChannel.send(FindConnection())
            
            val ack = checkAcknowledge().await()
            
            if (ack != ControllerResponse.CommandAcknowledge) {
                Log.e(Tag, "No ack for find all connections command")
                return@withContext
            }
            
            var pinCounter = 0
            repeat(boards.size * IoBoard.PINS_COUNT_ON_SINGLE_BOARD) {
                val msg = catchConnectivityMsg()
                
                if (msg == null) {
                    Log.e(Tag, "Check connections on single controller: msg is null")
                    return@withContext
                }
                
                Log.d(Tag, "Connections for pin: ${msg.masterPin}")
                connectionsChannel.send(SimpleConnectivityDescription(msg.masterPin, msg.connections))
                pinCounter++
                
                Log.d(Tag, "All pins connectivity info arrived!")
            }
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
                    while (!inputChannels.containsKey(MessageFromController.OperationStatus::class)) continue
                    val msg = inputChannels[MessageFromController.OperationStatus::class]?.receiveCatching()
                        ?.getOrNull()
                    
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
        catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Acknowledge timeout! E: ${e.message}")
            return@async ControllerResponse.CommandAcknowledgeTimeout
        }
        catch (e: Exception) {
            Log.e(Tag, "Acknowledge failure, exception: $e")
            return@async ControllerResponse.CommunicationFailure
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
                    val msg = inputMessagesChannelMutex.withLock {
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
            val Tag = Tag + ":IMHT"
            
            val msg = inputMessagesChannel.receiveCatching()
                .getOrNull()
            if (msg == null) {
                Log.e(Tag, "Unhandled message is null!")
                continue
            }
            
            val type = msg::class
            
            if (inputChannels.containsKey(type)) {
                Log.d(Tag, "new msg sent to channel of type: ${type.simpleName}")
                inputChannels[type]?.send(msg)
            }
            else {
                Log.d(Tag, "new channel created with class: ${type.simpleName}")
                inputChannels[type] = Channel<MessageFromController>(Channel.UNLIMITED)
                inputChannels[type]?.send(msg)
            }
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
    
    private suspend fun runDataLink() = withContext(Dispatchers.Default) {
        Log.d(Tag, "Starting new Controller DataLink: ${dataLink.id}")
        
        val dataLinkJob = async {
            dataLink.run()
        }
        
        try {
            dataLinkJob.await()
            Log.e(Tag, "DataLink ended its job!")
        }
        catch (e: SocketTimeoutException) {
            Log.e(Tag, "socket connection timeout")
        }
        catch (e: CancellationException) {
            Log.e(Tag, "DataLink task canceled due to: ${e.message} : ${e.cause}")
        }
        catch (e: Exception) {
            Log.e(Tag, "Unexpected exception caught from DataLink: ${e.message}")
        } finally {
            onFatalErrorCallback()
        }
        return@withContext
        
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
    
    private suspend fun catchConnectivityMsg(): MessageFromController.Connectivity? {
        val msg = try {
            withTimeout(FindConnection.SINGLE_PIN_RESULT_TIMEOUT_MS) {
                val type = MessageFromController.Connectivity::class
                
                while (!inputChannels.containsKey(type)) continue
                
                inputChannels[type]?.receiveCatching()
                    ?.getOrNull() as MessageFromController.Connectivity?
            }
        }
        catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Single pin results timeout!")
            return null
        }
        catch (e: Exception) {
            Log.e(Tag, "Unexpected exception while waiting for single pin connectivity results: ${e.message}")
            return null
        }
        
        if (msg == null) Log.e(Tag, "Connectivity info msg is null")
        
        return msg
    }
}
