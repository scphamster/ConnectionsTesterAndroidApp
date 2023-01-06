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
    private val errorHandler by lazy { ErrorHandler(app) }
    private val bluetooth by lazy { BluetoothBridge(errorHandler) }
    val measurementsHandler by lazy { MeasurementsHandler(errorHandler, bluetooth, app) }

    private var isInitialized: Boolean = false

    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        if (!isInitialized) {

            bluetooth.deviceName = deviceName
            bluetooth.mac = mac
            bluetooth.connect()

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


            isInitialized = true
        }
        return true
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

