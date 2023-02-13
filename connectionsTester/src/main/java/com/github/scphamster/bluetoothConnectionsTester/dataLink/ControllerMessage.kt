package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.BoardAddrT
import com.github.scphamster.bluetoothConnectionsTester.circuit.PinAffinityAndId
import com.github.scphamster.bluetoothConnectionsTester.circuit.SimpleConnection
import com.github.scphamster.bluetoothConnectionsTester.device.IoBoardsManager

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

interface MasterToControllerMsg {
    abstract fun serialize(): Collection<Byte>
    abstract val msg_id: Byte
    
    enum class MessageID(val id: Byte) {
        MeasureAll(100),
        EnableOutputForPin(101),
        SetOutputVoltageLevel(102),
        CheckConnections(103),
        CheckResistances(104),
        CheckVoltages(105),
        CheckRaw(106),
        GetBoardsOnline(107),
        GetInternalCounter(108),
        GetTaskStackWatermark(109),
        SetNewAddressForBoard(110),
        SetInternalParameters(111),
        GetInternalParameters(112),
        Test(113),
    }
}

final class MeasureAllCommand : MasterToControllerMsg {
    companion object {
        const val COMMAND_SIZE_BYTES = Byte.SIZE_BYTES.toByte()
        val CMD_ID: Byte = MasterToControllerMsg.MessageID.MeasureAll.id
    }
    
    override val msg_id = MasterToControllerMsg.MessageID.MeasureAll.id
    override fun serialize(): Collection<Byte> {
        val bytes = ArrayList<Byte>()
        bytes.add(CMD_ID)
        return bytes
    }
}

final class SetOutputVoltageLevel(val level: IoBoardsManager.VoltageLevel) : MasterToControllerMsg {
    companion object {
        private val CMD_ID = MasterToControllerMsg.MessageID.SetOutputVoltageLevel.id
        private const val SIZE_BYTES = Byte.SIZE_BYTES * 2
    }
    
    override val msg_id = MasterToControllerMsg.MessageID.SetOutputVoltageLevel.id
    override fun serialize(): Collection<Byte> {
        val bytes = mutableListOf<Byte>()
        bytes.add(CMD_ID)
        bytes.add(level.byteValue)
        return bytes
    }
}

final class GetBoardsOnline : MasterToControllerMsg {
    override val msg_id = MasterToControllerMsg.MessageID.GetBoardsOnline.id
    override fun serialize(): Collection<Byte> {
        return arrayListOf(msg_id)
    }
}

sealed class MessageFromController {
    enum class Type(val id: Byte) {
        Connections(50),
        OperationConfirmation(51),
        BoardsInfo(52),
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
                Type.OperationConfirmation.id -> OperationConfirmation(bytes.slice(1..(bytes.size - 1)))
                Type.BoardsInfo.id -> Boards(bytes.slice(1..(bytes.size - 1)))
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
    
    class OperationConfirmation() : MessageFromController() {
        lateinit var response: ControllerResponse
            private set
        
        constructor(bytes: Collection<Byte>) : this() {
            val byteValue = (bytes.toByteArray()).get(0)
            val enumConstant = ControllerResponse.values()
                .find { enumVal -> enumVal.byteValue == byteValue }
            
            if (enumConstant == null) {
                throw (IllegalArgumentException("Operation confirmation unsuccessful creation, byteValue: $byteValue"))
            }
            
            response = enumConstant
        }
    }
    
    class Boards() : MessageFromController() {
        companion object {
            private const val MAX_ADDRESS = 127.toByte()
        }
        
        lateinit var boards: Array<BoardAddrT>
        
        constructor(bytes: Collection<Byte>) : this() {
            if (bytes.isEmpty()) return
         
            val new_boards = mutableListOf<BoardAddrT>()
            new_boards.addAll(bytes.map { byte ->
                if (byte > MAX_ADDRESS) throw IllegalArgumentException("Board address is higher than $MAX_ADDRESS : $byte")
                
                byte.toInt()
            })
            boards = new_boards.toTypedArray()
        }
    }
}