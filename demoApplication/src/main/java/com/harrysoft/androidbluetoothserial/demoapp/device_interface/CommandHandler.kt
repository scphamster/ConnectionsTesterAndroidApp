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

import com.harrysoft.androidbluetoothserial.demoapp.device_interface.CommandInterpreter.Commands
import java.lang.ref.WeakReference

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

        val _boardsCount = MutableLiveData<Int>()
        val boards = MutableLiveData<MutableList<MutableLiveData<IoBoard>>>()
        val pinsConnections = MutableLiveData<MutableList<Connections>>()
//            private set

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
    val boardsManager = IoBoardsManagerLive()

//    var ioBoards = IOBoardState()
//        private set

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

        compositeDisposable.add(bluetoothManager!!
                                    .openSerialDevice(mac!!)
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

    override fun sendCommand(cmd: Commands.SetVoltageAtPin) {
        val command: String
        val command_argument: String

        command = app.getString(R.string.set_pin_cmd)
        command_argument = cmd.pin.toString()

        sendRawCommand(command + " " + command_argument)
    }

    fun sendCommand(cmd: Commands.SetOutputVoltageLevel) {
        val command: String
        val command_argument: String

        command = app.getString(R.string.set_output_voltage)
        command_argument = cmd.level.toString()

        sendRawCommand(command + " " + command_argument)
    }

    fun sendCommand(cmd: Commands.CheckConnectivity) {
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

    fun sendCommand(cmd: Commands.CheckHardware) {
        //todo: make supervisor to watch for answer to happen, if answer will not be obtained - controller is unhealthy
        sendRawCommand(app.getString(R.string.get_all_boads_online))
    }

    private fun boardsInitializer(boards_id: Array<IoBoardIndexT>) {
        val new_boards = mutableListOf<IoBoard>()
        var boards_counter = 0

        for (board in boards_id) {
            val new_board = IoBoard(board)
            val new_pin_group = PinGroup(boardsManager.nextUniqueBoardId)

            for (pin_num in 0..(IoBoard.pinsCountOnSingleBoard - 1)) {
                val descriptor = PinDescriptor(PinAffinityAndId(board, pin_num), group = new_pin_group)

                val new_pin =
                    Pin(descriptor, belongsToBoard = WeakReference(new_board))
                new_board.pins.add(new_pin)
            }

            new_boards.add(new_board)
            boards_counter++
        }

        boardsManager.boards.value = new_boards
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
                                          { Log.d(Tag, "command sent: $it") },
                                          { error: Throwable -> toast(R.string.message_send_error.toString()) })

            toast(R.string.connected.toString())
        }
        else {
            toast(R.string.connection_failed.toString())
            connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
        }

        sendCommand(CommandInterpreter.Commands.CheckHardware())
    }

    private fun onMessageReceived(message: String) {
        var msg: String? = message

        while (msg != null) {
            val (controller_msg, rest) = parseAndSplitSingleCommandFromString(msg)
            if (controller_msg == null) return

            msg = rest
            handleControllerMsg(controller_msg)
        }
    }

    private fun handleControllerMsg(msg: CommandInterpreter.ControllerMessage) {
        when (msg) {
            is CommandInterpreter.ControllerMessage.ConnectionsDescription -> {
                if (boardsManager.boards.value == null) {
                    Log.e(Tag, "Connections info arrived but boards are not initialized yet!")
                    return
                }

                boardsManager.updatePinConnections(msg)
            }

            is CommandInterpreter.ControllerMessage.HardwareDescription -> {
                Log.i(Tag, "Hardware description command arrived");
                boardsInitializer(msg.boardsOnLine)
            }

            else -> {
                return
            }
        }
    }

    lateinit var app: Application
    private fun toast(msg: String) {
        Toast
            .makeText(app, msg, Toast.LENGTH_LONG)
            .show()
    }

    companion object {
        //        val Tag = this::class.simpleName.toString()
        val Tag = "CommandHandler"
        const val unused: Int = -1
    }
}