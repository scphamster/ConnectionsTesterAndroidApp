package com.harrysoft.androidbluetoothserial.demoapp

import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.harrysoft.androidbluetoothserial.BluetoothManager
import com.harrysoft.androidbluetoothserial.BluetoothSerialDevice
import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers

typealias BoardCountT = Int

class DeviceControlViewModel(app: Application) : AndroidViewModel(app) {
    private val compositeDisposable = CompositeDisposable()
    private var bluetoothManager: BluetoothManager? = null
    private var deviceInterface: SimpleBluetoothDeviceInterface? = null
    private var mac: String? = null
    private var deviceName: String? = null
    var deviceNameData = MutableLiveData<String>()
        private set
        get
    private var connectionAttemptedOrMade: Boolean = false
    private var isInitialized: Boolean = false
    var connectionStatus = MutableLiveData<ConnectionStatus>()
        private set
        get

    var numberOfConnectedBoards = MutableLiveData<BoardCountT>()
        private set
        get

    var messages = StringBuilder()

    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        // Check we haven't already been called
        if (!isInitialized) {
            isInitialized = true

            // Setup our BluetoothManager
            bluetoothManager = BluetoothManager.btm
            if (bluetoothManager == null) {
                toast(R.string.bluetooth_unavailable)
                return false
            }

            this.deviceName = deviceName
            this.mac = mac

            deviceNameData.postValue(deviceName)
            connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
        }
        return true
    }

    fun connect() {
        if (connectionAttemptedOrMade) return

        compositeDisposable.add(bluetoothManager!!.openSerialDevice(mac!!)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ device: BluetoothSerialDevice -> onConnected(device.toSimpleDeviceInterface()) }) { t: Throwable? ->
                    toast(R.string.connection_failed)
                    connectionAttemptedOrMade = false
                    connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
                })

        connectionAttemptedOrMade = true
        connectionStatus.postValue(ConnectionStatus.CONNECTING)
    }
    fun disconnect() {
        // Check we were connected
        if (connectionAttemptedOrMade && deviceInterface != null) {
            connectionAttemptedOrMade = false
            // Use the library to close the connection
            bluetoothManager!!.closeDevice(deviceInterface!!)
            // Set it to null so no one tries to use it
            deviceInterface = null
            // Tell the activity we are disconnected
            connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
        }
    }

    fun test1() {
        numberOfConnectedBoards.postValue(100)
    }

    private fun onConnected(bt_interface: SimpleBluetoothDeviceInterface) {
        deviceInterface = bt_interface

        if (deviceInterface != null) {
            connectionStatus.postValue(ConnectionStatus.CONNECTED)
            numberOfConnectedBoards.postValue(100)


            deviceInterface?.setListeners({ message: String -> onMessageReceived(message) },
                { message: String -> onMessageSent(message) },
                { error: Throwable -> toast(R.string.message_send_error) })
            // Tell the user we are connected.
            toast(R.string.connected)
        }
        else {
            toast(R.string.connection_failed)
            connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
        }
    }
    private fun onMessageReceived(message: String) {
        messages.append(message)
                .append('\n')

        //todo: implement parsing
    }
    private fun onMessageSent(message: String) {
        messages.append(getApplication<Application>().getString(R.string.you_sent))
                .append(": ")
                .append(message)
                .append('\n')
    }
    private fun toast(@StringRes messageResource: Int) {
        Toast.makeText(getApplication(), messageResource, Toast.LENGTH_LONG)
                .show()
    }

    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED
    }
}

