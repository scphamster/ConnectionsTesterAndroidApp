package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import android.util.Log

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook

data class ExpectedConnections(val for_pin: Pair<String, String>, val is_connected_to: List<Pair<String, String>>)

class PinoutFileInterpreter {
    companion object {
        private const val groupHeaderTag = "Group: "
        private const val minBoardIndex = 0
        private const val maxBoardIndex = 255
        private const val maxPinIndexOnBoard = 31
        private const val minPinIndexOnBoard = 0
        private const val groupHeaderToActualDataVerticalIndent = 2
        private const val Tag = "PinDecoder"
    }

    class BadFileException(msg: String) : Exception(msg)
    data class Group(val name: String, val pinsMap: Map<String, PinAffinityAndId>)
    data class Interpretation(val pinGroups: List<Group>)

    private class GroupsManager(val groups: MutableList<Group> = mutableListOf()) {
        fun addNewGroup(new_group: Group) {
            for (group in groups) {
                if (new_group.name == group.name) {
                    throw Exception("Duplicate group names found: ${group.name}!")
                }
            }

            if (groupHasDuplicatedPinAffinities(new_group)) throw Exception(
                "Group ${new_group.name} has duplicated pin affinities")

            for ((pin_name, affinity_and_id) in new_group.pinsMap.entries) {
                if (checkIfPinAffinityIsNotOccupied(affinity_and_id)) {
                    throw Exception("""Duplicate hardware pin usage for 
                                |pin group: ${new_group.name}, 
                                |logical pin: ${pin_name}, 
                                |hw pin: ${affinity_and_id.idxOnBoard}, 
                                |board: ${affinity_and_id.boardId}""".trimMargin())
                }
            }

            groups.add(new_group)
        }

        private fun groupHasDuplicatedPinAffinities(group: Group): Boolean {
            val used_pins = mutableListOf<PinAffinityAndId>()

            for ((_, affinity_and_id) in group.pinsMap.entries) {
                if (used_pins.contains(affinity_and_id)) return true

                used_pins.add(affinity_and_id)
            }

            return false
        }

        private fun checkIfPinAffinityIsNotOccupied(affinityAndId: PinAffinityAndId): Boolean {
            for (group in groups) {
                for ((_, pin_affinity_and_id) in group.pinsMap.entries) {
                    if (pin_affinity_and_id == affinityAndId) return true
                }
            }

            return false
        }
    }

    var document: XSSFWorkbook? = null
        set(doc) {
            Log.d(Tag, "Workbook set, is null? ${doc == null}")
            field = doc
        }

    private fun getInterpretation(document: XSSFWorkbook?): Interpretation? {
        if (document == null) {
            Log.e(Tag, "Document is NULL")
            return null
        }

        val sheet = if (document.getSheet("Description") != null) document.getSheet("Description")
        else throw (BadFileException("Specified file does not have \"Description\" tab!"))

        val cells_with_group_names = findCellsWithGroupHeadersAtSheet(sheet)

        val groups_manager = GroupsManager()
        if (cells_with_group_names == null) throw BadFileException("No cells with group names found!")

        for (cell in cells_with_group_names) {
            val group = getGroupFromSheetAtCell(sheet, cell)
            if (group != null) groups_manager.addNewGroup(group)
        }

        if (groups_manager.groups.isEmpty()) return null

        return Interpretation(groups_manager.groups)
    }

    fun getInterpretation(): Interpretation? {
        return getInterpretation(document)
    }

    fun getExpectedConnections(): List<ExpectedConnections>? {

        val doc = document

        if (doc == null) {
            Log.d(Tag, "expected connections read fail, document is null")
            return null
        }

        val sheet = doc.getSheet("ExpectedResults")
        if (sheet == null) {
            throw (BadFileException("File does not have \"ExpectedResults\" tab!"))
        }

        val row_iterator = sheet.rowIterator()

        val list_of_expected_connections: MutableList<ExpectedConnections> = mutableListOf()

        while (row_iterator.hasNext()) {
            val row = row_iterator.next()
            val cell_iterator = row.cellIterator()
            while (cell_iterator.hasNext()) {
                val cell = cell_iterator.next()


                cell
                    .parseForExpectedConnections()
                    ?.let { expected_connection ->
                        if (list_of_expected_connections.find {
                                expected_connection.for_pin == it.for_pin
                            } != null) {
                            throw BadFileException(
                                "Duplicated results for same pin ${expected_connection.for_pin}! Duplication found at cell: ${
                                    CellReference(cell)
                                }")
                        }

                        list_of_expected_connections.add(expected_connection)
                    }
            }
        }

        return list_of_expected_connections
    }

