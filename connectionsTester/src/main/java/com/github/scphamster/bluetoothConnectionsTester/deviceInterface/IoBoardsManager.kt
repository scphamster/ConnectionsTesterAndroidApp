package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import androidx.lifecycle.MutableLiveData
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter.ControllerMessage
import java.lang.ref.WeakReference
import android.util.Log
import org.apache.poi.hpsf.UnexpectedPropertySetTypeException
import kotlin.math.exp

class IoBoardsManager(val errorHandler: ErrorHandler) {
    companion object {
        val Tag = "IoBoardsManagerLive"
        private const val STANDARD_INPUT_R: ResistanceT = 1100f
        private const val STANDARD_OUTPUT_R: ResistanceT = 210f
        private const val STANDARD_SHUNT_R: ResistanceT = 330f
        private const val STANDARD_OUTPUT_VOLTAGE_LOW: VoltageT = 0.69f
        private const val STANDARD_OUTPUT_VOLTAGE_HIGH: VoltageT = 0.93f
    }

    data class SortedPins(val group: PinGroup? = null, val affinity: IoBoardIndexT? = null, val pins: Array<Pin>) {
        fun getCongregationName(): String {
            if (group != null) return group.getPrettyName()
            else if (affinity != null) return affinity.toString()
            else return "FAILGROUP"
        }

        val isSortedByGroup: Boolean = group != null
        val isSortedByAffinity: Boolean = affinity != null
    }

    enum class VoltageLevel {
        Low,
        High
    }

    val boards = MutableLiveData<MutableList<IoBoard>>()
    var voltageLevel: VoltageLevel = VoltageLevel.Low
    val pinoutInterpreter: PinoutFileInterpreter
    private val boardsCount = MutableLiveData<Int>()
    var pinChangeCallback: ((Pin) -> Unit)? = null
    var nextUniqueBoardId = 0
        private set
        get() {
            val id = field
            field++
            return id
        }
    var nextUniqueGroupId = 0
        private set
        get() {
            val id = field
            field++
            return id
        }

    var maxResistanceAsConnection = 0f

    private var nextUniquePinId = 0
        get() {
            val id = field
            field++
            return id
        }

    init {
        pinoutInterpreter = PinoutFileInterpreter()
    }

    fun getNamedPinGroups(): Array<PinGroup>? {
        val boards = boards.value

        if (boards == null) return null
        if (boards.isEmpty()) return null

        val groups = mutableListOf<PinGroup>()

        for (board in boards) {
            if (board.pins.isEmpty()) continue

            for (pin in board.pins) {
                if (pin.descriptor.group?.name == null) continue

                if (!groups.contains(pin.descriptor.group)) {
                    pin.descriptor.group?.let {
                        groups.add(it)
                    }
                }
            }
        }

        return groups.toTypedArray()
    }

    fun getAllPinsFromGroup(group: PinGroup): Array<Pin>? {
        val boards = boards.value

        if (boards == null) return null
        if (boards.isEmpty()) return null

        val pins = mutableListOf<Pin>()

        for (board in boards) {
            for (pin in board.pins) {
                if (pin.descriptor.group == group) {
                    pins.add(pin)
                }
            }
        }

        return pins.toTypedArray()
    }

    fun getPinsSortedByGroupOrAffinity(): Array<SortedPins>? {
        val boards = boards.value

        if (boards == null) return null
        if (boards.isEmpty()) return null

        val groups = getNamedPinGroups()
        val pins_sorted_by_group = mutableListOf<SortedPins>()
        groups?.let {
            for (group in it) {
                val pins_from_this_group = getAllPinsFromGroup(group)
                pins_from_this_group?.let {
                    val sorted_pins = SortedPins(group, pins = it)
                    pins_sorted_by_group.add(sorted_pins)
                }
            }
        }

        val pins_sorted_by_affinity = mutableListOf<SortedPins>()
        for (board in boards) {
            val pins_without_group = mutableListOf<Pin>()

            for (pin in board.pins) {
                if (pin.descriptor.group?.name != null) continue

                pins_without_group.add(pin)
            }

            if (pins_without_group.isEmpty()) continue

            val sorted_pins = SortedPins(group = null, affinity = board.id, pins = pins_without_group.toTypedArray())
            pins_sorted_by_affinity.add(sorted_pins)
        }
        pins_sorted_by_group.addAll(pins_sorted_by_affinity)

        return pins_sorted_by_group.toTypedArray()
    }

