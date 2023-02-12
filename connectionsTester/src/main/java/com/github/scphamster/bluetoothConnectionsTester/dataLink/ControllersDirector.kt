package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerManager
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ErrorHandler
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.IoBoardsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

class ControllersDirector(val scope: CoroutineScope, val errorHandler: ErrorHandler) {
    companion object {
        private const val Tag = "ControllersMsgRouter"
        private const val CHANNEL_SIZE = 10
        private const val DEADLINE_TIMEOUT_MS = 10_000
    }
    
    enum class MachineState {
        SearchingControllers,
        InitializingControllers,
        Operating
    }
    
    var machineState = MachineState.SearchingControllers
    var voltageLevel = IoBoardsManager.VoltageLevel.Low
    
    
    val workSocketsChannel = Channel<WorkSocket>(CHANNEL_SIZE)

    private val controllers = mutableListOf<ControllerManager>()
    private val workSockets = mutableListOf<WorkSocket>()
    private val controllersNumberHasSettled: AtomicBoolean = AtomicBoolean(false)
    
    init {
        scope.launch(Dispatchers.Default) {
            receiveNewWorkSocketTask()
        }
    }

    

    private suspend fun receiveNewWorkSocketTask() = withContext(Dispatchers.Default) {
        while (isActive) {
            val socketReceiverJob = async {
                workSocketsChannel.receiveCatching()
            }
            val deadline = System.currentTimeMillis() + DEADLINE_TIMEOUT_MS
            val timeoutTimer = async {
                while (System.currentTimeMillis() < deadline) {
                    continue
                }
                true
            }
            
            val timeoutJob = launch {
                val timeoutResult = try {
                    timeoutTimer.await()
                }
                catch (e: Exception) {
                    return@launch
                }
                
                if (timeoutResult) {
                    controllersNumberHasSettled.set(true)
                    machineState = MachineState.InitializingControllers
                }
            }
            
            val result = socketReceiverJob.await()
            timeoutJob.cancel()
            machineState = MachineState.SearchingControllers
            controllersNumberHasSettled.set(false)
            
            val new_socket = result.getOrNull()
            
            if (new_socket == null) {
                Log.e(Tag, "New WorkSocket arrived but it is NULL! ${result.toString()}")
                delay(500)
                continue
            }
    
            Log.d(Tag, "New socket arrived!")
            workSockets.add(new_socket)
            controllers.add(ControllerManager(scope, new_socket))
        }
    }
    private suspend fun initAllControllers() {
        for (controller in controllers) {
            controller.initialize(voltageLevel)
        }
    }
}