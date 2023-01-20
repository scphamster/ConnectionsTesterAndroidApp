package com.github.scphamster.bluetoothConnectionsTester

import android.util.Log

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.*
import kotlinx.coroutines.*

import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter.Commands

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
    var maxDetectableResistance: Float = 0f

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
            setupVoltageLevel()

            isInitialized = true
        }
        return true
    }

    fun setupVoltageLevel() {
        val selected_voltage_level = PreferenceManager
            .getDefaultSharedPreferences(app)
            .getString("output_voltage_level", "")
        val voltage_level = when (selected_voltage_level) {
            "Low(0.7V)" -> Commands.SetOutputVoltageLevel.VoltageLevel.Low
            "High(1.0V)" -> Commands.SetOutputVoltageLevel.VoltageLevel.High
            else -> Commands.SetOutputVoltageLevel.VoltageLevel.Low
        }

        measurementsHandler.commander.sendCommand(Commands.SetOutputVoltageLevel(voltage_level))
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
            measurementsHandler.storeMeasurementsResultsToFile(maxDetectableResistance)
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
            measurementsHandler.commander.sendCommand(ControllerResponseInterpreter.Commands.CheckHardware())

            delay(1000)

            measurementsHandler.commander.sendCommand(ControllerResponseInterpreter.Commands.SetOutputVoltageLevel(
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

    fun checkConnections() {
        val if_sequential = PreferenceManager
            .getDefaultSharedPreferences(app)
            .getBoolean(PreferencesFragment.Companion.SharedPreferenceKey.SequentialModeScan.text, false)

        val domain = PreferenceManager
            .getDefaultSharedPreferences(app)
            .getString("connection_domain", "");

        val answer_domain = when (domain) {
            "Raw" -> Commands.CheckConnectivity.AnswerDomain.Voltage
            "Voltage" -> Commands.CheckConnectivity.AnswerDomain.Voltage
            "Resistance" -> Commands.CheckConnectivity.AnswerDomain.Resistance
            "SimpleBoolean" -> Commands.CheckConnectivity.AnswerDomain.SimpleConnectionFlag
            else -> Commands.CheckConnectivity.AnswerDomain.Resistance
        }

        measurementsHandler.commander.sendCommand(Commands.CheckConnectivity(answer_domain, sequential = if_sequential))
    }

    fun checkConnections(for_pin: Pin) {
        val if_sequential = PreferenceManager
            .getDefaultSharedPreferences(app)
            .getBoolean(PreferencesFragment.Companion.SharedPreferenceKey.SequentialModeScan.text, false)

        val domain = PreferenceManager
            .getDefaultSharedPreferences(app)
            .getString("connection_domain", "");

        val answer_domain = when (domain) {
            "Raw" -> Commands.CheckConnectivity.AnswerDomain.Voltage
            "Voltage" -> Commands.CheckConnectivity.AnswerDomain.Voltage
            "Resistance" -> Commands.CheckConnectivity.AnswerDomain.Resistance
            "SimpleBoolean" -> Commands.CheckConnectivity.AnswerDomain.SimpleConnectionFlag
            else -> Commands.CheckConnectivity.AnswerDomain.Resistance
        }


        measurementsHandler.commander.sendCommand(
            Commands.CheckConnectivity(answer_domain, for_pin.descriptor.pinAffinityAndId, if_sequential))
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
        Toast
            .makeText(app, msg, Toast.LENGTH_LONG)
            .show()
    }
}

