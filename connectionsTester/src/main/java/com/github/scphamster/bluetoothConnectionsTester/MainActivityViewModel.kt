package com.github.scphamster.bluetoothConnectionsTester

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.harrysoft.somedir.BluetoothManager
import com.harrysoft.somedir.BluetoothManager.Companion.manager
import com.github.scphamster.bluetoothConnectionsTester.R

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    private var bluetoothManager: BluetoothManager? = null
    val pairedDeviceList = MutableLiveData<Collection<BluetoothDevice>>()
    private var isInitialized = false

    fun setupViewModel(): Boolean {
        if (isInitialized) return true

        isInitialized = true
        bluetoothManager = manager
        if (bluetoothManager == null) {
            Toast
                .makeText(getApplication(), R.string.no_bluetooth, Toast.LENGTH_LONG)
                .show()

            return false
        }

        return true
    }

    fun refreshPairedDevices() {
        pairedDeviceList.postValue(bluetoothManager?.pairedDevices)
    }

    override fun onCleared() {
        if (bluetoothManager != null) bluetoothManager?.close()
    }

    fun getPairedDeviceList(): LiveData<Collection<BluetoothDevice>> {
        return pairedDeviceList
    }
}