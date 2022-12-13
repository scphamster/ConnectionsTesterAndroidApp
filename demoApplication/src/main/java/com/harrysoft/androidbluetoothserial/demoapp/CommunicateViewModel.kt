package com.harrysoft.androidbluetoothserial.demoapp

import android.app.Application
import android.text.TextUtils
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.harrysoft.androidbluetoothserial.BluetoothManager
import com.harrysoft.androidbluetoothserial.BluetoothManager.Companion.instance
import com.harrysoft.androidbluetoothserial.BluetoothSerialDevice
import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface
import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface.OnMessageReceivedListener
import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface.OnMessageSentListener
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers

class CommunicateViewModel  // Called by the system, this is just a constructor that matches AndroidViewModel.
    (application: Application) : AndroidViewModel(application) {
    // A CompositeDisposable that keeps track of all of our asynchronous tasks
    private val compositeDisposable = CompositeDisposable()

    // Our BluetoothManager!
    private var bluetoothManager: BluetoothManager? = null

    // Our Bluetooth Device! When disconnected it is null, so make sure we know that we need to deal with it potentially being null
    private var deviceInterface: SimpleBluetoothDeviceInterface? = null

    // The messages feed that the activity sees
    private val messagesData = MutableLiveData<String>()

    // The connection status that the activity sees
    private val connectionStatusData = MutableLiveData<ConnectionStatus>()

    // The device name that the activity sees
    private val deviceNameData = MutableLiveData<String>()

    // The message in the message box that the activity sees
    private val messageData = MutableLiveData<String>()

    // Our modifiable record of the conversation
    private var messages = StringBuilder()

    // Our configuration
    private var deviceName: String? = null
    private var mac: String? = null

    // A variable to help us not double-connect
    private var connectionAttemptedOrMade = false

    // A variable to help us not setup twice
    private var viewModelSetup = false

    // Called in the activity's onCreate(). Checks if it has been called before, and if not, sets up the data.
    // Returns true if everything went okay, or false if there was an error and therefore the activity should finish.
    fun setupViewModel(deviceName: String, mac: String?): Boolean {
        // Check we haven't already been called
        if (!viewModelSetup) {
            viewModelSetup = true

            // Setup our BluetoothManager
            bluetoothManager = instance
            if (bluetoothManager == null) {
                // Bluetooth unavailable on this device :( tell the user
                toast(R.string.bluetooth_unavailable)
                // Tell the activity there was an error and to close
                return false
            }

            // Remember the configuration
            this.deviceName = deviceName
            this.mac = mac

            // Tell the activity the device name so it can set the title
            deviceNameData.postValue(deviceName)
            // Tell the activity we are disconnected.
            connectionStatusData.postValue(ConnectionStatus.DISCONNECTED)
        }
        // If we got this far, nothing went wrong, so return true
        return true
    }

    // Called when the user presses the connect button
    fun connect() {
        // Check we are not already connecting or connected
        if (!connectionAttemptedOrMade) {
            // Connect asynchronously
            compositeDisposable.add(
                bluetoothManager!!.openSerialDevice(mac!!)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ device: BluetoothSerialDevice -> onConnected(device.toSimpleDeviceInterface()) }) { t: Throwable? ->
                        toast(R.string.connection_failed)
                        connectionAttemptedOrMade = false
                        connectionStatusData.postValue(ConnectionStatus.DISCONNECTED)
                    })
            // Remember that we made a connection attempt.
            connectionAttemptedOrMade = true
            // Tell the activity that we are connecting.
            connectionStatusData.postValue(ConnectionStatus.CONNECTING)
        }
    }

    // Called when the user presses the disconnect button
    fun disconnect() {
        // Check we were connected
        if (connectionAttemptedOrMade && deviceInterface != null) {
            connectionAttemptedOrMade = false
            // Use the library to close the connection
            bluetoothManager!!.closeDevice(deviceInterface!!)
            // Set it to null so no one tries to use it
            deviceInterface = null
            // Tell the activity we are disconnected
            connectionStatusData.postValue(ConnectionStatus.DISCONNECTED)
        }
    }

    // Called once the library connects a bluetooth device
    private fun onConnected(deviceInterface: SimpleBluetoothDeviceInterface) {
        this.deviceInterface = deviceInterface
        if (this.deviceInterface != null) {
            // We have a device! Tell the activity we are connected.
            connectionStatusData.postValue(ConnectionStatus.CONNECTED)
            // Set up the listeners for the interface
            this.deviceInterface!!.setListeners(
                { message: String -> onMessageReceived(message) },
                { message: String -> onMessageSent(message) },
                { error: Throwable -> toast(R.string.message_send_error) }
            )
            // Tell the user we are connected.
            toast(R.string.connected)
            // Reset the conversation
            messages = StringBuilder()
            messagesData.postValue(messages.toString())
        } else {
            // deviceInterface was null, so the connection failed
            toast(R.string.connection_failed)
            connectionStatusData.postValue(ConnectionStatus.DISCONNECTED)
        }
    }

    // Adds a received message to the conversation
    private fun onMessageReceived(message: String) {
        messages.append(deviceName).append(": ").append(message).append('\n')
        messagesData.postValue(messages.toString())
    }

    // Adds a sent message to the conversation
    private fun onMessageSent(message: String) {
        // Add it to the conversation
        messages.append(getApplication<Application>().getString(R.string.you_sent)).append(": ").append(message)
            .append('\n')
        messagesData.postValue(messages.toString())
        // Reset the message box
        messageData.postValue("")
    }

    // Send a message
    fun sendMessage(message: String?) {
        // Check we have a connected device and the message is not empty, then send the message
        if (deviceInterface != null && !TextUtils.isEmpty(message)) {
            deviceInterface!!.sendMessage(message!!)
        }
    }

    // Called when the activity finishes - clear up after ourselves.
    override fun onCleared() {
        // Dispose any asynchronous operations that are running
        compositeDisposable.dispose()
        // Shutdown bluetooth connections
        bluetoothManager!!.close()
    }

    // Helper method to create toast messages.
    private fun toast(@StringRes messageResource: Int) {
        Toast.makeText(getApplication(), messageResource, Toast.LENGTH_LONG).show()
    }

    // Getter method for the activity to use.
    fun getMessages(): LiveData<String> {
        return messagesData
    }

    val connectionStatus: LiveData<ConnectionStatus>
        // Getter method for the activity to use.
        get() = connectionStatusData

    // Getter method for the activity to use.
    fun getDeviceName(): LiveData<String> {
        return deviceNameData
    }

    val message: LiveData<String>
        // Getter method for the activity to use.
        get() = messageData

    // An enum that is passed to the activity to indicate the current connection status
    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED
    }
}