    private fun findCellsWithGroupHeadersAtSheet(sheet: XSSFSheet): Array<Cell>? {
        val first_row = sheet.getRow(0) ?: return null

        val list_of_cells_with_group_headers = mutableListOf<Cell>()

        val row_iterator = sheet.rowIterator()
        while (row_iterator.hasNext()) {
            val row = row_iterator.next()

            val cell_iterator = row.cellIterator()
            while (cell_iterator.hasNext()) {
                val cell = cell_iterator.next()

                if (cell.cellType == CellType.STRING) {
                    if (cell.stringCellValue.contains(groupHeaderTag)) {
                        list_of_cells_with_group_headers.add(cell)
                    }
                }
            }
        }

        return if (list_of_cells_with_group_headers.isEmpty()) null
        else list_of_cells_with_group_headers.toTypedArray()
    }

    private fun getGroupFromSheetAtCell(sheet: XSSFSheet, cell: Cell): Group? {
        val group_header_cell_content = cell.getStringRepresentationOfValue()
        val group_name = group_header_cell_content
            .trim()
            .substring(groupHeaderTag.length)
        if (group_name.isEmpty()) return null

        val usable_data_begins_at_row = cell.rowIndex + groupHeaderToActualDataVerticalIndent
        val usable_data_begins_at_column = cell.columnIndex

        val pins_mappings = mutableMapOf<String, PinAffinityAndId>()

        for ((row_idx, row) in sheet
            .rowIterator()
            .withIndex()) {
            if (row_idx < usable_data_begins_at_row) continue

            val cell_probably_with_mapping = row.getCell(usable_data_begins_at_column) ?: continue
            if (cell_probably_with_mapping.cellType == CellType.BLANK) break

            val mapping = getSinglePinMappingFromCell(cell_probably_with_mapping) ?: continue

            if (pins_mappings.contains(mapping.first)) {
                throw (BadFileException("Duplicate pin descriptor found at: ${
                    CellReference(cell_probably_with_mapping)
                }"))
            }

            pins_mappings[mapping.first] = mapping.second
        }

        if (pins_mappings.isEmpty()) return null
        return Group(group_name, pins_mappings.toMap())
    }

    private fun getSinglePinMappingFromCell(cell: Cell): Pair<String, PinAffinityAndId>? {
        val row_affinity_of_cell = cell.row

        val pin_name = cell
            .getStringRepresentationOfValue()
            .trim()
        if (pin_name.isEmpty()) return null

        val cell_with_board_affinity = row_affinity_of_cell.getCell(cell.columnIndex + 1) ?: return null
        val board_affinity = cell_with_board_affinity.numericCellValue.toInt()
//        if (board_affinity == null) return null
        if (board_affinity > maxBoardIndex || board_affinity < minBoardIndex) return null

        val cell_with_pin_idx_on_board = row_affinity_of_cell.getCell(cell.columnIndex + 2) ?: return null
        val pin_index_on_board = cell_with_pin_idx_on_board.numericCellValue.toInt()
//        if (pin_index_on_board == null) return null
        if (pin_index_on_board > maxPinIndexOnBoard || pin_index_on_board < minPinIndexOnBoard) return null

        return Pair(pin_name, PinAffinityAndId(board_affinity, pin_index_on_board))
    }
}

private fun Cell.getStringRepresentationOfValue(): String {
    return when (cellType) {
        CellType.NUMERIC -> {
            numericCellValue
                .toInt()
                .toString()
        }

        CellType.STRING -> {
            stringCellValue
        }

        else -> {
            throw Exception("Group header from cell: ${
                CellReference(rowIndex, columnIndex)
            } is not of string nor integer type")
        }
    }
}

private fun Cell.parseForExpectedConnections(): ExpectedConnections? {
    if (cellType != CellType.STRING) return null

    val cell_text = stringCellValue

    if (!cell_text.contains("->")) return null

    val text_before_arrow = cell_text.substringBefore("->")
    val text_after_arrow = cell_text.substringAfter("->")

    val main_pin = text_before_arrow.toAffinityAndIds()
    if (main_pin.isEmpty()) return null

    val connected_to_pins = text_after_arrow.toAffinityAndIds()

    return ExpectedConnections(main_pin.get(0), connected_to_pins)
}

private fun String.toAffinityAndIds(): List<Pair<String, String>> {
    val pin_group_and_id_regex = "\\w+[:]\\w+".toRegex()
    val list_of_affinity_and_ids = pin_group_and_id_regex
        .findAll(this)
        .map { Pair(it.value.substringBefore(":"), it.value.substringAfter(":")) }
        .toList()

    return list_of_affinity_and_ids
}
