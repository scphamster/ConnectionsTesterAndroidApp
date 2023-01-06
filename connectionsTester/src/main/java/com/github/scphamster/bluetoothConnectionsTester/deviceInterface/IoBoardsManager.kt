package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import androidx.lifecycle.MutableLiveData
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter.ControllerMessage
import java.lang.ref.WeakReference
import android.util.Log

class IoBoardsManager(val errorHandler: ErrorHandler) {
    companion object {
        val Tag = "IoBoardsManagerLive"
    }

    data class SortedPins(val group: PinGroup? = null, val affinity: IoBoardIndexT? = null, val pins: Array<Pin>) {
        fun getGroupName(): String {
            group?.let {
                return it.getPrettyName()
            }

            return affinity.toString()
        }
    }

    val boards = MutableLiveData<MutableList<IoBoard>>()
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
    private var nextUniquePinId = 0
        get() {
            val id = field
            field++
            return id
        }

    val pinDescriptionInterpreter by lazy { PinDescriptionInterpreter() }

    fun getPinGroups(): Array<PinGroup>? {
        val boards = boards.value

        if (boards == null) return null
        if (boards.isEmpty()) return null

        val groups = mutableListOf<PinGroup>()

        for (board in boards) {
            if (board.pins.isEmpty()) continue

            for (pin in board.pins) {
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

        val groups = getPinGroups()
        val pins_sorted_by_group = mutableListOf<SortedPins>()
        groups?.let {
            for (group in it) {
                val pins_for_this_group = getAllPinsFromGroup(group)
                pins_for_this_group?.let {
                    val sorted_pins = SortedPins(group, pins = it)
                    pins_sorted_by_group.add(sorted_pins)
                }
            }
        }

        val pins_sorted_by_affinity = mutableListOf<SortedPins>()
        for (board in boards) {
            val pins_without_group = mutableListOf<Pin>()

            for (pin in board.pins) {
                if (pin.descriptor.group != null) continue

                pins_without_group.add(pin)
            }

            if (pins_without_group.isEmpty()) continue

            val sorted_pins = SortedPins(null, board.id, pins_without_group.toTypedArray())
            pins_sorted_by_affinity.add(sorted_pins)
        }

        pins_sorted_by_group.addAll(pins_sorted_by_affinity)
        return pins_sorted_by_group.toTypedArray()
    }

    fun updatePinConnections(connections: ControllerMessage.ConnectionsDescription) {
        val updated_pin = findPinRefByAffinityAndId(connections.testedPin)

        if (updated_pin == null) {
            Log.e(Tag,
                  "Pin with descriptor: ${connections.testedPin.boardId}:${connections.testedPin.idxOnBoard} is not found!")

            return
        }

        val descriptors_of_connected_pins = mutableListOf<PinDescriptor>()

        for (affinity_and_id_of_pin in connections.connectedTo) {
            val pin = findPinRefByAffinityAndId(affinity_and_id_of_pin)

            if (pin == null) {
                Log.e(Tag, "Pin not found! ${affinity_and_id_of_pin.boardId}:${affinity_and_id_of_pin.idxOnBoard}")

                return
            }
            val descriptor = pin.get()?.descriptor
            if (descriptor == null) {
                Log.e(Tag, "descriptor is null!")
                return
            }

            descriptors_of_connected_pins.add(descriptor)
            Log.i(Tag, "Searched pin Found! ${affinity_and_id_of_pin.boardId}:${affinity_and_id_of_pin.idxOnBoard}")
        }

        updated_pin.get()?.isConnectedTo = descriptors_of_connected_pins

        val pin = updated_pin.get()
        if (pin == null) {
            Log.e(Tag, "Pin is null!")
            return
        }

        pinChangeCallback?.invoke(pin)
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

    //new
    suspend fun updateIOBoards(boards_id: Array<IoBoardIndexT>) {
        val new_boards = mutableListOf<IoBoard>()
        var boards_counter = 0

        for (board in boards_id) {
            val new_board = IoBoard(board)
            val new_pin_group = PinGroup(board)

            for (pin_num in 0..(IoBoard.pinsCountOnSingleBoard - 1)) {
                val descriptor =
                    PinDescriptor(PinAffinityAndId(board, pin_num), group = new_pin_group, uniqueIdx = nextUniquePinId)

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

        val pinout_interpretation = try {
            pinDescriptionInterpreter.getInterpretation()
        }
        catch (e: PinDescriptionInterpreter.BadFileSyntaxException) {
            errorHandler.handleError("Error ${e.message}")
            return
        }

        if (pinout_interpretation == null) return

        Log.d(Tag, "Fetching pins from xlsx file")
        for (group in pinout_interpretation.pinGroups) {
            val logicPinGroup = PinGroup(nextUniqueGroupId, group.name)
            Log.d(Tag, "PinGroup.name = ${logicPinGroup.name}")

            Log.d(Tag, " pins properties:")
            for (pin_mapping in group.pinsMap) {
                val pin_ref = findPinRefByAffinityAndId(pin_mapping.value)
                Log.d(Tag,
                      "  name: ${pin_mapping.key}, affinity: ${pin_mapping.value.boardId}, id: ${pin_mapping.value.idxOnBoard}")


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
    }
}
