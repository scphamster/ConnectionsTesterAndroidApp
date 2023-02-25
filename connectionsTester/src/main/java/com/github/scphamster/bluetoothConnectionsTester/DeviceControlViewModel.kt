package com.github.scphamster.bluetoothConnectionsTester

import android.app.Application
import android.util.Log
import android.widget.Toast

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.circuit.Pin
import com.github.scphamster.bluetoothConnectionsTester.circuit.toResistance
import com.github.scphamster.bluetoothConnectionsTester.dataLink.BluetoothBridge
import com.github.scphamster.bluetoothConnectionsTester.device.Director

import com.github.scphamster.bluetoothConnectionsTester.device.ControllerResponseInterpreter.Commands
import com.github.scphamster.bluetoothConnectionsTester.device.*
import kotlinx.coroutines.*

class DeviceControlViewModel(val app: Application) : AndroidViewModel(app) {
    companion object {
        private const val Tag = "ControlViewModel"
    }
    
    val errorHandler = ErrorHandler(app)
    val measurementsHandler: MeasurementsHandler
    val controllerIsNotConfigured: Boolean
        get() {
            return measurementsHandler.boardsManager.boards.value?.isEmpty() ?: true
        }
    
    var maxDetectableResistance: Float = 0f
    
    private val controllersManager: Director
    private val bluetooth: BluetoothBridge //    private val logger by lazy { ToFileLogger(app) }
    
    private var isInitialized: Boolean
    
    init {
        isInitialized = false
        bluetooth = BluetoothBridge(errorHandler)
        measurementsHandler = MeasurementsHandler(errorHandler, bluetooth, app, viewModelScope)
        controllersManager =
            Director(app, viewModelScope, errorHandler, measurementsHandler.boardsManager.boardsArrayChannel)
    }
    