    fun setOutputVoltageLevelForBoards(level: VoltageLevel) {
        voltageLevel = level

        val boards = boards.value
        if (boards == null) return

        for (board in boards) {
            board.voltageLevel = level
            val internals = board.internalParams

            if (internals != null) {
                for (pin in board.pins) {
                    pin.outVoltage = if (level == VoltageLevel.High) internals.outputVoltageHigh
                    else internals.outputVoltageLow
                }
            }
        }
    }

    private fun getConnectionFromRawForPin(pin: Pin, connection: Connection): Connection {
        if (connection.raw == null) throw Exception("raw is null!")

        val pin_of_connection = findPinRefByAffinityAndId(connection.toPin.pinAffinityAndId)
        val input_resistance = pin_of_connection?.get()?.inResistance ?: STANDARD_INPUT_R

        val output_resistance = pin.outResistance ?: STANDARD_OUTPUT_R
        val shunt_r = pin_of_connection?.get()?.shuntResistance ?: STANDARD_SHUNT_R
        val master_board = pin.belongsToBoard.get()

        val voltage_level = if (master_board == null) {
            Log.e(Tag, "master board is null!")
            VoltageLevel.Low
        }
        else master_board.voltageLevel

        val output_voltage = if (voltage_level == VoltageLevel.High) pin.outVoltage ?: STANDARD_OUTPUT_VOLTAGE_HIGH
        else pin.outVoltage ?: STANDARD_OUTPUT_VOLTAGE_LOW

        val sensed_voltage = (connection.raw / 1023f) * 1.1f
        val circuit_current = sensed_voltage / shunt_r
        val overall_resistance = output_voltage / circuit_current
        val sensed_resistance = overall_resistance - input_resistance - output_resistance - shunt_r
        Log.d(Tag, "sensed Voltage: $sensed_voltage, resistance = $sensed_resistance")

        return Connection(connection.toPin, Voltage(sensed_voltage), Resistance(sensed_resistance), connection.raw)
    }

    fun updateConnectionsForPin(updated_pin: Pin, connections: Array<Connection>) {
        val new_connections = mutableListOf<Connection>()

        for (found_connection in connections) {
            val connection = if (found_connection.raw != null)
                getConnectionFromRawForPin(updated_pin, found_connection)
            else found_connection

            val affinity_and_id = connection.toPin.pinAffinityAndId
            val connected_to_pin = findPinRefByAffinityAndId(affinity_and_id)

            if (connected_to_pin == null) {
                Log.e(Tag, "Pin not found! ${affinity_and_id.boardId}:${affinity_and_id.idxOnBoard}")
                return
            }

            val descriptor_of_connected_pin = connected_to_pin.get()?.descriptor
            if (descriptor_of_connected_pin == null) {
                Log.e(Tag, "descriptor is null!")
                return
            }

            val previous_connection_to_this_pin = updated_pin.getConnection(affinity_and_id)

            val differs_from_previous = connection.checkIfDifferent(previous_connection_to_this_pin) ?: false
            val first_occurrence = previous_connection_to_this_pin == null;

            val new_connection =
                Connection(descriptor_of_connected_pin, connection.voltage, connection.resistance, connection.raw,
                           differs_from_previous, first_occurrence)

            new_connections.add(new_connection)
            Log.i(Tag, "Searched pin Found! ${affinity_and_id.boardId}:${affinity_and_id.idxOnBoard}")
        }

        updated_pin.connectionsListChangedFromPreviousCheck =
            updated_pin.checkIfConnectionsListIsDifferent(new_connections, maxResistanceAsConnection)
        updated_pin.connections = new_connections

        pinChangeCallback?.invoke(updated_pin)
    }

    fun updateConnectionsByControllerMsg(connections_description: ControllerMessage.ConnectionsDescription) {
        val updated_pin_ref = findPinRefByAffinityAndId(connections_description.ofPin)

        if (updated_pin_ref == null) {
            Log.e(Tag,
                  "Pin with descriptor: ${connections_description.ofPin.boardId}:${connections_description.ofPin.idxOnBoard} is not found!")

            return
        }

        val updated_pin = updated_pin_ref.get()
        if (updated_pin == null) return

        updateConnectionsForPin(updated_pin, connections_description.connections)
    }

