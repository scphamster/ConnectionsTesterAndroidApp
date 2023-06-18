package com.github.scphamster.bluetoothConnectionsTester.device

import androidx.lifecycle.MutableLiveData
import com.github.scphamster.bluetoothConnectionsTester.device.ControllerResponseInterpreter.ControllerMessage
import java.lang.ref.WeakReference
import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class IoBoardsManager(val errorHandler: ErrorHandler, scope: CoroutineScope) {
    companion object {
        const val Tag = "IoBoardsManagerLive"
        private const val STANDARD_INPUT_R: ResistanceT = 1100f
        private const val STANDARD_OUTPUT_R: ResistanceT = 210f
        private const val STANDARD_SHUNT_R: ResistanceT = 330f
        private const val STANDARD_OUTPUT_VOLTAGE_LOW: VoltageT = 0.69f
        private const val STANDARD_OUTPUT_VOLTAGE_HIGH: VoltageT = 0.93f
    }
    
    data class SortedPins(val group: PinGroup? = null, val affinity: BoardAddrT? = null, val pins: Array<Pin>) {
        fun getCongregationName(): String {
            return group?.getPrettyName() ?: (affinity?.toString() ?: "FAILGROUP")
        }
        
        val isSortedByGroup: Boolean = group != null
    }
    
    enum class VoltageLevel(val byteValue: Byte) {
        Low(21),
        High(20)
    }
    
    val boards = MutableLiveData<MutableList<IoBoard>>()
    val pinoutInterpreter = PinoutFileInterpreter()
    val pinConnectivityResultsCh = Channel<SimpleConnectivityDescription>(Channel.UNLIMITED)
    val boardsArrayChannel = Channel<Array<IoBoard>>(10)
    
    private var voltageLevel: VoltageLevel = VoltageLevel.Low
    var pinChangeCallback: ((Pin) -> Unit)? = null
    private var nextUniqueGroupId = 0
        get() {
            val id = field
            field++
            return id
        }
    
    var maxResistanceAsConnection = 0f

    init {
        scope.launch(Dispatchers.Default) {
            newPinConnectivityResultsReceiverTask()
        }
        scope.launch {
            boardsReceiverTask()
        }
    }
    
    private fun getNamedPinGroups(): Array<PinGroup>? {
        val boards = boards.value ?: return null

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
    
    private fun getAllPinsFromGroup(group: PinGroup): Array<Pin>? {
        val boards = boards.value ?: return null

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
        val boards = boards.value ?: return null

        if (boards.isEmpty()) return null
        
        val groups = getNamedPinGroups()
        val pins_sorted_by_group = mutableListOf<SortedPins>()
        groups?.let { it ->
            for (group in it) {
                val pins_from_this_group = getAllPinsFromGroup(group)
                pins_from_this_group?.let {pins ->
                    val sorted_pins = SortedPins(group, pins = pins)
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
            
            val sorted_pins =
                SortedPins(group = null, affinity = board.address, pins = pins_without_group.toTypedArray())
            pins_sorted_by_affinity.add(sorted_pins)
        }
        pins_sorted_by_group.addAll(pins_sorted_by_affinity)
        
        return pins_sorted_by_group.toTypedArray()
    }

    private suspend fun updateConnectionsForPin(updated_pin: Pin, connections: Array<Connection>) {
        val Tag = "$Tag:PCU"
        
        val new_connections = mutableListOf<Connection>()
        
        for (found_connection in connections) {
            val connection = if (found_connection.raw != null) getConnectionFromRawForPin(updated_pin, found_connection)
            else found_connection
            
            val affinity_and_id = connection.toPin.pinAffinityAndId
            val connected_to_pin = findPinRefByAffinityAndId(affinity_and_id)
            
            if (connected_to_pin == null) {
                Log.e(Tag, "Pin not found! ${affinity_and_id.boardAddress}:${affinity_and_id.pinID}")
                return
            }
            
            val descriptor_of_connected_pin = connected_to_pin.get()?.descriptor
            if (descriptor_of_connected_pin == null) {
                Log.e(Tag, "descriptor is null!")
                return
            }
            
            val previous_connection_to_this_pin = updated_pin.getConnection(affinity_and_id)
            
            val differs_from_previous = connection.checkIfDifferent(previous_connection_to_this_pin) ?: false
            val first_occurrence = previous_connection_to_this_pin == null

            val new_connection = Connection(descriptor_of_connected_pin,
                                            connection.voltage,
                                            connection.resistance,
                                            connection.raw,
                                            differs_from_previous,
                                            first_occurrence)
            
            new_connections.add(new_connection)
            Log.i(Tag, "Searched pin Found! ${affinity_and_id.boardAddress}:${affinity_and_id.pinID}")
        }
        
        updated_pin.connectionsListChangedFromPreviousCheck =
            updated_pin.checkIfConnectionsListIsDifferent(new_connections, maxResistanceAsConnection)
        updated_pin.connections = new_connections
        
        Log.d(Tag, "pinchange callback invocation for pin ${updated_pin.toString()}")
        withContext(Dispatchers.Main) {
            pinChangeCallback?.invoke(updated_pin)
        }
    }
    
    suspend fun updateConnectionsForPin(masterPin: PinAffinityAndId, connections: Array<SimpleConnection>) {
        val pin = findPinRefByAffinityAndId(masterPin)?.get()
        
        if (pin == null) {
            Log.e(Tag, "Pin not found in UpdateConnectionsForPin")
            return
        }

        val newConnections =
            connections.map { connection -> (Connection(connection.toPin, raw = connection.voltage.value.toInt())) }
                .toTypedArray()
        
        updateConnectionsForPin(pin, newConnections)
    }
    
    suspend fun updateConnectionsByControllerMsg(connections_description: ControllerMessage.ConnectionsDescription) {
        val updated_pin_ref = findPinRefByAffinityAndId(connections_description.ofPin)
        
        if (updated_pin_ref == null) {
            Log.e(Tag,
                  "Pin with descriptor: ${connections_description.ofPin.boardAddress}:${connections_description.ofPin.pinID} is not found!")
            
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
            if (board.address != affinityAndId.boardAddress) continue
            
            if (board.pins.isEmpty()) {
                Log.e(Tag, "board with id:${board.address} has empty set of pins!")
                return null
            }
            
            for (pin in board.pins) {
                if (pin.descriptor.affinityAndId.pinID == affinityAndId.pinID) return WeakReference(pin)
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

        for (board in current_boards) {
            for (pin in board.pins) {
                if (pin.descriptor.pinAffinityAndId.pinID.toString() == name && board.address.toString() == group) {
                    pin_with_board_id_same_as_group_name = pin
                }
                if (pin.descriptor.name == name && pin.descriptor.group?.name == group) {
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

    fun getAllPins(): List<Pin> {
        val boards = boards.value
        
        if (boards == null) return emptyList()
        
        val pins = mutableListOf<Pin>()
        
        boards.forEach { board ->
            pins.addAll(board.pins)
        }
        
        return pins
    }
    
    suspend fun updateIOBoards(boards_id: Array<BoardAddrT>) {
        val new_boards = mutableListOf<IoBoard>()
        
        for (id in boards_id) {
            val new_board = IoBoard(id, voltageLevel = voltageLevel)
            
            new_boards.add(new_board)
        }
        withContext(Dispatchers.Main) {
            boards.value = new_boards
        }
        
        fetchPinsInfoFromExcelToPins()
    }
    
    suspend fun updateIOBoards(boards: Array<IoBoard>) {
        withContext(Dispatchers.Main) {
            ::boards.get().value = boards.toMutableList()
        }
        fetchPinsInfoFromExcelToPins()
    }
    
    suspend fun fetchPinsInfoFromExcelToPins() = withContext(Dispatchers.Default) {
        val boards = boards.value
        if (boards == null) return@withContext
        if (boards.isEmpty()) return@withContext
        
        Log.d(Tag, "entering fetch procedure")
        Log.d(Tag, "checking for null document: Is null? = ${pinoutInterpreter.document == null}")
        
        val pinout_interpretation = try {
            pinoutInterpreter.getInterpretation()
        }
        catch (e: PinoutFileInterpreter.BadFileException) {
            errorHandler.handleError("Error ${e.message}")
            Log.e(Tag, "${e.message}")
            return@withContext
        }
        catch (e: Throwable) {
            errorHandler.handleError("Unknown error: ${e.message}")
            Log.e(Tag, "${e.message}")
            return@withContext
        }
        
        Log.d(Tag, "checking for null document2: Is null? = ${pinoutInterpreter.document == null}")
        Log.d(Tag, "Interpretation obtained, is null? : ${pinout_interpretation == null}")
        
        if (pinout_interpretation == null) return@withContext
        
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
                    return@withContext
                }
                
                pin.descriptor.group = logicPinGroup
                pin.descriptor.name = pin_mapping.key
            }
        }
        
        //update visual representation of pins names
        withContext(Dispatchers.Main) {
            ::boards.get().value = boards
        }
        
        fetchExpectedConnectionsToPinsFromFile()
    }
    
    suspend fun fetchExpectedConnectionsToPinsFromFile() = withContext(Dispatchers.Default) {
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
            return@withContext
        }
        
        Log.d(Tag, "Expected connection are not null, size = ${expected_connections.size} proceeding!")
        for (connections_for_pin in expected_connections) {
            Log.d(Tag, "Searching for pin: ${connections_for_pin}")
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
    //tasks
    suspend fun boardsReceiverTask() = withContext(Dispatchers.Default) {
        val Tag = Tag + ":BRT"
        
        while (isActive) {
            val boards = boardsArrayChannel.receiveCatching()
                .getOrNull()
            
            if (boards == null) {
                Log.e(Tag, "boards are null!")
                continue
            }
            
            updateIOBoards(boards)
        }
    }
    
    suspend fun newPinConnectivityResultsReceiverTask() = withContext(Dispatchers.Default) {
        val Tag = Tag + ":PCT"
        
        while (isActive) {
            val newResult = pinConnectivityResultsCh.receiveCatching()
                .getOrNull()
            
            if (newResult == null) {
                Log.e(Tag, "pin connectivity result is null!")
                continue
            }
            
            try {
                updateConnectionsForPin(newResult.masterPin, newResult.connections)
                Log.d(Tag, "new connections arrived for pin :${newResult.masterPin} ")
            }
            catch (e: Exception) {
                Log.e(Tag, "Exception caught while updating connections for pin: ${e.message}")
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
