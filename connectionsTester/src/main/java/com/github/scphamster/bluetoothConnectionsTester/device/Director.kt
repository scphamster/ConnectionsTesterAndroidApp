package com.github.scphamster.bluetoothConnectionsTester.device

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.circuit.SimpleConnectivityDescription
import com.github.scphamster.bluetoothConnectionsTester.dataLink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class Director(val app: Application,
               val scope: CoroutineScope,
               val errorHandler: ErrorHandler,
               val boardsArrayChannel: Channel<Array<IoBoard>>) {
    companion object {
        private const val Tag = "Director"
        private const val CHANNEL_SIZE = 10
        private const val WAIT_FOR_NEW_SOCKETS_TIMEOUT = 4_000.toLong()
        private const val STD_ENTRY_SOCKET_PORT = 1500
        private const val STD_ENTRY_SOCKET_TIMEOUT_MS = 4000
        private const val KEEPALIVE_MSG_PERIOD_MS = 1000
    }
    
    private inner class NewSocketRegistator(val socketChannel: Channel<DeviceLink>) {
        init {
            scope.launch(Dispatchers.Default) {
                while (isActive) {
                    val entrySocketJob = async {
                        run()
                    }
                    
                    try {
                        entrySocketJob.await()
                    }
                    catch (e: CancellationException) {
                        Log.d(Tag, "cancelled due to: $e")
                    }
                    catch (e: Exception) {
                        Log.e(Tag, "Unexpected exception: $e")
                    } finally {
                        if (::serverSocket.isInitialized && !serverSocket.isClosed) {
                            withContext(Dispatchers.IO) {
                                serverSocket.close()
                            }
                        }
                    }
                }
            }
        }
        
        lateinit var socket: Socket
        lateinit var serverSocket: ServerSocket
        
        suspend fun run() = withContext(Dispatchers.IO) {
            val Tag = Tag + ":EntrySocket"
            val keepAliveMessage = WorkSocket.KeepAliveMessage(KeepAliveMessage().serialize(), 1000)
            
            while (isActive) {
                Log.w(Tag, "Restart")
                try {
                    serverSocket = ServerSocket(STD_ENTRY_SOCKET_PORT)
                    serverSocket.soTimeout = STD_ENTRY_SOCKET_TIMEOUT_MS
                    socket = serverSocket.accept()
                }
                catch (e: SocketTimeoutException) {
                    Log.d(Tag, "serverSocket timeout!")
                    if (::serverSocket.isInitialized && !serverSocket.isClosed) serverSocket.close()
                    continue
                }
                catch (e: Exception) {
                    Log.e(Tag, "Unexpected error in server socket! ${e.message}")
                    if (::serverSocket.isInitialized && !serverSocket.isClosed) serverSocket.close()
                    continue
                }
                
                Log.d(Tag, "Someone connected: ${socket.remoteSocketAddress}")
                
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                val newSocket = WorkSocket(keepAliveMessage)
                
                socketChannel.send(newSocket)
                
                while (newSocket.port == -1) {
                    continue
                }
                
                Log.d(Tag, "New controller socket started! Port: ${newSocket.port}")
                
                outputStream.write(newSocket.port.toByteArray())  // writing new port number to controller
                val msg = ReadAnswer(inputStream)
                
                if (msg == null) {
                    Log.e(Tag, "New socket: answer not obtained! Restarting entry server!")
                }
                else {
                    when (msg) {
                        is Msg.OperationConfirmation -> {
                            Log.d(Tag, "New socket: confirmation: ${msg.confirmationValue}")
                        }
                    }
                }
                serverSocket.close()
            }
        }
        
        suspend fun ReadAnswer(inStream: java.io.InputStream, timeout: Long = Long.MAX_VALUE) =
                withContext<Msg?>(Dispatchers.IO) {
                    val deadline = if (timeout == Long.MAX_VALUE) Long.MAX_VALUE
                    else timeout
                    
                    while (inStream.available() == 0) {
                        if (deadline < System.currentTimeMillis()) {
                            Log.e(Tag, "Timeout of answer retrieval!")
                            return@withContext null
                        }
                    }
                    
                    val buffer = ByteArray(inStream.available() + 64)
                    inStream.read(buffer)
                    val msg = Msg.deserialize(buffer)
                    
                    return@withContext msg
                }
    }
    
    enum class State {
        InitializingDirector,
        SearchingForControllers,
        InitializingControllers,
        GettingBoards,
        Operating
    }
    
    private inner class MachineState {
        
        val ready: Boolean
            get() = state == State.Operating
        
        val allControllersInitialized: Boolean
            get() {
                var someoneIsNotInitialized = false
                
                controllers.forEach() {
                    if (!it.initialized.get()) {
                        someoneIsNotInitialized = true
                        return@forEach
                    }
                }
                if (controllers.size == 0) someoneIsNotInitialized = true
                
                
                return someoneIsNotInitialized == false
            }
        
        var controllersQuantitySettled = AtomicBoolean(false)
        var state: State = State.InitializingDirector
    }
    
    private class ControllersCommonSettings {
        var voltageLevel = IoBoardsManager.VoltageLevel.Low
            set(newVal) {
                synchronized(this) {
                    field = newVal
                }
            }
            get() {
                val value = synchronized(this) {
                    field
                }
                return value
            }
    }
    
    val controllers = CopyOnWriteArrayList<ControllerManager>()
    
    private val machineState = MachineState()
    private val newDeviceLinksChannel = Channel<DeviceLink>(CHANNEL_SIZE)
    private val newSocketRegistator = NewSocketRegistator(newDeviceLinksChannel)
    private val controllersSettings = ControllersCommonSettings()
    
    init {
        scope.launch {
            newDeviceLinksReceiverTask()
        }
    }
    
    //measurement functions
    suspend fun checkAllConnections(connectionsChannel: Channel<SimpleConnectivityDescription>) =
            withContext(Dispatchers.IO) {
                if (!machineState.allControllersInitialized) {
                    Log.e(Tag, "Not all controllers are initialized, failed check!")
                    return@withContext
                }
                
                if (controllers.size == 0) {
                    Log.e(Tag, "There are no controllers to operate with!")
                }
                else if (controllers.size == 1) {
                    controllers.get(0)
                        .checkConnectionsForLocalBoards(connectionsChannel)
                }
                else {
                    Log.e(Tag,
                          "Unimplemented check all connections with many controllers used! Controllers num = ${controllers.size}")
                }
            }
    
    suspend fun getAllBoards() = withContext<Array<IoBoard>>(Dispatchers.Default) {
        val mutableListOfBoards = mutableListOf<IoBoard>()
        
        val deferreds = controllers.map { c -> async { c.getBoards() } }
        
        val boardsArrays = try {
            deferreds.awaitAll()
        }
        catch (e: Exception) {
            Log.e(Tag, "get all boards command failed with exception: ${e.message}")
            return@withContext emptyArray<IoBoard>()
        }
        
        return@withContext boardsArrays.filterNot { it.isEmpty() }
            .flatMap { it.toList() }
            .toTypedArray()
    }
    
    suspend fun setVoltageLevelAccordingToPreferences() = withContext(Dispatchers.Default) {
        val selected_voltage_level = PreferenceManager.getDefaultSharedPreferences(app)
            .getString("output_voltage_level", "")
        
        val new_voltage_level = when (selected_voltage_level) {
            "Low(0.7V)" -> IoBoardsManager.VoltageLevel.Low
            "High(1.0V)" -> IoBoardsManager.VoltageLevel.High
            else -> IoBoardsManager.VoltageLevel.Low
        }
        
        val voltageChangeJobs = arrayListOf<Deferred<ControllerResponse>>()
        for (controller in controllers) {
            voltageChangeJobs.add(controller.setVoltageLevel(new_voltage_level))
        }
        
        val results = voltageChangeJobs.awaitAll()
        for (result in results) {
            if (result != ControllerResponse.CommandPerformanceSuccess) {
                Log.e(Tag, "Command not successful!")
                return@withContext
            }
        }
        
        controllersSettings.voltageLevel = new_voltage_level
    }
    
    private suspend fun updateAllBoards() = withContext(Dispatchers.Default) {
        Log.d(Tag, "Updating all boards, waiting for all controllers to be initialized")
        while (!machineState.allControllersInitialized) {
            continue
        }
        
        Log.d(Tag, "All controllers initialized, sending boards to boards controller")
        
        val allBoards = getAllBoards()
        if (allBoards.isEmpty()) {
            Log.e(Tag, "No boards found!")
        }
        else {
            Log.d(Tag, "Found ${allBoards.size} boards!")
            boardsArrayChannel.send(allBoards)
        }
    }
    
    private suspend fun newDeviceLinksReceiverTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            val waitForAllDevicesToConnectTimeoutJob = async {
                delay(WAIT_FOR_NEW_SOCKETS_TIMEOUT)
                if (isActive) {
                    if (controllers.size == 0) return@async
                    
                    machineState.controllersQuantitySettled.set(true)
                    updateAllBoards()
                }
            }
            
            val result = newDeviceLinksChannel.receiveCatching()
            waitForAllDevicesToConnectTimeoutJob.cancel()
            
            machineState.controllersQuantitySettled.set(false)
            
            val new_link = result.getOrNull()
            if (new_link == null) {
                Log.e(Tag, "New WorkSocket arrived but it is NULL! ${result.toString()}")
                delay(500)
                continue
            }
            
            Log.d(Tag, "New socket arrived!")
            controllers.add(ControllerManager(new_link) {
                controllers.removeIf() {
                    if (it.dataLink.hashCode() == new_link.hashCode()) {
                        Log.e(Tag, "Controller is removed due to fatal error")
                        it.cancelAllJobs()
                        true
                    }
                    else false
                }
                
                scope.launch {
                    updateAllBoards()
                }
            })
        }
        
        Log.e(Tag, "New sockets receiver task ended!")
    }
}