package com.harrysoft.androidbluetoothserial.demoapp.device_interface

import java.lang.ref.WeakReference

data class PinGroup(val groupId: Int, val groupName: String? = null)
data class PinAffinityAndId(val boardAffinityId: IoBoardIndexT, val idxOnBoard: PinNumT)
data class PinDescriptor(val affinityAndId: PinAffinityAndId,
                         val customIdx: PinNumberT? = null,
                         val name: String? = null,
                         val group: PinGroup? = null)

data class Pin(val descriptor: PinDescriptor,
               var isConnectedTo: MutableList<PinDescriptor> = mutableListOf(),
               var belongsToBoard: WeakReference<IoBoard> = WeakReference<IoBoard>(null))

data class IoBoard(val id: IoBoardIndexT, val pins: MutableList<Pin> = mutableListOf()) {
    companion object {
        const val pinsCountOnSingleBoard = 32
    }
}

data class Connections(val pin: Pin, var connectedWith: MutableList<Pin>) {}