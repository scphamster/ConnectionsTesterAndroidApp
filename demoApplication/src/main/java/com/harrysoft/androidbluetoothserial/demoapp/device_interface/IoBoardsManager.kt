package com.harrysoft.androidbluetoothserial.demoapp.device_interface

import androidx.lifecycle.MutableLiveData
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.CommandInterpreter.ControllerMessage
import java.lang.ref.WeakReference
import android.util.Log

class IoBoardsManager() {
    companion object {
        val Tag = "IoBoardsManagerLive"
    }

    data class SortedPins(val group: PinGroup? = null,
                          val affinity: IoBoardIndexT? = null,
                          val pins: Array<Pin>? = null)

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

    private fun findPinRefByAffinityAndId(affinityAndId: PinAffinityAndId): WeakReference<Pin>? {
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
}
