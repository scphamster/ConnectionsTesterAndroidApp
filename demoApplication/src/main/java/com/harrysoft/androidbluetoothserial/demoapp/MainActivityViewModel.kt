package com.harrysoft.androidbluetoothserial.demoapp

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.harrysoft.androidbluetoothserial.BluetoothManager
import com.harrysoft.androidbluetoothserial.BluetoothManager.Companion.instance

class MainActivityViewModel  // Called by the system, this is just a constructor that matches AndroidViewModel.
    (application: Application) : AndroidViewModel(application) {
    // Our BluetoothManager!
    private var bluetoothManager: BluetoothManager? = null

    // The paired devices list tha the activity sees
    val pairedDeviceList = MutableLiveData<Collection<BluetoothDevice>>()

    // A variable to help us not setup twice
    private var viewModelSetup = false

    // Called in the activity's onCreate(). Checks if it has been called before, and if not, sets up the data.
    // Returns true if everything went okay, or false if there was an error and therefore the activity should finish.
    fun setupViewModel(): Boolean {
        // Check we haven't already been called
        if (!viewModelSetup) {
            viewModelSetup = true

            // Setup our BluetoothManager
            bluetoothManager = instance
            if (bluetoothManager == null) {
                // Bluetooth unavailable on this device :( tell the user
                Toast.makeText(getApplication(), R.string.no_bluetooth, Toast.LENGTH_LONG).show()
                // Tell the activity there was an error and to close
                return false
            }
        }
        // If we got this far, nothing went wrong, so return true
        return true
    }

    // Called by the activity to request that we refresh the list of paired devices
    fun refreshPairedDevices() {
        pairedDeviceList.postValue(bluetoothManager!!.pairedDevices)
    }

    // Called when the activity finishes - clear up after ourselves.
    override fun onCleared() {
        if (bluetoothManager != null) bluetoothManager!!.close()
    }

    // Getter method for the activity to use.
    fun getPairedDeviceList(): LiveData<Collection<BluetoothDevice>> {
        return pairedDeviceList
    }
}