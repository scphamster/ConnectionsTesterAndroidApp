package com.github.scphamster.bluetoothConnectionsTester

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.MeasurementsHandler

typealias BoardCountT = Int

class DeviceControlViewModel(app: Application) : AndroidViewModel(app) {
    val measurementsHandler by lazy { MeasurementsHandler() }
    private var isInitialized: Boolean = false
    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        if (!isInitialized) {

            measurementsHandler.context = getApplication()
            measurementsHandler.deviceName = deviceName
            measurementsHandler.mac = mac

            measurementsHandler.connect()
            isInitialized = true
        }
        return true
    }
}

