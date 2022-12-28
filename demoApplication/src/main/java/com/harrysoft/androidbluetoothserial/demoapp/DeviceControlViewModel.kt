package com.harrysoft.androidbluetoothserial.demoapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.CommandHandler

typealias BoardCountT = Int

class DeviceControlViewModel(app: Application) : AndroidViewModel(app) {
    val commandHandler by lazy { CommandHandler() }
    private var isInitialized: Boolean = false
    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        // Check we haven't already been called
        if (!isInitialized) {
            isInitialized = true

            commandHandler.app = getApplication()
            commandHandler.deviceName = deviceName
            commandHandler.mac = mac

//            deviceNameData.postValue(deviceName)
//            connectionStatus.postValue(CommandHandler.ConnectionStatus.DISCONNECTED)
        }
        return true
    }

}

