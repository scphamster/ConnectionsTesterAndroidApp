package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.preference.PreferenceManager
import com.github.scphamster.bluetoothConnectionsTester.*
import kotlinx.coroutines.*

import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter.Commands
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.*

class MeasurementsHandler(errorHandler: ErrorHandler,
                          bluetoothBridge: BluetoothBridge,
                          private val context: Context,
                          val coroutineScope: CoroutineScope) {

    enum class PinConnectionsStatus {
        AlteredConnectionsList,
        DoubleChecked,
        Unhealthy,
        SubstantialDifferenceFound
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
                it.setColor(xssfColorFromInt(context.getColor(R.color.modified_connection)))
            }
        }
        val font_for_unhealthy_pins by lazy {
            XSSFFont().also {
                it.setColor(xssfColorFromInt(context.getColor(R.color.unhealthy_pin)))
            }
        }
        val font_good_value by lazy { XSSFFont().also { it.color = IndexedColors.GREEN.index } }

        private fun setCellStyle(cell: Cell, workbook: XSSFWorkbook, status: PinConnectionsStatus) {
            val style = workbook.createCellStyle()
            when (status) {
                PinConnectionsStatus.AlteredConnectionsList -> {
                    style.setFillForegroundColor(
                        xssfColorFromInt(context.getColor(R.color.pin_with_altered_connections)))
                }

                PinConnectionsStatus.DoubleChecked -> {
                    style.setFillForegroundColor(
                        xssfColorFromInt(context.getColor(R.color.pin_with_double_checked_connections)))
                }

                PinConnectionsStatus.Unhealthy -> {
                    style.setFillForegroundColor(xssfColorFromInt(context.getColor(R.color.unhealthy_pin)))
                }

                PinConnectionsStatus.SubstantialDifferenceFound -> {
                    style.setFillForegroundColor(
                        xssfColorFromInt(context.getColor(R.color.pincheck_substantial_difference_found)))
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

        private fun convertPinConnectionDifferencesToRichText(pin: Pin,
                                                              max_resistance: Float): Pair<XSSFRichTextString, Boolean> {
            val header = pin.descriptor.getPrettyName() + " ::"
            val rich_text = XSSFRichTextString(header).also { it.applyFont(font_normal) }

            val not_present_connections = pin.notPresentExpectedConnections

            var substantial_difference_found = false

            if (not_present_connections != null) {
                if (!not_present_connections.isEmpty()) {
                    rich_text.append(" Not present: (")

                    for (connection in not_present_connections) {
                        rich_text.append(connection.toString() + ' ', font_for_unhealthy_pins)
                    }

                    rich_text.append(")")
                    substantial_difference_found = true
                }
                else {
                    rich_text.append(" All present", font_good_value)
                }
            }
            else {
                rich_text.append(" not checked")
            }

            val unexpected_connection = pin.unexpectedConnections
            if (unexpected_connection != null) {
                if (!unexpected_connection.isEmpty()) {
                    val string_builder = StringBuilder()
                    for (connection in unexpected_connection) {
                        if (connection.resistance != null) if (connection.resistance.value < max_resistance) string_builder.append(
                            connection.toString() + ' ')
                        else string_builder.append(connection.toString() + ' ')
                    }

                    if (string_builder.length != 0) {
                        rich_text.append(", Unwanted: (")
                        rich_text.append(string_builder.toString(), font_for_unhealthy_pins)
                        rich_text.append(")")
                        substantial_difference_found = true
                    }
                    else {
                        rich_text.append(", No unwanted", font_good_value)
                    }
                }
                else {
                    rich_text.append(", No unwanted", font_good_value)
                }
            }
            return Pair(rich_text, substantial_difference_found)
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

        suspend fun storeMeasurements(maximumResistance: Float): Boolean {
            val pins_congregations = boardsManager.getPinsSortedByGroupOrAffinity()

            if (pins_congregations == null) {
                Log.e(Tag, "sorted pins array is null!")
                throw Error("Internal error")
            }

            val workbook = Storage.getWorkBookFromFile(context)
            val sheet = if (workbook.getSheet("Measurements") != null) workbook.getSheet("Measurements")
            else workbook.createSheet("Measurements")

            val names_row = sheet.getRow(0) ?: sheet.createRow(0)

            var column_counter = 0
            for (congregation in pins_congregations) {
                var max_number_of_characters_in_this_column = 0

                val cell_with_name_of_congregation =
                    names_row.getCell(column_counter) ?: names_row.createCell(column_counter)

                val name_of_congregation =
                    if (congregation.isSortedByGroup) "Group: ${congregation.getCongregationName()}"
                    else "Board Id: ${congregation.getCongregationName()}"

                val new_style = workbook.createCellStyle()
                cell_with_name_of_congregation.setCellValue(name_of_congregation)
                new_style.fillForegroundColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
                new_style.fillPattern = FillPatternType.SOLID_FOREGROUND
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

        suspend fun storeExpectedToMeasuredDifferences(maximumResistance: Float) {
            val pins_congregations = boardsManager.getPinsSortedByGroupOrAffinity()
            if (pins_congregations == null) throw (Exception("sorted pins array is  empty!"))

            val workbook = Storage.getWorkBookFromFile(context)
            val name_of_tab = "Differences"
            val sheet = if (workbook.getSheet(name_of_tab) != null) workbook.getSheet(name_of_tab)
            else workbook.createSheet(name_of_tab)

            val congregation_names_row = sheet.getRow(0) ?: sheet.createRow(0)

            var column_counter = 0
            for (congregation in pins_congregations) {
                var max_number_of_characters_in_this_column = 0

                val cell_with_name_of_congregation =
                    congregation_names_row.getCell(column_counter) ?: congregation_names_row.createCell(column_counter)

                val name_of_congregation =
                    if (congregation.isSortedByGroup) "Group: ${congregation.getCongregationName()}"
                    else "Board Id: ${congregation.getCongregationName()}"

                val new_style = workbook.createCellStyle()
                cell_with_name_of_congregation.setCellValue(name_of_congregation)
                new_style.fillForegroundColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
                new_style.fillPattern = FillPatternType.SOLID_FOREGROUND
                cell_with_name_of_congregation.cellStyle = (new_style)

                val results_start_row_number = 1
                for ((pin_counter, pin) in congregation.pins.withIndex()) {
                    val row_num = pin_counter + results_start_row_number
                    val row = sheet.getRow(row_num) ?: sheet.createRow(row_num)
                    val cell_for_this_pin_differences = row.getCell(column_counter) ?: row.createCell(column_counter)

                    val (cell_text, substantial_difference_found) = convertPinConnectionDifferencesToRichText(pin,
                                                                                                              maximumResistance)
                    cell_for_this_pin_differences.setCellValue(cell_text)
                    if (max_number_of_characters_in_this_column < cell_text.length()) max_number_of_characters_in_this_column =
                        cell_text.length()

                    if (substantial_difference_found)
                        setCellStyle(cell_for_this_pin_differences, workbook,
                                     PinConnectionsStatus.SubstantialDifferenceFound)
                    else
                        setCellStyle(cell_for_this_pin_differences, workbook, PinConnectionsStatus.DoubleChecked)
                }

                val one_char_width = 240
                val max_column_width = 60 * one_char_width
                val column_width =
                    if (one_char_width * max_number_of_characters_in_this_column > max_column_width) max_column_width
                    else one_char_width * max_number_of_characters_in_this_column

                sheet.setColumnWidth(column_counter, column_width)
                column_counter++
            }

            Storage.storeToFile(workbook, context)
        }
    }

    companion object {
        val Tag = "CommandHandler"
    }

    val boardsManager by lazy { IoBoardsManager(errorHandler) }
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

                delay(200)

                for (board in message.boardsOnLine) {
                    commander.sendCommand(ControllerResponseInterpreter.Commands.GetInternalParameters(board))
                    delay(200)
                }
            }
        }

        responseInterpreter.onInternalParametersCallback = {
            boardsManager.setInternalParametersForBoard(it.board_addr, it.internalParameters)
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
}

private fun xssfColorFromInt(color: Int): XSSFColor {
    val red = Color.red(color)
    val green = Color.green(color)
    val blue = Color.blue(color)

    return XSSFColor(byteArrayOf(red.toByte(), green.toByte(), blue.toByte()))
}