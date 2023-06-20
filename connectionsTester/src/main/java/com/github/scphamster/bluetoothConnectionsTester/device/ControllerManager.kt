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
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

//todo: create command to check internals (e.g. controller fw version)
//todo: add permanent check of online boards
class ControllerManager(
    val uuid: UUID,
    override val dataLink: DeviceLink,
    private val scope: CoroutineScope,
    private val outputVoltageLevel: IoBoardsManager.VoltageLevel,
    val onStateChangeCallback: (prevState: ControllerManagerI.State, s: ControllerManagerI.State) -> Unit,
    val onFatalErrorCallback: () -> Unit
) : ControllerManagerI {
    companion object {
        private const val MESSAGES_CHANNEL_SIZE = 10
        private const val BaseTag = "ControllerManager"
        private const val COMMAND_ACK_TIMEOUT_MS = 1000.toLong()
    }

    private val boardsMutex = Mutex()

    var state: ControllerManagerI.State = ControllerManagerI.State.Initializing
        private set(newState) {
            val prevState = field
            field = newState
            onStateChangeCallback(prevState, newState)
            Log.d(Tag, "Setting state to ${field.toString()}!")
        }
    var voltageLevel = IoBoardsManager.VoltageLevel.Low
        private set

    private var boards = CopyOnWriteArrayList<IoBoard>()

    private val Tag: String
        get() = BaseTag + ":${dataLink.id}"
    private val inputDataCh = dataLink.inputDataChannel
    private val outputDataCh = dataLink.outputDataChannel
    private val inputMessagesChannel = Channel<MessageFromController>(MESSAGES_CHANNEL_SIZE)
    private val inputChannels = mutableMapOf<KClass<*>, Channel<MessageFromController>>()
    private val outputMessagesChannel = Channel<MasterToControllerMsg>(MESSAGES_CHANNEL_SIZE)
    private val inputMessagesChannelMutex = Mutex()

    private var allJobs: Deferred<Unit>

    init {
        allJobs = scope.async(Dispatchers.Default) {
            val jobs = mutableListOf<Deferred<Unit>>()

            jobs.add(scope.async {
                // task to receive data in form of bytes from the controller, data is transferred through the Channel from WorkSocket
                rawDataReceiverTask()
            })
            jobs.add(scope.async {
                // messages deserialized from bytes in rawDataReceiverTask sent to Chanel received here
                inputMessagesHandlerTask()
            })
            jobs.add(scope.async {
                // messages to be sent to controller are sent to Chanel handled here
                outputMessagesHandlerTask()
            })
            jobs.add(scope.async {
                // handler of raw data flow (in and out)
                runDataLink()
            })
            jobs.add(scope.async {
                // receiver task solely for the KeepAlive type of messages
                keepAliveReceiver()
            })
            jobs.add(scope.async {
                Log.d(Tag, "Initialization controller manager")

                while (!dataLink.isReady.get()) {
                    yield()
                    continue
                }

                initialize()
                Log.d(Tag, "Initialized!")
                // disabled until issue with this resolved
                // checkLatency()

                state = ControllerManagerI.State.Operating
            })

            try {
                jobs.awaitAll()
            } catch (e: CancellationException) {
                Log.d(Tag, "Controllers jobs were cancelled due to: $e")
            } catch (e: Exception) {
                Log.e(Tag, "Controllers jobs were cancelled due to unexpected exception: $e")
            } finally {
                onFatalErrorCallback()
            }

        }
    }

    override fun stop() {
        cancelAllJobs()
    }

    override fun cancelAllJobs() {
        state = ControllerManagerI.State.Failed

        try {
            dataLink.stop()
            allJobs.cancel("Cancel requested")
        } catch (e: Exception) {
            Log.e(Tag, "CancelAllJobs exception caught: ${e.message}")
        }
    }

    override suspend fun initialize() = withContext(Dispatchers.Default) {
        while (isActive) {
            val response = updateAvailableBoards()
            if (response == ControllerResponse.DeviceIsInitializing) {
                delay(1000)
                continue
            }

            if (response != ControllerResponse.CommandPerformanceSuccess) {
                onFatalErrorCallback
                return@withContext
            } else break
        }

        if (boards.size != 0) return@withContext

        while (isActive) {
            val response = updateAvailableBoards()

            if (response != ControllerResponse.CommandPerformanceSuccess) {
                Log.e(Tag, "boards update failed with result : $response")
                onFatalErrorCallback()
                return@withContext
            } else if (boards.size == 0) {
                delay(1000)
                continue
            } else break
        }

        if (setVoltageLevel(outputVoltageLevel) != ControllerResponse.CommandPerformanceSuccess) {
            Log.e(Tag, "Set voltage failed in initialization of controller! Deleting controller!")
            onFatalErrorCallback()
        }
    }

    override suspend fun measureAllVoltages() = withContext<Array<SingleBoardVoltages>?>(Dispatchers.Default) {
        if (state != ControllerManagerI.State.Operating) {
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
            withTimeout(MessageFromController.Voltages.TIME_TO_WAIT_FOR_RESULT_MS) {
                val type = MessageFromController.Voltages::class
                while (!inputChannels.containsKey(type)) continue

                val voltages = inputChannels[type]?.receiveCatching()
                    ?.getOrNull() as MessageFromController.Voltages?


                if (voltages == null) {
                    Log.e(Tag, "voltage table arrived as null")
                    null
                } else {
                    voltages.boardsAndVoltages.map { boardAndVoltages ->
                        val pinsVoltages = boardAndVoltages.pinsAndVoltages.map { pinAndVoltage ->
                            Pair(pinAndVoltage.pin.toInt(), pinAndVoltage.voltage)
                        }

                        SingleBoardVoltages(boardAndVoltages.boardId.toInt(), pinsVoltages.toTypedArray())
                    }
                        .toTypedArray()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Measure all voltages command timed out!")

            val operationResult = withTimeout(MessageFromController.OperationStatus.MESSAGE_OBTAINMENT_TIMEOUT_MS) {
                inputChannels[MessageFromController.OperationStatus::class]?.receiveCatching()
                    ?.getOrNull() as MessageFromController.OperationStatus?
            }

            if (operationResult != null) {
                if (operationResult.response != ControllerResponse.CommandPerformanceFailure) {
                    Log.e(Tag, "Measure all failed and unexpected operation result obtained!")
                    return@withContext null
                } else {
                    Log.e(Tag, "Measure all failed, fail confirmation arrived, fail is on controller side")
                    return@withContext null
                }
            } else {
                Log.e(Tag, "Measure all failed but operation result obtainment timed out!")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(Tag, "Unsuccessful retrieval of AllBoardsVoltages! E: ${e.message}")
            return@withContext null
        }

        if (result == null) {
            Log.e(Tag, "Voltages are null!")
            return@withContext null
        }

        if (result.size != boards.size) {
            Log.e(Tag, "Not all boards voltages are available!")
        }

        Log.d(Tag, "Successful retrieval of all voltages! Size: ${result.size}")

        return@withContext result
    }

    override suspend fun setVoltageLevel(level: IoBoardsManager.VoltageLevel) =
        withContext<ControllerResponse>(Dispatchers.Default) {
            outputMessagesChannel.send(SetOutputVoltageLevel(level))
            val ackResult = checkAcknowledge()
            if (ackResult != ControllerResponse.CommandAcknowledge) {
                Log.e(Tag, "No ack for set voltage level command! Got: $ackResult")
                return@withContext ControllerResponse.CommandNoAcknowledge
            }

            val response = checkCommandSuccess(SetOutputVoltageLevel.RESULT_OBTAINMENT_TIMEOUT_MS)

            if (response == ControllerResponse.CommandPerformanceSuccess) {
                voltageLevel = level
                Log.d(Tag, "Voltage setting to $level succeeded")
                return@withContext ControllerResponse.CommandPerformanceSuccess
            } else {
                Log.e(Tag, "Votlage setting to $level failed")
                return@withContext response
            }
        }

    override suspend fun updateAvailableBoards() = withContext<ControllerResponse>(Dispatchers.Default) {
        state = ControllerManagerI.State.GettingBoards

        val resultObtainmentTimeoutValue = if (boards.size == 0) {
            outputMessagesChannel.send(GetBoardsOnline(true))
            GetBoardsOnline.RESULT_TIMEOUT_WITH_RESCAN_MS
        } else {
            outputMessagesChannel.send(GetBoardsOnline(false))
            GetBoardsOnline.RESULT_TIMEOUT_MS
        }

        val command_status = checkAcknowledge()

        if (command_status != ControllerResponse.CommandAcknowledge) {
            Log.e(Tag, "No ack for GetBoards command: $command_status")
            return@withContext command_status
        }

        val newBoards = try {
            withTimeout(resultObtainmentTimeoutValue) {
                while (!inputChannels.containsKey(MessageFromController.Boards::class)) {
                    yield()
                    continue
                }

                inputChannels[MessageFromController.Boards::class]?.receiveCatching()
                    ?.getOrNull() as MessageFromController.Boards?
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Timeout while getting all boards! $e")
            return@withContext ControllerResponse.CommandPerformanceTimeout
        } catch (e: Exception) {
            Log.e(Tag, "Unexpected error occurred while getting all boards! $e")
            return@withContext ControllerResponse.CommandPerformanceFailure
        }

        if (newBoards == null) {
            Log.e(Tag, "New boards are null!")
            return@withContext ControllerResponse.CommandPerformanceFailure
        }

        if (newBoards.boardsInfo.size == 0) {
            Log.e(Tag, "Controller without boards!")
        }

        if (addNewBoards(newBoards)) {
            Log.d(Tag, "New boards obtained! Boards count :${boards.size}")
        }

        return@withContext ControllerResponse.CommandPerformanceSuccess
    }

    override suspend fun getBoards() = boardsMutex.withLock<Array<IoBoard>> {
        return boards.toTypedArray()
    }

    override suspend fun setVoltageAtPin(pinAffinityAndId: PinAffinityAndId): ControllerResponse {
        if (state != ControllerManagerI.State.Operating) {
            Log.e(
                Tag,
                "Command setVoltageAtPin invoked while controller is not in Operating state, current state: $state"
            )
            return ControllerResponse.CommandPerformanceFailure
        }

        outputMessagesChannel.send(SetVoltageAtPin(pinAffinityAndId))
        val ack = checkAcknowledge()

        if (ack != ControllerResponse.CommandAcknowledge) Log.e(
            Tag,
            "Failed to set voltage at pin: ${pinAffinityAndId}"
        )

        return ack
    }

    override suspend fun checkSingleConnection(
        pinAffinityAndId: PinAffinityAndId,
        connectionsChannel: Channel<SimpleConnectivityDescription>
    ) =
        withContext(Dispatchers.Default) {
            if (state != ControllerManagerI.State.Operating) {
                Log.e(
                    Tag,
                    "Command ${object {}.javaClass.enclosingMethod?.name} invoked while controller is not in Operating state, current state: $state"
                )
                return@withContext
            }

            outputMessagesChannel.send(FindConnection(pinAffinityAndId))

            val ack = checkAcknowledge()

            if (ack != ControllerResponse.CommandAcknowledge) {
                Log.e(Tag, "Check single connection failed: no acknowledge $ack")
                return@withContext
            }

            val msg = catchConnectivityMsg()

            if (msg == null) {
                Log.e(Tag, "Check connectivity failed: connectivity msg is null")
                return@withContext
            }

            Log.d(
                Tag,
                "Connections info arrived for pin: ${msg.masterPin}, connections number: ${msg.connections.size}"
            )
            connectionsChannel.send(SimpleConnectivityDescription(msg.masterPin, msg.connections))
        }

    override suspend fun checkConnectionsForLocalBoards(connectionsChannel: Channel<SimpleConnectivityDescription>) =
        withContext(Dispatchers.Default) /* overall operation result */ {
            if (state != ControllerManagerI.State.Operating) {
                Log.e(
                    Tag,
                    "Command ${object {}.javaClass.enclosingMethod?.name} invoked while controller is not in Operating state, current state: $state"
                )
                return@withContext
            }

            outputMessagesChannel.send(FindConnection())

            val ack = checkAcknowledge()

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

    override suspend fun disableOutput(): ControllerResponse {
        if (state != ControllerManagerI.State.Operating) {
            Log.e(
                Tag,
                "Command ${object {}.javaClass.enclosingMethod?.name} invoked while controller is not in Operating state, current state: $state"
            )
            return ControllerResponse.CommandPerformanceFailure
        }

        outputMessagesChannel.send(DisableOutput())
        return checkAcknowledge()
    }

    private suspend fun checkAcknowledge() = withContext<ControllerResponse>(Dispatchers.Default) {
        val ackResult = try {
            withTimeout(COMMAND_ACK_TIMEOUT_MS.toLong()) {
                while (!inputChannels.containsKey(MessageFromController.OperationStatus::class)) continue
                val msg = inputChannels[MessageFromController.OperationStatus::class]?.receiveCatching()
                    ?.getOrNull()

                if (msg == null) {
                    Log.e(Tag, "Was waiting for acknowledge but got NULL msg!");
                    return@withTimeout ControllerResponse.CommunicationFailure
                } else {
                    return@withTimeout (msg as MessageFromController.OperationStatus).response
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Acknowledge timeout! E: ${e.message}")
            return@withContext ControllerResponse.CommandAcknowledgeTimeout
        } catch (e: Exception) {
            Log.e(Tag, "Acknowledge failure, exception: $e")
            return@withContext ControllerResponse.CommunicationFailure
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
                val msg = inputMessagesChannelMutex.withLock {
                    inputMessagesChannel.receiveCatching()
                        .getOrNull()
                }

                if (msg == null) {
                    Log.e(Tag, "MSG is null, waiting for response on setVoltageLevel");
                    return@withTimeout ControllerResponse.CommunicationFailure
                } else {
                    return@withTimeout (msg as MessageFromController.OperationStatus).response
                }
            }
        } catch (e: Exception) {
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
        val Tag = {
            "$Tag:RDRT"
        }

        while (isActive) {
            val bytes = inputDataCh.receiveCatching().getOrNull() ?: continue

            for (byte in bytes) {
                Log.v(Tag(), "${byte.toUByte()}")
            }

            val msg = try {
                MessageFromController.deserialize(bytes.iterator())
            } catch (e: Exception) {
                Log.e(Tag(), "Error while creating MessageFromController: ${e.message}")

                for ((index, byte) in bytes.withIndex()) {
                    Log.e(Tag(), "$index:${byte.toUByte()}")
                }
                continue
            }

            if (msg == null) {
                Log.e(Tag(), "Message is null")
                continue
            } else {
                Log.d(Tag(), "New Msg: $msg")
            }

            inputMessagesChannel.send(msg)
        }
    }

    private suspend fun inputMessagesHandlerTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            val Tag = { "$Tag:IMHT" }

            val msg = inputMessagesChannel.receiveCatching().getOrNull()

            if (msg == null) {
                Log.e(Tag(), "Unhandled message is null!")
                continue
            }

            val type = msg::class

            if (inputChannels.containsKey(type)) {
                Log.d(Tag(), "new msg sent to channel of type: ${type.simpleName}")
                inputChannels[type]?.send(msg)
            } else {
                Log.d(Tag(), "new channel created with class: ${type.simpleName}")
                inputChannels[type] = Channel<MessageFromController>(Channel.UNLIMITED)
                inputChannels[type]?.send(msg)
            }
        }
    }

    private suspend fun outputMessagesHandlerTask() = withContext(Dispatchers.Default) {
        val Tag = { "$Tag:OMHT" }

        while (isActive) {
            val newMsg = outputMessagesChannel.receiveCatching().getOrNull()
            if (newMsg == null) {
                Log.e(Tag(), "new msg is null!")
                continue
            }

            Log.d(Tag(), "sending new msg")

            val bytes = newMsg.serialize()
            bytes.forEach {
                Log.d(Tag(), "$it")
            }

            outputDataCh.send(bytes)
        }
    }

    private suspend fun keepAliveReceiver() = withContext(Dispatchers.Default) {
        val Tag = { "$Tag:KAT" }

        // keepalive timer should be started after first keepalive message arrival
        while (!inputChannels.containsKey(MessageFromController.KeepAlive::class)) {
            delay(100)
            continue
        }

        while (isActive) {
            try {
                withTimeout(MessageFromController.KeepAlive.KEEPALIVE_TIMEOUT_MS) {
                    val msg = inputChannels[MessageFromController.KeepAlive::class]?.receiveCatching()
                        ?.getOrNull()

                    if (msg != null) {
                        Log.v(Tag(), "KeepAlive received")
                    } else {
                        Log.e(Tag(), "KeepAlive msg is null! Terminating!")
                        onFatalErrorCallback()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                if (System.currentTimeMillis() - dataLink.lastInputOperationTimeStamp > MessageFromController.KeepAlive.KEEPALIVE_TIMEOUT_MS) {
                    Log.e(
                        Tag(),
                        "KeepAlive timed out with timeout set to ${MessageFromController.KeepAlive.KEEPALIVE_TIMEOUT_MS}"
                    )
                    onFatalErrorCallback()
                    return@withContext
                }
            } catch (e: CancellationException) {
                Log.d(Tag(), "cancelled due to: $e")
                return@withContext
            } //            yield()
        }
    }

    private suspend fun checkLatency() = withContext(Dispatchers.Default) {
        Log.d(Tag, "Checking link latency")

        repeat(10) {
            val sendTimeStamp = System.currentTimeMillis()
            outputMessagesChannel.send(EchoMessage())

            try {
                withTimeout(1000) {
                    while (!inputChannels.containsKey(MessageFromController.Dummy::class)) {
                        yield()
                        continue
                    }

                    val response = inputChannels[MessageFromController.Dummy::class]?.receiveCatching()
                        ?.getOrNull() as MessageFromController.Dummy?

                    if (response != null) Log.d(
                        Tag,
                        "Echo obtained, latency: ${System.currentTimeMillis() - sendTimeStamp}ms"
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(Tag, "Latency check failed because of timeout 1s")
            } catch (e: Exception) {
                Log.e(Tag, "Latency check failed: unexpected exception: $e")
            }
        }
    }

    //todo: refactor this method
    private suspend fun runDataLink() = withContext(Dispatchers.Default) {
        Log.d(Tag, "Starting new Controller DataLink: ${dataLink.id}")

        val dataLinkJob = async {
            dataLink.run()
        }

        try {
            dataLinkJob.await()
            Log.e(Tag, "DataLink ended its job!")
        } catch (e: SocketTimeoutException) {
            Log.e(Tag, "socket connection timeout")
        } catch (e: CancellationException) {
            Log.e(Tag, "DataLink task canceled due to: ${e.message} : ${e.cause}")
        } catch (e: Exception) {
            Log.e(Tag, "Unexpected exception caught from DataLink: ${e.message}")
        } finally {
            onFatalErrorCallback()
        }
        return@withContext

    }

    private suspend fun addNewBoards(newBoards: MessageFromController.Boards) = boardsMutex.withLock {
        boards.clear()
        return@withLock boards.addAll(newBoards.boardsInfo.map { b ->
            IoBoard(
                b.address.toInt(),
                internalParams = IoBoardInternalParameters(
                    b.internals.inR1,
                    b.internals.outR1,
                    b.internals.inR2,
                    b.internals.outR2,
                    b.internals.shuntR,
                    b.internals.outVLow,
                    b.internals.outVHigh

                ),
                voltageLevel = b.voltageLevel,
                belongsToController = WeakReference(this)
            )
        })
    }

    private suspend fun catchConnectivityMsg(): MessageFromController.Connectivity? {
        val msg = try {
            withTimeout(FindConnection.SINGLE_PIN_RESULT_TIMEOUT_MS) {
                val type = MessageFromController.Connectivity::class

                while (!inputChannels.containsKey(type)) {
                    yield()
                    continue
                }

                inputChannels[type]?.receiveCatching()
                    ?.getOrNull() as MessageFromController.Connectivity?
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(Tag, "Single pin results timeout!")
            return null
        } catch (e: Exception) {
            Log.e(Tag, "Unexpected exception while waiting for single pin connectivity results: ${e.message}")
            return null
        }

        if (msg == null) Log.e(Tag, "Connectivity info msg is null")

        return msg
    }
}
