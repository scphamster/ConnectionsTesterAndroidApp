package com.harrysoft.androidbluetoothserial.demoapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.MeasurementsHandler

typealias BoardCountT = Int

class DeviceControlViewModel(app: Application) : AndroidViewModel(app) {
    val measurementsHandler by lazy { MeasurementsHandler() }
    private var isInitialized: Boolean = false
    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        if (!isInitialized) {
            isInitialized = true

            measurementsHandler.context = getApplication()
            measurementsHandler.deviceName = deviceName
            measurementsHandler.mac = mac
        }
        return true
    }
}

