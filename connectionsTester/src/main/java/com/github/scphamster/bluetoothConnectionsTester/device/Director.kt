package com.github.scphamster.bluetoothConnectionsTester.device

import android.util.Log
import android.app.Application
import android.view.KeyEvent.DispatcherState
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.dataLink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class Director(val app: Application, val scope: CoroutineScope, val errorHandler: ErrorHandler) {
    companion object {
        private const val Tag = "Director"
        private const val CHANNEL_SIZE = 10
        private const val WAIT_FOR_NEW_SOCKETS_TIMEOUT = 10_000
        private const val STANDARD_ENTRY_SOCKET = 1500
    }
    
    enum class MachineState {
        SearchingControllers,
        InitializingControllers,
        Operating
    }
    
    private inner class NewSocketRegistator(val socketChannel: Channel<DeviceLink>) {
        init {
            scope.launch(Dispatchers.Default) {
                while (isActive) {
                    try {
                        startEntrySocket()
                    }
                    catch (e: Exception) {
                        Log.e(Tag, "Exception in entrySocketAsync task: ${e.message}")
                    }
                }
            }
        }
        
        suspend fun startEntrySocket() = withContext(Dispatchers.IO) {
            while (isActive) {
                val ss = ServerSocket(STANDARD_ENTRY_SOCKET)
                val socket = ss.accept()
                
                Log.d(Tag, "Someone connected: ${socket.remoteSocketAddress}")
                
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                val newSocket = WorkSocket()
                
                launch(Dispatchers.Default) {
                    socketChannel.send(newSocket)
                }
                
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
                        
                        else -> {
                            Log.e(Tag, "New socket: Expected operation confirmation but obtained other type!")
                        }
                    }
                }
                
                outputStream.close()
                inputStream.close()
                socket.close()
                ss.close()
            }
            Log.e(Tag, "Entry socket is closed!")
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
    
    var machineState = MachineState.SearchingControllers
    var voltageLevel = IoBoardsManager.VoltageLevel.Low
        private set(newVal) {
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
    
    val isReady: Boolean
        get() = controllersNumberHasSettled.get()
    val newDeviceLinksChannel = Channel<DeviceLink>(CHANNEL_SIZE)
    val controllersNumber: Int
        get() = controllers.size
    
    private val controllers = CopyOnWriteArrayList<ControllerManager>()
    private val deviceLinks = mutableListOf<DeviceLink>()
    private val newSocketRegistator = NewSocketRegistator(newDeviceLinksChannel)
    private val controllersNumberHasSettled: AtomicBoolean = AtomicBoolean(false)
    
    init {
        scope.launch(Dispatchers.Default) {
            receiveNewDataLinksTask()
        }
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
        
        voltageLevel = new_voltage_level
    }
    
    private suspend fun receiveNewDataLinksTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            val waitForAllDevicesToConnectTimeoutJob = async {
                delay(WAIT_FOR_NEW_SOCKETS_TIMEOUT.toLong())
                if (isActive) {
                    controllersNumberHasSettled.set(true)
                    Log.d(Tag, "Before initialization all ctles. Ctls count: ${controllers.size}")
                    initAllControllersAsync()
                }
            }
            
            val result = newDeviceLinksChannel.receiveCatching()
            waitForAllDevicesToConnectTimeoutJob.cancel()
            
            machineState = MachineState.SearchingControllers
            controllersNumberHasSettled.set(false)
            
            val new_link = result.getOrNull()
            if (new_link == null) {
                Log.e(Tag, "New WorkSocket arrived but it is NULL! ${result.toString()}")
                delay(500)
                continue
            }
            
            Log.d(Tag, "New socket arrived!")
            deviceLinks.add(new_link)
            controllers.add(ControllerManager(new_link))
            
            //start new controller
            launch {
                startNewController(controllers.last())
            }
        }
        
        Log.e(Tag, "New sockets receiver task ended!")
    }
    
    suspend fun startNewController(newController: ControllerManagerI) = withContext(Dispatchers.Default) {
        try {
            newController.startSocket().await()
        }
        catch (e: Exception) {
            val socketPort = newController.dataLink.id
            
            Log.e(Tag, "Exception caught in controller socket with port $socketPort: ${e.message}")
            try {
                if (controllers.removeIf {
                        if (it.dataLink.id == socketPort) {
                            it.cancelAllJobs()
                            true
                        }
                        else false
                    }) {
                    Log.d(Tag, "Controller $socketPort was removed!")
                }
                else {
                    Log.e(Tag, "Controller with socket: $socketPort was not found to be removed")
                }
            }
            catch (e: Exception) {
                Log.e(Tag, "Exception during controller removal: ${e.message}")
            }
        }
    }
    
    suspend fun initAllControllersAsync() = withContext(Dispatchers.Default) {
        Log.d(Tag, "Controllers count: ${controllers.size}")
        for ((index, controller) in controllers.withIndex()) {
            Log.d(Tag, "Initializing $index controller")
            launch {
                controller.initialize()
            }
        }
    }
}