    override fun onCleared() {
        Log.d(Tag, "Clearing ViewModel!")
        viewModelScope.cancel("End of viewModelScope")
        viewModelScope.coroutineContext.cancelChildren(CancellationException("End of viewModelScope"))
        super.onCleared()
    }
    
    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        if (!isInitialized) {
            bluetooth.deviceName = deviceName
            bluetooth.mac = mac
            bluetooth.connect()
            
            getPinoutConfigFile()
            setupVoltageLevel()
            
            isInitialized = true
        }
        return true
    }
    
    fun setupVoltageLevel() {
        val selected_voltage_level = PreferenceManager.getDefaultSharedPreferences(app)
            .getString("output_voltage_level", "")
        val voltage_level = when (selected_voltage_level) {
            "Low(0.7V)" -> Commands.SetOutputVoltageLevel.VoltageLevel.Low
            "High(1.0V)" -> Commands.SetOutputVoltageLevel.VoltageLevel.High
            else -> Commands.SetOutputVoltageLevel.VoltageLevel.Low
        }
        
        when (voltage_level) {
            Commands.SetOutputVoltageLevel.VoltageLevel.Low -> measurementsHandler.boardsManager.setOutputVoltageLevelForBoards(
                IoBoardsManager.VoltageLevel.Low)
            
            Commands.SetOutputVoltageLevel.VoltageLevel.High -> measurementsHandler.boardsManager.setOutputVoltageLevelForBoards(
                IoBoardsManager.VoltageLevel.High)
        }
        
        measurementsHandler.commander.sendCommand(Commands.SetOutputVoltageLevel(voltage_level))
    }
    
    fun getPinoutConfigFile() {
        viewModelScope.launch {
            val workbook = viewModelScope.async {
                Storage.getWorkBookFromFile(app)
            }
            
            try {
                val workbook_instance = workbook.await()
                Log.d(Tag, "Workbook obtained, Not null? : ${workbook != null}")
                
                measurementsHandler.boardsManager.pinoutInterpreter.document = workbook_instance
                
                toast("Pinout descriptor found")
                measurementsHandler.boardsManager.fetchPinsInfoFromExcelToPins()
            }
            catch (e: Throwable) {
                e.message?.let {
                    Log.e(Tag, "Error while opening XLSX file: " + it)
                }
                errorHandler.handleError("Error while opening XLSX file: " + e.message)
            }
        }
    }
    
    fun storeMeasurementsToFile() = viewModelScope.launch {
        val job = viewModelScope.async(Dispatchers.Default) {
            measurementsHandler.resultsSaver.storeMeasurements(maxDetectableResistance)
            measurementsHandler.resultsSaver.storeExpectedToMeasuredDifferences(maxDetectableResistance)
        }
        
        try {
            job.join()
            toast("Successfully stored results to file!")
        }
        catch (e: Error) {
            errorHandler.handleError(e.message)
        }
        catch (e: Throwable) {
            errorHandler.handleError(e.message)
        }
    }
    
    fun reconnectToController() {
        if (isInitialized) {
            bluetooth.connect()
            toast("Reconnecting")
        }
        else {
            toast("Error")
        }
    }
    
    fun initializeHardware() {
        viewModelScope.launch(Dispatchers.IO) {
            measurementsHandler.commander.sendCommand(ControllerResponseInterpreter.Commands.CheckHardware())
            
            delay(1000)
            
            setupVoltageLevel()
        }
    }
    
    fun calibrate() {
        viewModelScope.launch {
            measurementsHandler.calibrate { result_message ->
                viewModelScope.launch(Dispatchers.Main) {
                    toast(result_message)
                }
            }
            
        }
    }
    
    fun checkConnections() {
        val if_sequential = PreferenceManager.getDefaultSharedPreferences(app)
            .getBoolean(PreferencesFragment.Companion.SharedPreferenceKey.SequentialModeScan.text, false)
        
        val domain = PreferenceManager.getDefaultSharedPreferences(app)
            .getString("connection_domain", "");
        
        val answer_domain = when (domain) {
            "Raw" -> Commands.CheckConnectivity.AnswerDomain.Raw
            "Voltage" -> Commands.CheckConnectivity.AnswerDomain.Voltage
            "Resistance" -> Commands.CheckConnectivity.AnswerDomain.Resistance
            "SimpleBoolean" -> Commands.CheckConnectivity.AnswerDomain.SimpleConnectionFlag
            else -> Commands.CheckConnectivity.AnswerDomain.Raw
        }
        
        //        measurementsHandler.commander.sendCommand(Commands.CheckConnectivity(answer_domain, sequential = if_sequential))
        viewModelScope.launch {
            controllersManager.checkAllConnections(measurementsHandler.boardsManager.pinConnectivityResultsCh)
        }
    }
    
    fun checkConnections(for_pin: Pin) {
        val if_sequential = PreferenceManager.getDefaultSharedPreferences(app)
            .getBoolean(PreferencesFragment.Companion.SharedPreferenceKey.SequentialModeScan.text, false)
        
        val domain = PreferenceManager.getDefaultSharedPreferences(app)
            .getString("connection_domain", "");
        
        val answer_domain = when (domain) {
            "Raw" -> Commands.CheckConnectivity.AnswerDomain.Raw
            "Voltage" -> Commands.CheckConnectivity.AnswerDomain.Voltage
            "Resistance" -> Commands.CheckConnectivity.AnswerDomain.Resistance
            "SimpleBoolean" -> Commands.CheckConnectivity.AnswerDomain.SimpleConnectionFlag
            else -> Commands.CheckConnectivity.AnswerDomain.Raw
        }
        
        //        measurementsHandler.commander.sendCommand(Commands.CheckConnectivity(answer_domain,
        //                                                                             for_pin.descriptor.pinAffinityAndId,
        //                                                                             if_sequential))
        //
        viewModelScope.launch {
            controllersManager.checkConnection(for_pin.descriptor.pinAffinityAndId,
                                               measurementsHandler.boardsManager.pinConnectivityResultsCh)
            Log.d(Tag, "check connection succeeded")
            
        }
    }
    
    fun setMinimumResistanceToBeRecognizedAsConnection(value_as_text: String) {
        val resistance = value_as_text.toResistance()
        
        resistance?.let {
            maxDetectableResistance = resistance.value
            measurementsHandler.boardsManager.maxResistanceAsConnection = maxDetectableResistance
        }
    }
    
    fun disconnect() {
        bluetooth.disconnect()
    }
    
    fun refreshHardware() {
        measurementsHandler.commander.sendCommand(Commands.CheckHardware())
    }
    
    private fun toast(msg: String?) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG)
            .show()
    }
}

