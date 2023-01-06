package com.github.scphamster.bluetoothConnectionsTester

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.BluetoothBridge
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ErrorHandler
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.MeasurementsHandler
import com.harrysoft.somedir.BluetoothManager
import com.harrysoft.somedir.SimpleBluetoothDeviceInterface
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

typealias BoardCountT = Int

class DeviceControlViewModel(val app: Application) : AndroidViewModel(app) {
    private val errorHandler by lazy { ErrorHandler(app) }
    private val bluetooth by lazy { BluetoothBridge(errorHandler) }
    val measurementsHandler by lazy { MeasurementsHandler(bluetooth, app) }

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
                    measurementsHandler.boardsManager.pinsDescriptorWorkbook = workbook.await()
                    toast("Pinout descriptor found")
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