    fun getBoardsCount(): Int {
        return boards.value?.size ?: 0
    }

    fun findPinRefByAffinityAndId(affinityAndId: PinAffinityAndId): WeakReference<Pin>? {
        val available_boards = boards.value
        if (available_boards == null) return null

        for (board in available_boards) {
            if (board.id != affinityAndId.boardId) continue

            if (board.pins.isEmpty()) {
                Log.e(Tag, "board with id:${board.id} has empty set of pins!")
                return null
            }

            for (pin in board.pins) {
                if (pin.descriptor.affinityAndId.idxOnBoard == affinityAndId.idxOnBoard) return WeakReference(pin)
            }

            return null
        }

        return null
    }

    fun findPinByGroupAndName(group: String, name: String): Pin? {
        val current_boards = boards.value
        if (current_boards == null) {
            Log.e(Tag, "findPinByGroupAndName: boards are null!")
            return null
        }

        var pin_with_board_id_same_as_group_name: Pin? = null
        var pin_with_group_and_name_as_requested: Pin? = null

        for (board in current_boards) {
            for (pin in board.pins) {
                if (pin.descriptor.pinAffinityAndId.idxOnBoard.toString() == name && board.id.toString() == group) {
                    pin_with_board_id_same_as_group_name = pin
                }
                if (pin.descriptor.name == name && pin.descriptor.group?.name == group){
                    Log.d(Tag, "findPinByGroupAndName: found pin ${pin.toString()}")
                    return pin
                }
            }
        }

        Log.e(Tag, "Did not found pin by group and pin name $group : $name")

        if (pin_with_board_id_same_as_group_name != null) {
            Log.d("pinSearch",
                  "found pin without group: ${pin_with_board_id_same_as_group_name.descriptor.pinAffinityAndId}")
            return pin_with_board_id_same_as_group_name
        }

        return null
    }

    fun calibrate() {
        val boards = boards.value
        boards?.let {
            for (board in boards) {
                for (pin in board.pins) {
                    //todo: implement
                }
            }
        }
    }

    fun setInternalParametersForBoard(board_addr: Int, board_internals: IoBoardInternalParameters) {
        val boards = boards.value

        if (boards == null) {
            Log.e(Tag, "setInternalParametersForBoard::boards are null!")
            return
        }

        for (board in boards) {
            if (board.id == board_addr) {
                board.internalParams = board_internals

                for (pin in board.pins) {
                    val logical_pin_num = pin.descriptor.getLogicalPinNumber()
                    if (logical_pin_num == null) {
                        Log.e(Tag, "logical pin num is null!")
                        continue
                    }

                    val single_mux_pin_count = 16
                    val is_of_second_mux = logical_pin_num >= single_mux_pin_count

                    if (is_of_second_mux) {
                        pin.inResistance = board_internals.inputResistance1
                        pin.outResistance = board_internals.outputResistance1
                    }
                    else {
                        pin.inResistance = board_internals.inputResistance0
                        pin.outResistance = board_internals.outputResistance0
                    }

                    pin.outVoltage = if (board.voltageLevel == VoltageLevel.High) board_internals.outputVoltageHigh
                    else board_internals.outputVoltageLow

                    pin.shuntResistance = board_internals.shuntResistance
                }

                Log.d("Test",
                      "Board ${board.id} internals set!: ${board_internals.inputResistance0},${board_internals.outputResistance0},${board_internals.outputVoltageHigh}")
            }
        }
    }

    fun getAllPins() : List<Pin>
    {
        val boards = boards.value

        if (boards == null) return emptyList()

        val pins = mutableListOf<Pin>()

        boards.forEach { board ->
            pins.addAll(board.pins)
        }

        return pins
    }

