package com.harrysoft.androidbluetoothserial.demoapp.device_interface

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData

import com.harrysoft.androidbluetoothserial.BluetoothManager
import com.harrysoft.androidbluetoothserial.BluetoothSerialDevice
import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface
import com.harrysoft.androidbluetoothserial.demoapp.BoardCountT
import com.harrysoft.androidbluetoothserial.demoapp.R

import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers

typealias CommandArgsT = Int

class CommandHandler : CommandInterpreter {
    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    class IOBoardState {
        var lastSelectedOutputPin: PinNumberT = 0
            private set
        var boardsCount: PinNumT = 0
            private set
        val pinCount = boardsCount * numberOfPinsOnSingleBoard

        var pinsConnections = MutableLiveData<MutableList<PinConnections>>()
            private set

        companion object {
            const val numberOfPinsOnSingleBoard = 32
        }
    }

    private val compositeDisposable = CompositeDisposable()
    private var bluetoothManager: BluetoothManager? = null
    private var deviceInterface: SimpleBluetoothDeviceInterface? = null
    var mac: String? = null
    var deviceName: String? = null
    var deviceNameData = MutableLiveData<String>()
        private set
        get
    private var connectionAttemptedOrMade: Boolean = false
    var connectionStatus = MutableLiveData<ConnectionStatus>()
        private set
        get
    var numberOfConnectedBoards = MutableLiveData<BoardCountT>()
        private set
        get
    var messages = StringBuilder()
    var ioBoards = IOBoardState()
        private set

    init {
        if (BluetoothManager.btm != null) {
            bluetoothManager = BluetoothManager.btm
        }
        else {
            toast(R.string.bluetooth_unavailable.toString())
        }
    }

    fun connect() {
        if (connectionAttemptedOrMade) return

        compositeDisposable.add(bluetoothManager!!.openSerialDevice(mac!!)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ device: BluetoothSerialDevice -> onConnected(device.toSimpleDeviceInterface()) }) { t: Throwable? ->
                    toast(R.string.connection_failed.toString())
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

    fun sendCommand(cmd: CommandInterpreter.Commands.SetVoltageAtPin) {
        val command: String
        val command_argument: String

        command = app.getString(R.string.set_pin_cmd)
        command_argument = cmd.pin.toString()

        sendRawCommand(command + " " + command_argument)
    }

    fun sendCommand(cmd: CommandInterpreter.Commands.SetOutputVoltageLevel) {
        val command: String
        val command_argument: String

        command = app.getString(R.string.set_output_voltage)
        command_argument = cmd.level.toString()

        sendRawCommand(command + " " + command_argument)
    }

    fun sendCommand(cmd: CommandInterpreter.Commands.CheckConnectivity) {
        val command: String
        val command_argument: String

        command = app.getString(R.string.check_cmd)

        if (cmd.pin == CommandInterpreter.Commands.CheckConnectivity.checkAllConnections) {
            command_argument = app.getString(R.string.check_all_cmd_special_argument)
        }
        else {
            command_argument = cmd.pin.toString()
        }

        sendRawCommand(command + " " + command_argument)

        //test
//        ioBoards.pinsConnections.postValue(mutableListOf(PinConnections(1, mutableListOf(5))))
//        logAllConnections()
    }

    private fun logAllConnections() {
        Log.d(Tag, "Printing all connections, number of pins: ${ioBoards.pinsConnections.value?.size}")

        ioBoards.pinsConnections.value?.forEach { connection: PinConnections ->
            Log.d(Tag, "Pin: ${connection.pin}, Connections: ${connection.connections.joinToString(" ")}")

        }
    }

    private fun sendRawCommand(cmd: String) {
        if (cmd.isEmpty()) {
            Log.e(Tag, "Empty command is not allowed")
            return
        }
        Log.d(Tag, "Sending command: " + cmd)
        deviceInterface?.sendMessage(cmd)
        Log.d(Tag, "Command sent")
    }

    private fun onConnected(bt_interface: SimpleBluetoothDeviceInterface) {
        deviceInterface = bt_interface

        if (deviceInterface != null) {
            connectionStatus.postValue(ConnectionStatus.CONNECTED)
            numberOfConnectedBoards.postValue(100)


            deviceInterface?.setListeners({ message: String -> onMessageReceived(message) },
                { message: String -> onMessageSent(message) },
                { error: Throwable -> toast(R.string.message_send_error.toString()) })

            toast(R.string.connected.toString())
        }
        else {
            toast(R.string.connection_failed.toString())
            connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
        }
    }

    private fun onMessageReceived(message: String) {
        messages.append(message)
                .append('\n')

        //todo: implement parsing
    }

    private fun onMessageSent(message: String) {
        messages.append(": ")
                .append(message)
                .append('\n')
    }

    lateinit var app: Application
    private fun toast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG)
                .show()
    }

    companion object {
        //        val Tag = this::class.simpleName.toString()
        val Tag = "CommandHandler"
        const val unused: Int = -1
    }
}