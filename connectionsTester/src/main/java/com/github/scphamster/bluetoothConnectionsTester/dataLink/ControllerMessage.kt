package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.BoardAddrT
import com.github.scphamster.bluetoothConnectionsTester.circuit.PinAffinityAndId
import com.github.scphamster.bluetoothConnectionsTester.circuit.SimpleConnection

sealed class Msg {
    companion object {
        const val Tag = "Msg"
        fun deserialize(byteArray: ByteArray): Msg? {
            val byteIt = byteArray.iterator()
            
            var byteCounter = 0
            
            if (!byteIt.hasNext()) Log.e(Tag, "Zero elements in message byte array!")
            
            var byte = byteIt.nextByte()
            
            val msg = when (byte.toInt()) {
                1 -> {
                    if (byteArray.size - 1 != OperationConfirmation.SIZE_BYTES) {
                        Log.e(Tag,
                              "Message with id: ${byte.toInt()} has improper size: Expected: ${OperationConfirmation.SIZE_BYTES + 1}, Got: ${byteArray.size}")
                    }
                    
                    OperationConfirmation(byteIt.nextByte())
                }
                
                else -> null
            }
            
            return msg
        }
    }
    
    class OperationConfirmation(val confirmationValue: Byte) : Msg() {
        companion object {
            const val ID = 1
            const val SIZE_BYTES = 1
        }
    }
}

interface MTC {
    abstract fun serialize() : ArrayList<Byte>
}
final class MeasureAllCommand : MTC {
    companion object {
        const val COMMAND_SIZE_BYTES = Byte.SIZE_BYTES.toByte()
        const val CMD_ID: Byte = 100
    }
    
    override fun serialize() : ArrayList<Byte> {
        val bytes = ArrayList<Byte>()
        bytes.add(CMD_ID)
        return bytes
    }
}

sealed class MessageFromController {
    enum class Type(val id: Byte) {
        Connections(50),
    }
    
    companion object {
        private const val Tag = "MsgFromController"
        fun deserialize(bytes: List<Byte>): MessageFromController? {
            if (bytes.size == 0) {
                Log.e(Tag, "empty byte list!")
                return null
            }
            
            val id = bytes.get(0)
            
            return when (id) {
                Type.Connections.id -> Connectivity.deserialize(bytes.slice(1..(bytes.size - 1)))
                else -> null
            }
        }
    }
    
    class Connectivity(val masterPin: PinAffinityAndId,
                       val connections: List<SimpleConnection>) : MessageFromController() {
        companion object {
            const val SIZE_BYTES = 3
            fun deserialize(bytes: List<Byte>): Connectivity {
                val pin = PinAffinityAndId.deserialize(bytes.slice(0..1))
                val number_of_connections = bytes.get(2)
                
                if (number_of_connections == 0.toByte()) return Connectivity(pin, emptyList<SimpleConnection>())
                
                val _connections = mutableListOf<SimpleConnection>()
                
                var startByte = 3
                var endByte = startByte + SimpleConnection.SIZE_BYTES - 1
                for (idx in 1..number_of_connections) {
                    _connections.add(SimpleConnection.deserialize(bytes.slice(startByte..endByte)))
                    
                    startByte += SimpleConnection.SIZE_BYTES
                    endByte += SimpleConnection.SIZE_BYTES
                }
                
                return Connectivity(pin, _connections.toList())
            }
        }
        
        override fun toString(): String {
            val str = masterPin.toString()
            
            return str + connections.joinToString { connection -> "${connection.toPin.toString()}(${connection.voltage})" }
        }
    }
    
    class Status(val boards: List<BoardAddrT>)
}