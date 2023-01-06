package com.github.scphamster.bluetoothConnectionsTester

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.BluetoothBridge
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ErrorHandler
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.MeasurementsHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

typealias BoardCountT = Int

class DeviceControlViewModel(val app: Application) : AndroidViewModel(app) {
    private var isInitialized: Boolean
    private val errorHandler: ErrorHandler
    private val bluetooth:BluetoothBridge
    val measurementsHandler: MeasurementsHandler
    val shouldCheckHardware: Boolean
        get() {return measurementsHandler.boardsManager.boards.value?.isEmpty() ?: true}

    init{
        isInitialized = false
        errorHandler = ErrorHandler(app)
        bluetooth = BluetoothBridge(errorHandler)
        measurementsHandler =  MeasurementsHandler(errorHandler, bluetooth, app, viewModelScope)
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
                measurementsHandler.boardsManager.pinDescriptionInterpreter.document = workbook.await()
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
            measurementsHandler.storeMeasurementsResultsToFile()
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

    private fun toast(msg: String?) {
        Toast
            .makeText(app, msg, Toast.LENGTH_LONG)
            .show()
    }
}

