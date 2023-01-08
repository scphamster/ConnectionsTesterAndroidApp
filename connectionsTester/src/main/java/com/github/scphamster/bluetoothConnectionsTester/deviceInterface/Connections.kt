package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import java.lang.ref.WeakReference

typealias IoBoardIndexT = Int
typealias PinNumT = Int
typealias CircuitParamT = Float
typealias VoltageT = CircuitParamT
typealias ResistanceT = CircuitParamT

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

interface PinIdentifier {
    fun getPrettyName(): String
    public val pinAffinityAndId: PinAffinityAndId
}

data class PinAffinityAndId(val boardId: IoBoardIndexT, val idxOnBoard: PinNumT): PinIdentifier{
    override fun getPrettyName(): String {
        return "$boardId:$idxOnBoard"
    }

    override val pinAffinityAndId: PinAffinityAndId
        get() = PinAffinityAndId(boardId, idxOnBoard)
}
class Connection(val toPin: PinIdentifier,
                 val voltage: VoltageT? = null,
                 val resistance: ResistanceT? = null) {

}

data class PinDescriptor(val affinityAndId: PinAffinityAndId,
                         val uniqueIdx: PinNumT? = null,
                         var name: String? = null,
                         var group: PinGroup? = null) : PinIdentifier{
    override fun getPrettyName(): String {
        val string_builder = StringBuilder()

        if (group != null) {
            string_builder.append(group?.getPrettyName())
        }
        else {
            string_builder.append(affinityAndId.boardId.toString())
        }

        string_builder.append(":")

        if (name != null) {
            string_builder.append(name)
        }
        else {
            string_builder.append(affinityAndId.idxOnBoard.toString())
        }

        return string_builder.toString()
    }

    override val pinAffinityAndId: PinAffinityAndId
        get() = affinityAndId

    fun clearPinAndGroupNames() {
        group?.let {
            group = PinGroup(it.id, null)
        }

        name = null
    }
}

data class Pin(val descriptor: PinDescriptor,
               var connections: MutableList<Connection> = mutableListOf(),
               var belongsToBoard: WeakReference<IoBoard> = WeakReference<IoBoard>(null))

data class IoBoard(val id: IoBoardIndexT, val pins: MutableList<Pin> = mutableListOf()) {
    companion object {
        const val pinsCountOnSingleBoard = 32
    }
}

data class Connections(val pin: Pin, var connectedWith: MutableList<Pin>) {}
