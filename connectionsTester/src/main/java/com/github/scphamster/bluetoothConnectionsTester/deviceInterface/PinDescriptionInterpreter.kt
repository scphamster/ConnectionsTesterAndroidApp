package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import android.util.Log

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
        private const val groupHeaderToActualDataVerticalIndent = 2
        private const val Tag = "PinDecoder"
    }

    class BadFileException(msg: String) : Exception(msg)
    data class Group(val name: String, val pinsMap: Map<String, PinAffinityAndId>)
    class Interpretation(val pinGroups: List<Group>) {
//        data class DuplicatesDescription(val numberOfDuplicates: Int)

//        fun checkForDuplicates() {
//            val used_boards_with_pins = mutableMapOf<Int, MutableList<Int>>()
//            val used_groups_with_pins = mutableMapOf<String, MutableList<String>>()
//            var number_of_duplicates = 0
//            val duplicated_group_names = mutableListOf<String>()
//
//
//            for (pin_group in pinGroups) {
//                if (used_groups_with_pins.contains(pin_group.name)) {
//                    number_of_duplicates++
//
//                    if (!duplicated_group_names.contains(pin_group.name))
//                        duplicated_group_names.add(pin_group.name)
//                }
//
//
//
//                used_groups_with_pins[pin_group.name] = mutableListOf()
//            }
//        }

    }

    private class GroupsManager(val groups: MutableList<Group> = mutableListOf()) {
        fun addNewGroup(new_group: Group) {
            for (group in groups) {
                if (new_group.name == group.name) {
                    throw Exception("Duplicate group names found: ${group.name}!")
                }
            }

            if (groupHasDuplicatedPinAffinities(new_group))
                throw Exception("Group ${new_group.name} has duplicated pin affinities")

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
        if (document == null){
            Log.e(Tag, "Document is NULL")
            return null
        }

        Log.d(Tag, "Start of decode...")

        val sheet = document.getSheetAt(0) ?: throw BadFileException("document has no sheets!")

        val cells_with_group_names = findCellsWithGroupHeadersAtSheet(sheet)

        val groups_manager = GroupsManager()
        if (cells_with_group_names == null) throw BadFileException("No cells with group names found!")

        for (cell in cells_with_group_names) {
            val group = getGroupFromSheetAtCell(sheet, cell)
            if (group != null)
                groups_manager.addNewGroup(group)
        }

        if (groups_manager.groups.isEmpty()) return null

        return Interpretation(groups_manager.groups)
    }

    fun getInterpretation(): Interpretation? {
        return getInterpretation(document)
    }

    private fun findCellsWithGroupHeadersAtSheet(sheet: XSSFSheet): Array<Cell>? {
        val first_row = sheet.getRow(0) ?: return null

        val cell_iterator = first_row.cellIterator()
        if (!cell_iterator.hasNext()) return null

        val list_of_cells_with_group_headers = mutableListOf<Cell>()

        while (cell_iterator.hasNext()) {
            val cell = cell_iterator.next()
            if (cell.stringCellValue.contains(groupHeaderTag)) {
                list_of_cells_with_group_headers.add(cell)
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
            if (cell_probably_with_mapping.cellType == CellType.BLANK) continue

            val mapping = getSinglePinMappingFromCell(cell_probably_with_mapping) ?: continue

            if (pins_mappings.contains(mapping.first)) {
                throw (BadFileException("Duplicate pin found at: ${
                    CellReference(row_idx, usable_data_begins_at_column)
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