package com.github.scphamster.bluetoothConnectionsTester

import android.util.Log

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.*
import kotlinx.coroutines.*

typealias BoardCountT = Int

class DeviceControlViewModel(val app: Application) : AndroidViewModel(app) {
    companion object {
        private const val Tag = "ControlViewModel"
    }

    private var isInitialized: Boolean
    private val errorHandler: ErrorHandler
    private val bluetooth: BluetoothBridge
    val measurementsHandler: MeasurementsHandler
    val controllerIsNotConfigured: Boolean
        get() {
            return measurementsHandler.boardsManager.boards.value?.isEmpty() ?: true
        }
    var thresholdResistanceBeforeNoise: Float = 0f

    init {
        isInitialized = false
        errorHandler = ErrorHandler(app)
        bluetooth = BluetoothBridge(errorHandler)
        measurementsHandler = MeasurementsHandler(errorHandler, bluetooth, app, viewModelScope)
    }

    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        if (!isInitialized) {
            bluetooth.deviceName = deviceName
            bluetooth.mac = mac
            bluetooth.connect()

            configuePinoutAccordingToFile()

            isInitialized = true
        }
        return true
    }

    fun configuePinoutAccordingToFile() {
        viewModelScope.launch {
            val workbook = viewModelScope.async {
                Storage.getWorkBookFromFile(app)
            }

            try {
                val workbook_instance = workbook.await()
                Log.d(Tag, "Workbook obtained, Not null? : ${workbook != null}")

                measurementsHandler.boardsManager.pinDescriptionInterpreter.document = workbook_instance

                toast("Pinout descriptor found")
                measurementsHandler.boardsManager.fetchPinsInfoFromExcelToPins()
            }
            catch (e: Throwable) {
                toast(e.message)
            }
        }
    }

    fun storeMeasurementsToFile() = viewModelScope.launch {
        val job = viewModelScope.async(Dispatchers.Default) {
            measurementsHandler.storeMeasurementsResultsToFile(thresholdResistanceBeforeNoise)
        }

        try {
            job.join()
            toast("Successfully stored results to file!")
        }
        catch (e: Error) {
            toast(e.message)
        }
        catch (e: Throwable) {
            toast(e.message)
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
            measurementsHandler.commander.sendCommand(
                ControllerResponseInterpreter.Commands.CheckHardware())

            delay(1000)

            measurementsHandler.commander.sendCommand(
                ControllerResponseInterpreter.Commands.SetOutputVoltageLevel(
                    ControllerResponseInterpreter.Commands.SetOutputVoltageLevel.VoltageLevel.Low))
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

    fun setMinimumResistanceToBeRecognizedAsConnection(value_as_text: String) {
        val resistance = value_as_text.toResistance()

        resistance?.let {
            thresholdResistanceBeforeNoise = resistance.value
        }
    }

    private fun toast(msg: String?) {
        Toast
            .makeText(app, msg, Toast.LENGTH_LONG)
            .show()
    }
}

