package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import java.lang.ref.WeakReference

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.github.scphamster.bluetoothConnectionsTester.*
import com.jaiselrahman.filepicker.model.MediaFile
import kotlinx.coroutines.*
import org.apache.poi.ss.usermodel.IndexedColors

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.util.*
import kotlin.math.max

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

    //new
    val commander = Commander(bluetoothBridge, context)

    init {
        responseInterpreter.onConnectionsDescriptionCallback = { new_connections ->
            boardsManager.updatePinConnections(new_connections)
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

    //todo: refactor
    suspend fun storeMeasurementsResultsToFile(): Boolean {
        val pins_congregations = boardsManager.getPinsSortedByGroupOrAffinity()

        if (pins_congregations == null) {
            Log.e(Tag, "sorted pins array is null!")
            throw Error("Internal error")
        }

        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Measurements")
        val names_row = sheet.getRow(0) ?: sheet.createRow(0)

        var column_counter = 0

        for (congregation in pins_congregations) {
            var max_number_of_characters_in_this_column = 0

            val cell_with_name_of_congregation =
                names_row.getCell(column_counter) ?: names_row.createCell(column_counter)

            val name_of_congregation = if (congregation.isSortedByGroup) "Group: ${congregation.getCongregationName()}"
            else "BoardId: ${congregation.getCongregationName()}"

            cell_with_name_of_congregation.setCellValue(name_of_congregation)

            var row_counter = 1
            for (pin in congregation.pins) {
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
                        Log.e(Tag, """Pin ${descriptor_of_connected_pin.affinityAndId.boardId}:
                                  |${descriptor_of_connected_pin.affinityAndId.idxOnBoard} 
                                  |is not found""".trimMargin())

                        continue
                    }

                    val connected_pin = _pin.get()
                    if (connected_pin == null) {
                        Log.e(Tag, """Pin ${descriptor_of_connected_pin.affinityAndId.boardId}:
                                  |${descriptor_of_connected_pin.affinityAndId.idxOnBoard} 
                                  |is null!""".trimMargin())

                        continue
                    }

                    string_builder.append(", ${connected_pin.descriptor.getPrettyName()}")
                }

                if (max_number_of_characters_in_this_column < string_builder.length) max_number_of_characters_in_this_column =
                    string_builder.length

                cell_for_this_pin_connections.setCellValue(string_builder.toString())
                row_counter++
            }

            //todo: add preference to make this action configurable
//            sheet.setColumnWidth(column_counter, max_number_of_characters_in_this_column)
            sheet.setColumnWidth(column_counter, 240 * max_number_of_characters_in_this_column)
//            sheet.setColumnWidth(column_counter, 5000)
//            val calendar = Calendar.getInstance()
//            val millis = calendar.time.toString()
//            Log.d(Tag, "Watermark0: $column_counter ${millis}")
            column_counter++
        }

        Storage.storeToFile(workbook, Dispatchers.IO, context)

        return true
    }
}