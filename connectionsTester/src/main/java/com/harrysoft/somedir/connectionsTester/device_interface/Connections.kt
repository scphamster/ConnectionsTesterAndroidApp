package com.harrysoft.somedir.connectionsTester.device_interface

import java.lang.ref.WeakReference

typealias IoBoardIndexT = Int
typealias PinNumT = Int

data class PinGroup(val id: Int, val name: String? = null) {
    fun getPrettyName(): String {
        if (name != null) {
            return name
        }
        else {
            return id.toString()
        }
    }
}
data class PinAffinityAndId(val boardId: IoBoardIndexT, val idxOnBoard: PinNumT)
data class PinDescriptor(val affinityAndId: PinAffinityAndId,
                         val customIdx: PinNumT? = null,
                         val name: String? = null,
                         val group: PinGroup? = null) {
    fun getPrettyName() : String {
        val string_builder = StringBuilder()

        if (group!=null) {
            string_builder.append(group.getPrettyName())
        }
        else {
            string_builder.append(affinityAndId.boardId.toString())
        }

        string_builder.append(":")

        if (name!=null){
            string_builder.append(name)
        }
        else {
            string_builder.append(affinityAndId.idxOnBoard.toString())
        }

        return string_builder.toString()
    }
}

data class Pin(val descriptor: PinDescriptor,
               var isConnectedTo: MutableList<PinDescriptor> = mutableListOf(),
               var belongsToBoard: WeakReference<IoBoard> = WeakReference<IoBoard>(null))

data class IoBoard(val id: IoBoardIndexT, val pins: MutableList<Pin> = mutableListOf()) {
    companion object {
        const val pinsCountOnSingleBoard = 32
    }
}

data class Connections(val pin: Pin, var connectedWith: MutableList<Pin>) {}
