package com.harrysoft.androidbluetoothserial.demoapp.device_interface

import androidx.lifecycle.MutableLiveData
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.CommandInterpreter.ControllerMessage
import java.lang.ref.WeakReference
import android.util.Log

typealias IoBoardIndexT = Int

class IoBoardsManagerLive {
    companion object {
        val Tag = "IoBoardsManagerLive"
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

    fun updatePinConnections(connections: ControllerMessage.ConnectionsDescription) {
        val updated_pin = findPinByAffinityAndId(connections.pin)

        if (updated_pin == null) {
            Log.e(Tag,
                  "Pin with descriptor: ${connections.pin.boardAffinityId}:${connections.pin.idxOnBoard} is not found!")

            return
        }

        val descriptors_of_connected_pins = mutableListOf<PinDescriptor>()

        for (affinity_and_id_of_pin in connections.connectedTo) {
            val pin = findPinByAffinityAndId(affinity_and_id_of_pin)

            if (pin == null) {
                Log.e(Tag,
                      "Pin not found! ${affinity_and_id_of_pin.boardAffinityId}:${affinity_and_id_of_pin.idxOnBoard}")

                return
            }
            val descriptor = pin.get()?.descriptor
            if (descriptor == null) {
                Log.e(Tag, "descriptor is null!")
                return
            }

            descriptors_of_connected_pins.add(descriptor)
            Log.i(Tag,"Searched pin Found! ${affinity_and_id_of_pin.boardAffinityId}:${affinity_and_id_of_pin.idxOnBoard}")
        }

        updated_pin.get()?.isConnectedTo = descriptors_of_connected_pins

        val pin = updated_pin.get()
        if (pin == null) {
            Log.e(Tag, "Pin is null!")
            return
        }

        pinChangeCallback?.invoke(pin)
    }
    fun getBoardsCount() : Int {
        return boards.value?.size ?: 0
    }
    private fun findPinByAffinityAndId(affinityAndId: PinAffinityAndId): WeakReference<Pin>? {
        val available_boards = boards.value
        if (available_boards == null) return null

        for (board in available_boards) {
            if (board.id != affinityAndId.boardAffinityId) continue

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
