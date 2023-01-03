package com.harrysoft.androidbluetoothserial.connectionsTester.device_interface

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.harrysoft.androidbluetoothserial.BluetoothManager
import com.harrysoft.androidbluetoothserial.BluetoothSerialDevice
import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface
import com.harrysoft.androidbluetoothserial.connectionsTester.BoardCountT
import com.harrysoft.androidbluetoothserial.connectionsTester.PreferencesFragment
import com.harrysoft.androidbluetoothserial.connectionsTester.R
import com.harrysoft.androidbluetoothserial.connectionsTester.device_interface.ControllerResponseInterpreter.Commands
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers

import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.lang.ref.WeakReference

typealias CommandArgsT = Int

class MeasurementsHandler : ControllerResponseInterpreter {
    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    class IOBoardState {
        var lastSelectedOutputPin: PinNumT = 0
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

    private data class Measurements(private var pinId: String = "", private var isConnectedTo: String = "") {}

    companion object {
        //        val Tag = this::class.simpleName.toString()
        val Tag = "CommandHandler"
        const val unused: Int = -1
    }

    lateinit var context: Application
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
    val boardsManager = IoBoardsManager()

    init {
        if (BluetoothManager.manager != null) {
            bluetoothManager = BluetoothManager.manager
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

    override fun sendCommand(cmd: Commands.SetVoltageAtPin) {
        val command: String
        val command_argument: String

        command = context.getString(R.string.set_pin_cmd)
        command_argument = cmd.pin.toString()

        sendRawCommand(command + " " + command_argument)
    }

    fun sendCommand(cmd: Commands.SetOutputVoltageLevel) {
        val command: String
        val command_argument: String

        command = context.getString(R.string.set_output_voltage)
        command_argument = cmd.level.toString()

        sendRawCommand(command + " " + command_argument)
    }

    fun sendCommand(cmd: Commands.CheckConnectivity) {
        val command: String
        val command_argument: String

        command = context.getString(R.string.check_cmd)

        if (cmd.pin == ControllerResponseInterpreter.Commands.CheckConnectivity.checkAllConnections) {
            command_argument = context.getString(R.string.check_all_cmd_special_argument)
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
        sendRawCommand(context.getString(R.string.get_all_boads_online))
    }

    private fun toast(msg: String) {
        Toast
            .makeText(context, msg, Toast.LENGTH_LONG)
            .show()
    }

    private fun boardsInitializer(boards_id: Array<IoBoardIndexT>) {
//        test_parse()

        val new_boards = mutableListOf<IoBoard>()
        var boards_counter = 0

        for (board in boards_id) {
            val new_board = IoBoard(board)
            val new_pin_group = PinGroup(boardsManager.nextUniqueBoardId)

            for (pin_num in 0..(IoBoard.pinsCountOnSingleBoard - 1)) {
                val descriptor = PinDescriptor(PinAffinityAndId(board, pin_num), group = new_pin_group)

                val new_pin = Pin(descriptor, belongsToBoard = WeakReference(new_board))
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

        sendCommand(ControllerResponseInterpreter.Commands.CheckHardware())
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

    private fun handleControllerMsg(msg: ControllerResponseInterpreter.ControllerMessage) {
        when (msg) {
            is ControllerResponseInterpreter.ControllerMessage.ConnectionsDescription -> {
                if (boardsManager.boards.value == null) {
                    Log.e(Tag, "Connections info arrived but boards are not initialized yet!")
                    return
                }

                boardsManager.updatePinConnections(msg)
            }

            is ControllerResponseInterpreter.ControllerMessage.HardwareDescription -> {
                Log.i(Tag, "Hardware description command arrived");
                boardsInitializer(msg.boardsOnLine)
            }

            else -> {
                return
            }
        }
    }

    fun storeMeasurementsResultsToFile() {
        val file_storage_uri = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(PreferencesFragment.Companion.SharedPreferenceKey.ResultsFileUri.text, "")

        if (file_storage_uri == "") {
            toast("No file for storage selected! Go to settings and set it via: Specify file where to store results")
            return
        }

        val groups_of_sorted_pins = boardsManager.getPinsSortedByGroupOrAffinity()

        if (groups_of_sorted_pins == null) {
            Log.e(Tag, "sorted pins array is null!")
            return
        }

        val outputStream = context.contentResolver.openOutputStream(Uri.parse(file_storage_uri))
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Measurements")

        val names_row = sheet.getRow(0) ?: sheet.createRow(0)

        var column_counter = 0
        for (pins_group in groups_of_sorted_pins) {
            val cell_with_name_of_group = names_row.getCell(column_counter) ?: names_row.createCell(column_counter)
            cell_with_name_of_group.setCellValue(pins_group.getGroupName())

            var row_counter = 1
            for (pin in pins_group.pins) {
                val row_for_this_pin = sheet.getRow(row_counter) ?: sheet.createRow(row_counter)
                val cell_for_this_pin_connections =
                    row_for_this_pin.getCell(column_counter) ?: row_for_this_pin.createCell(column_counter)

                if (pin.isConnectedTo.isEmpty()) {
                    cell_for_this_pin_connections.setCellValue("${pin.descriptor.getPrettyName()} -> NC")
                    row_counter++
                    continue
                }

                val string_builder = StringBuilder()
                string_builder.append("${pin.descriptor.getPrettyName()} -> ")

                for (descriptor_of_connected_pin in pin.isConnectedTo) {
                    val _pin = boardsManager.findPinRefByAffinityAndId(descriptor_of_connected_pin.affinityAndId)

                    if (_pin == null) {
                        Log.e(Tag,
                              """Pin ${descriptor_of_connected_pin.affinityAndId.boardId}:
                                  |${descriptor_of_connected_pin.affinityAndId.idxOnBoard} 
                                  |is not found""".trimMargin())

                        continue
                    }

                    val connected_pin = _pin?.get()
                    if (connected_pin == null) {
                        Log.e(Tag,
                              """Pin ${descriptor_of_connected_pin.affinityAndId.boardId}:
                                  |${descriptor_of_connected_pin.affinityAndId.idxOnBoard} 
                                  |is null!""".trimMargin())

                        continue
                    }

                    string_builder.append(", ${connected_pin.descriptor.getPrettyName()}")
                }

                cell_for_this_pin_connections.setCellValue(string_builder.toString())
                row_counter++
            }

            column_counter++
        }

        workbook.write(outputStream)
        outputStream?.close()
    }

    private fun test_parse() {
        val file_storage_uri = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(PreferencesFragment.Companion.SharedPreferenceKey.ResultsFileUri.text, "")

        if (file_storage_uri == "") {
            toast("No file for storage selected! Go to settings and set it via: Specify file where to store results")
            return
        }

        val inputStream = context.contentResolver.openInputStream(Uri.parse(file_storage_uri))
        val workbook = XSSFWorkbook(inputStream)
        val sheet = workbook.getSheetAt(0)

        val rows = sheet.rowIterator()
        while (rows.hasNext()) {
            val row = rows.next()
            val cells = row.cellIterator()
            while (cells.hasNext()) {
                val cell = cells.next()
                val cellValue = cell.stringCellValue
                Log.d(Tag, cellValue)
            }
        }

        inputStream?.close()
    }
}