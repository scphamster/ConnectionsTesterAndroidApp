package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.github.scphamster.bluetoothConnectionsTester.R
import com.github.scphamster.bluetoothConnectionsTester.device.ErrorHandler
import com.github.scphamster.bluetoothConnectionsTester.device.MeasurementsHandler
import com.harrysoft.somedir.BluetoothManager
import com.harrysoft.somedir.BluetoothSerialDevice
import com.harrysoft.somedir.SimpleBluetoothDeviceInterface
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers

class BluetoothBridge(val errorHandler: ErrorHandler) {
    companion object {
        const val Tag = "BluetoothBridge"
    }

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private val compositeDisposable = CompositeDisposable()
    private var bluetoothManager: BluetoothManager? = null
    private var deviceInterface: SimpleBluetoothDeviceInterface? = null
    var mac: String? = null
    var deviceName: String? = null
    val connectionStatus = MutableLiveData<ConnectionStatus>()
        get
    private var connectionAttemptedOrMade: Boolean = false

    //new
    lateinit var onMessageReceivedCallback: ((String) -> Unit)

    init {
        if (BluetoothManager.manager != null) {
            bluetoothManager = BluetoothManager.manager
        }
        else {
            errorHandler.handleError(R.string.bluetooth_unavailable.toString())
        }
    }

    fun connect() {
        if (connectionAttemptedOrMade) return

        compositeDisposable.add(bluetoothManager!!
                                    .openSerialDevice(mac!!)
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe({ device: BluetoothSerialDevice ->
                                                   onConnected(device.toSimpleDeviceInterface())
                                               }) { t: Throwable? ->
                                        errorHandler.handleError(R.string.connection_failed.toString())
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

    fun sendRawCommand(cmd: String) {
        if (cmd.isEmpty()) {
            Log.e(MeasurementsHandler.Tag, "Empty command is not allowed")
            return
        }
        Log.d(MeasurementsHandler.Tag, "Sending command: " + cmd)
        deviceInterface?.sendMessage(cmd)
        Log.d(MeasurementsHandler.Tag, "Command sent")
    }

    private fun onConnected(bt_interface: SimpleBluetoothDeviceInterface) {
        deviceInterface = bt_interface

        deviceInterface?.setListeners({ message: String ->
                                          if (::onMessageReceivedCallback.isInitialized) {
                                              onMessageReceivedCallback(message)
                                          }
                                      }, { Log.d(Tag, "command sent: $it") }, { error: Throwable ->
                                          errorHandler.handleError(R.string.message_send_error.toString())
                                          disconnect()
                                      })

        connectionStatus.postValue(ConnectionStatus.CONNECTED)
    }
}