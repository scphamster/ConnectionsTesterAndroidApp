package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import java.lang.ref.WeakReference

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.*
import com.jaiselrahman.filepicker.model.MediaFile
import kotlinx.coroutines.*

import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter.Commands
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.*
import java.lang.Exception

typealias CommandArgsT = Int

class MeasurementsHandler(errorHandler: ErrorHandler,
                          bluetoothBridge: BluetoothBridge,
                          private val context: Context,
                          val coroutineScope: CoroutineScope) {

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
    enum class PinConnectionsStatus {
        AlteredConnectionsList,
        DoubleChecked,
        Unhealthy
    }

    enum class PinConnectionStatus {
        FirstOccurrence,
        DoubleChecked,
        ValueChanged,
    }

    inner class MeasurementsToFileSaver {
        val font_normal by lazy { XSSFFont() }
        val font_for_connection_with_changes by lazy {
            XSSFFont().also {
                it.setColor(XSSFColor().fromInt(context.getColor(R.color.modified_connection)))
            }
        }
        val font_for_unhealthy_pins by lazy {
            XSSFFont().also {
                it.setColor(context
                                .getColor(R.color.unhealthy_pin)
                                .toShort())
            }
        }

        private fun setCellStyle(cell: Cell, workbook: XSSFWorkbook, status: PinConnectionsStatus) {
            val style = workbook.createCellStyle()
            val borderBottom = style.borderBottom
            val borderTop = style.borderTop
            val borderLeft = style.borderLeft
            val borderRight = style.borderRight

            when (status) {
                PinConnectionsStatus.AlteredConnectionsList -> {
                    style.setFillForegroundColor(
                        XSSFColor().fromInt(context.getColor(R.color.pin_with_altered_connections)))
                }

                PinConnectionsStatus.DoubleChecked -> {
                    style.setFillForegroundColor(
                        XSSFColor().fromInt(context.getColor(R.color.pin_with_double_checked_connections)))
                }

                PinConnectionsStatus.Unhealthy -> {
                    style.setFillForegroundColor(XSSFColor().fromInt(context.getColor(R.color.unhealthy_pin)))
                }
            }

            style.fillPattern = FillPatternType.SOLID_FOREGROUND

            style.borderBottom = BorderStyle.THIN
            style.borderTop = BorderStyle.THIN
            style.borderLeft = BorderStyle.THIN
            style.borderRight = BorderStyle.THIN
            val to_wrap = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean("xlsx_wrap_long_text", true)
            style.wrapText = to_wrap

            cell.cellStyle = style
        }

        private fun convertPinConnectionsToRichText(pin: Pin, max_resistance: Float): XSSFRichTextString {
            val header = "${pin.descriptor.getPrettyName()} -> "
            val rich_text = XSSFRichTextString(header).also { it.applyFont(font_normal) }

            if (!pin.isHealthy) {
                rich_text.append("UNHEALTHY!", font_for_unhealthy_pins)
            }
            else if (pin.connections.size == 1 && pin.hasConnection(pin.descriptor.pinAffinityAndId) && pin.isHealthy) {
                rich_text.append("NC")
            }
            else for (connection in pin.connections) {
                if (connection.toPin.pinAffinityAndId == pin.descriptor.affinityAndId) continue

                //do not print if resistance is higher than max resistance (user defined)
                val connection_as_string = if (connection.resistance != null) {
                    if (connection.resistance.value < max_resistance) connection.toString() + ' '
                    else ""
                }
                else connection.toString()

                if (connection.value_changed_from_previous_check) {
                    rich_text.append(connection_as_string, font_for_connection_with_changes)
                }
                else {
                    rich_text.append(connection_as_string, font_normal)
                }
            }

            return rich_text
        }

        suspend fun storeMeasurementsResultsToFile(maximumResistance: Float): Boolean {
            val pins_congregations = boardsManager.getPinsSortedByGroupOrAffinity()

            if (pins_congregations == null) {
                Log.e(Tag, "sorted pins array is null!")
                throw Error("Internal error")
            }

            val workbook = Storage.getWorkBookFromFile(context)

            Log.d(Tag, "Marker-1")
            val sheet = if (workbook.getSheet("Measurements") != null) workbook.getSheet("Measurements")
            else workbook.createSheet("Measurements")

            Log.d(Tag, "Marker0")
            Log.d(Tag, "Number of sheets: ${workbook.numberOfSheets}")
            val names_row = sheet.getRow(0) ?: sheet.createRow(0)

            var column_counter = 0
            for (congregation in pins_congregations) {
                var max_number_of_characters_in_this_column = 0

                val cell_with_name_of_congregation =
                    names_row.getCell(column_counter) ?: names_row.createCell(column_counter)

                val name_of_congregation =
                    if (congregation.isSortedByGroup) "Group: ${congregation.getCongregationName()}"
                    else "Board Id: ${congregation.getCongregationName()}"

                Log.d(Tag, "Congregation: $name_of_congregation")

                val new_style = workbook.createCellStyle()
                val bot_border = new_style.borderBottom
                cell_with_name_of_congregation.setCellValue(name_of_congregation)
                new_style.setFillBackgroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.index)
                new_style.setFillPattern(FillPatternType.SQUARES)
                cell_with_name_of_congregation.cellStyle = (new_style)

                val results_start_row_number = 1
                for ((pin_counter, pin) in congregation.pins.withIndex()) {
                    val row_num = pin_counter + results_start_row_number
                    val row = sheet.getRow(row_num) ?: sheet.createRow(row_num)
                    val cell_for_this_pin_connections = row.getCell(column_counter) ?: row.createCell(column_counter)


                    if (!pin.isHealthy) setCellStyle(cell_for_this_pin_connections, workbook,
                                                     PinConnectionsStatus.Unhealthy)
                    else if (pin.connectionsListChangedFromPreviousCheck) setCellStyle(cell_for_this_pin_connections,
                                                                                       workbook,
                                                                                       PinConnectionsStatus.AlteredConnectionsList)
                    else setCellStyle(cell_for_this_pin_connections, workbook, PinConnectionsStatus.DoubleChecked)

                    val cell_text = convertPinConnectionsToRichText(pin, maximumResistance)
                    cell_for_this_pin_connections.setCellValue(cell_text)
                    if (max_number_of_characters_in_this_column < cell_text.length()) max_number_of_characters_in_this_column =
                        cell_text.length()
                }

                val one_char_width = 260
                val max_column_width = 60 * one_char_width
                val column_width =
                    if (one_char_width * max_number_of_characters_in_this_column > max_column_width) max_column_width
                    else one_char_width * max_number_of_characters_in_this_column

                sheet.setColumnWidth(column_counter, column_width)
                column_counter++
            }

            Storage.storeToFile(workbook, context)

            return true
        }
    }

    companion object {
        //        val Tag = this::class.simpleName.toString()
        val Tag = "CommandHandler"
        const val unused: Int = -1
    }

    var numberOfConnectedBoards = MutableLiveData<BoardCountT>()
        private set
        get
    val boardsManager by lazy { IoBoardsManager(errorHandler) }
    var outputFile: MediaFile? = null
    val responseInterpreter by lazy { ControllerResponseInterpreter() }
    val resultsSaver by lazy { MeasurementsToFileSaver() }
    private var connectionDescriptorMessageCounter = 0

    val commander = Commander(bluetoothBridge, context)

    init {
        responseInterpreter.onConnectionsDescriptionCallback = { new_connections ->
            boardsManager.updateConnectionsByControllerMsg(new_connections)
            connectionDescriptorMessageCounter++
        }
        responseInterpreter.onHardwareDescriptionCallback = { message ->
            coroutineScope.launch {
                boardsManager.updateIOBoards(message.boardsOnLine)
            }
        }

        commander.dataLink.onMessageReceivedCallback = { msg ->
            responseInterpreter.handleMessage(msg)
        }
    }

    suspend fun calibrate(completion_callback: ((String) -> Unit)) {
        val pin_count = boardsManager.getBoardsCount() * IoBoard.pinsCountOnSingleBoard
        if (pin_count == 0) completion_callback("Fail, no boards found yet")

        connectionDescriptorMessageCounter = 0
        commander.sendCommand(Commands.CheckConnectivity(Commands.CheckConnectivity.AnswerDomain.Resistance))
        val max_delay_for_result_arrival_ms = 1000


        withContext(Dispatchers.Default) {
            var pin_descriptor_messages_count_last_check = connectionDescriptorMessageCounter

            while (true) {
                delay(max_delay_for_result_arrival_ms.toLong())

                if (connectionDescriptorMessageCounter == pin_descriptor_messages_count_last_check) {
                    if (connectionDescriptorMessageCounter == pin_count) {
//                        boardsManager.calibrate()
                        completion_callback("Success, calibrated!")
                        return@withContext
                    }
                    else {
                        completion_callback("Fail! Only $pin_descriptor_messages_count_last_check descriptors arrived!")
                        return@withContext
                    }
                }
                else {
                    pin_descriptor_messages_count_last_check = connectionDescriptorMessageCounter
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast
            .makeText(context, msg, Toast.LENGTH_LONG)
            .show()
    }

    private fun boardsInitializer(boards_id: Array<IoBoardIndexT>) {
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
}

private fun XSSFColor.fromInt(color: Int): XSSFColor {
    val red = Color.red(color)
    val green = Color.green(color)
    val blue = Color.blue(color)

    return XSSFColor(byteArrayOf(red.toByte(), green.toByte(), blue.toByte()))
}