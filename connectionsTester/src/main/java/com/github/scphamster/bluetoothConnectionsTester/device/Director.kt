package com.github.scphamster.bluetoothConnectionsTester.device

import android.util.Log
import android.app.Application
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.dataLink.ControllerResponse
import com.github.scphamster.bluetoothConnectionsTester.dataLink.DeviceLink
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

class Director(val app: Application, val scope: CoroutineScope, val errorHandler: ErrorHandler) {
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
    
    private val controllers = mutableListOf<ControllerManager>()
    private val deviceLinks = mutableListOf<DeviceLink>()
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
            voltageChangeJobs.add(scope.async {
                controller.setVoltageLevel(new_voltage_level)
            })
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
            val socketReceiverJob = async {
                newDeviceLinksChannel.receiveCatching()
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
                    machineState = MachineState.Operating
                }
            }
            
            val result = socketReceiverJob.await()
            timeoutJob.cancel()
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
            controllers.add(ControllerManager(scope, new_link))
        }
    }
    
    suspend fun initAllControllers() {
        for (controller in controllers) {
            controller.initialize()
        }
    }
}