    suspend fun updateIOBoards(boards_id: Array<IoBoardIndexT>) {
        val new_boards = mutableListOf<IoBoard>()
        var boards_counter = 0

        for (id in boards_id) {
            val new_board = IoBoard(id, voltageLevel = voltageLevel)
            val new_pin_group = PinGroup(id)

            for (pin_num in 0..(IoBoard.pinsCountOnSingleBoard - 1)) {
                val descriptor =
                    PinDescriptor(PinAffinityAndId(id, pin_num), group = new_pin_group, UID = nextUniquePinId)

                val new_pin = Pin(descriptor, belongsToBoard = WeakReference(new_board))
                new_board.pins.add(new_pin)
            }

            new_boards.add(new_board)
            boards_counter++
        }

        boards.value = new_boards

        fetchPinsInfoFromExcelToPins()
    }

    suspend fun fetchPinsInfoFromExcelToPins() {
        val boards = boards.value
        if (boards == null) return
        if (boards.isEmpty()) return

        Log.d(Tag, "entering fetch procedure")
        Log.d(Tag, "checking for null document: Is null? = ${pinoutInterpreter.document == null}")

        val pinout_interpretation = try {
            pinoutInterpreter.getInterpretation()
        }
        catch (e: PinoutFileInterpreter.BadFileException) {
            errorHandler.handleError("Error ${e.message}")
            Log.e(Tag, "${e.message}");
            return
        }
        catch (e: Throwable) {
            errorHandler.handleError("Unknown error: ${e.message}")
            Log.e(Tag, "${e.message}");
            return
        }

        Log.d(Tag, "checking for null document2: Is null? = ${pinoutInterpreter.document == null}")
        Log.d(Tag, "Interpretation obtained, is null? : ${pinout_interpretation == null}")

        if (pinout_interpretation == null) return

        cleanAllPinsAndGroupsNaming_silently()
        Log.d(Tag, "all pins names and groups names cleared")

        for (group in pinout_interpretation.pinGroups) {
            val logicPinGroup = PinGroup(nextUniqueGroupId, group.name)

            for (pin_mapping in group.pinsMap) {
                val pin_ref = findPinRefByAffinityAndId(pin_mapping.value)


                if (pin_ref == null) continue
                val pin = pin_ref.get()
                if (pin == null) {
                    Log.e(Tag, "Pin is null!")
                    return
                }

                pin.descriptor.group = logicPinGroup
                pin.descriptor.name = pin_mapping.key
            }
        }

        this.boards.value = boards

        fetchExpectedConnectionsToPinsFromFile()
    }

    suspend fun fetchExpectedConnectionsToPinsFromFile() {
        getAllPins().forEach() { pin ->
            pin.expectedConnections = null
            pin.unexpectedConnections = null
            pin.notPresentExpectedConnections = null
        }


        Log.d(Tag, "Entering fetch expected connections!")
        val expected_connections = try {
            pinoutInterpreter.getExpectedConnections()
        }
        catch (e: Exception) {
            errorHandler.handleError(e.message)
            null
        }

        if (expected_connections == null) {
            Log.e(Tag, "expected connections are null")
            return
        }

        Log.d(Tag, "Expected connection are not null, size = ${expected_connections.size} proceeding!")
        for (connections_for_pin in expected_connections) {
            Log.d(Tag,"Searching for pin: ${connections_for_pin}")
            findPinByGroupAndName(connections_for_pin.for_pin.first,
                                  connections_for_pin.for_pin.second)?.let { pin_of_interest ->
                val expected = mutableListOf<Connection>()
                Log.d(Tag, "expected connections for pin entered: ${connections_for_pin.for_pin}")
                for (pin_expected_to_be_connected in connections_for_pin.is_connected_to) {
                    Log.d(Tag, "Searching for expected pin: ${pin_expected_to_be_connected.toString()}")
                    findPinByGroupAndName(pin_expected_to_be_connected.first,
                                          pin_expected_to_be_connected.second)?.let {
                        Log.d(Tag, "found pin: ${it.toString()} in fetch expected!")
                        expected.add(Connection(it.descriptor))
                    }
                }

                Log.d(Tag, "Found ${expected.size} expected connection for pin ${connections_for_pin.for_pin}")

                pin_of_interest.expectedConnections = expected
            }
        }
    }

    private fun cleanAllPinsAndGroupsNaming_silently() {
        val boards = boards.value
        if (boards == null) return

        for (board in boards) {
            if (board.pins.isEmpty()) continue

            for (pin in board.pins) {
                pin.descriptor.clearPinAndGroupNames()
            }
        }
    }
}
