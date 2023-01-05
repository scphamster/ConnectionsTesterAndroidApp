package com.github.scphamster.bluetoothConnectionsTester

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.MeasurementsHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

typealias BoardCountT = Int

class DeviceControlViewModel(val app: Application) : AndroidViewModel(app) {
    val measurementsHandler by lazy { MeasurementsHandler() }
    private var isInitialized: Boolean = false
    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        if (!isInitialized) {

            measurementsHandler.parentViewModel = WeakReference(this)
            measurementsHandler.context = getApplication()
            measurementsHandler.deviceName = deviceName
            measurementsHandler.mac = mac

            measurementsHandler.connect()
            isInitialized = true
        }
        return true
    }

    fun storeMeasurementsToFile() = viewModelScope.launch {
        val job = viewModelScope.async(Dispatchers.Default) {
            measurementsHandler.storeMeasurementsResultsToFile()
        }

        try{
            job.join()
            toast("Successfully stored results to file!")
        }
        catch (e: Error) {
            toast(e.message)
        }
        catch(e: Throwable){
            toast(e.message)
        }
    }



    private fun toast(msg: String?) {
        Toast
            .makeText(app, msg, Toast.LENGTH_LONG)
            .show()
    }

}

