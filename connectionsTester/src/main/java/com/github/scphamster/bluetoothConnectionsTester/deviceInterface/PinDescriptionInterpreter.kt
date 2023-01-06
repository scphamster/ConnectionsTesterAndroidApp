package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class PinDescriptionInterpreter {
    companion object {
        private const val groupHeaderTag = "Group: "
        private const val minBoardIndex = 0
        private const val maxBoardIndex = 255
        private const val maxPinIndexOnBoard = 31
        private const val minPinIndexOnBoard = 0
        private const val groupHeaderToActualDataHorizontalIndent = 2
    }

    class BadFileSyntaxException(msg: String) : Exception(msg)
    data class Group(val name: String, val pinsMap: Map<String, PinAffinityAndId>)
    data class Interpretation(val pinGroups: List<Group>)

    var document: XSSFWorkbook? = null
        set(value) {
            field = value
//            fetchPinsInfoFromExcelToPins()
        }

    fun getInterpretation(document: XSSFWorkbook?): Interpretation? {
        if (document == null) return null

        val sheet = document?.getSheetAt(0)
        if (sheet == null) return null

        val cells_with_group_names = findCellsWithGroupHeadersAtSheet(sheet)
        val groups = mutableListOf<Group>()

        if (cells_with_group_names == null) return null

        for (cell in cells_with_group_names) {
            val group = getGroupFromSheetAtCell(sheet, cell)
            if (group != null) groups.add(group)
        }

        if (groups.isEmpty()) return null

        return Interpretation(groups)
    }

    fun getInterpretation(): Interpretation? {
        return getInterpretation(document)
    }

    private fun findCellsWithGroupHeadersAtSheet(sheet: XSSFSheet): Array<Cell>? {
        val first_row = sheet.getRow(0)
        if (first_row == null) return null

        val cell_iterator = first_row.cellIterator()
        if (!cell_iterator.hasNext()) return null

        val list_of_cells_with_group_headers = mutableListOf<Cell>()

        while (cell_iterator.hasNext()) {
            val cell = cell_iterator.next()
            if (cell.stringCellValue.contains(groupHeaderTag)) {
                list_of_cells_with_group_headers.add(cell)
            }
        }

        if (list_of_cells_with_group_headers.isEmpty()) return null
        else return list_of_cells_with_group_headers.toTypedArray()
    }

    private fun getGroupFromSheetAtCell(sheet: XSSFSheet, cell: Cell): Group? {
        val group_header_cell_content = cell.getStringRepresentationOfValue()
        val group_name = group_header_cell_content
            .trim()
            .substring(groupHeaderTag.length)
        if (group_name.isEmpty()) return null

        val usable_data_begins_at_row = cell.rowIndex + groupHeaderToActualDataHorizontalIndent
        val usable_data_begins_at_column = cell.columnIndex

        val pins_mappins = mutableMapOf<String, PinAffinityAndId>()

        for ((row_idx, row) in sheet
            .rowIterator()
            .withIndex()) {
            if (row_idx < usable_data_begins_at_row) continue

            val cell_probably_with_mapping = row.getCell(usable_data_begins_at_column)
            if (cell_probably_with_mapping == null) continue
            val mapping = getSinglePinMappingFromCell(cell_probably_with_mapping)
            if (mapping == null) continue

            if (pins_mappins.contains(mapping.first)) {
                throw (BadFileSyntaxException("Duplicate pin found at: ${
                    CellReference(row_idx, usable_data_begins_at_column)
                }"))
            }

            pins_mappins[mapping.first] = mapping.second
        }

        if (pins_mappins.isEmpty()) return null
        return Group(group_name, pins_mappins.toMap())
    }

    private fun getSinglePinMappingFromCell(cell: Cell): Pair<String, PinAffinityAndId>? {
        val row_affinity_of_cell = cell.row

        val pin_name = cell.getStringRepresentationOfValue().trim()
        if (pin_name.length == 0) return null

        val cell_with_board_affinity = row_affinity_of_cell.getCell(cell.columnIndex + 1)
        if (cell_with_board_affinity == null) return null
        val board_affinity = cell_with_board_affinity.numericCellValue.toInt()
//        if (board_affinity == null) return null
        if (board_affinity > maxBoardIndex || board_affinity < minBoardIndex) return null

        val cell_with_pin_idx_on_board = row_affinity_of_cell.getCell(cell.columnIndex + 2)
        if (cell_with_pin_idx_on_board == null) return null
        val pin_index_on_board = cell_with_pin_idx_on_board.numericCellValue.toInt()
//        if (pin_index_on_board == null) return null
        if (pin_index_on_board > maxPinIndexOnBoard || pin_index_on_board < minPinIndexOnBoard) return null

        return Pair(pin_name, PinAffinityAndId(board_affinity, pin_index_on_board))
    }
}

private fun Cell.getStringRepresentationOfValue(): String {
    if (cellType == CellType.NUMERIC) {
        return numericCellValue.toInt().toString()
    }
    else if (cellType == CellType.STRING) {
        return stringCellValue
    }
    else {
        throw Exception("Group header from cell: ${
            CellReference(rowIndex, columnIndex)
        } is not of string nor integer type")
    }
}