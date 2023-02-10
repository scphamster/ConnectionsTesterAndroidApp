package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.MeasurementsHandler
import com.github.scphamster.bluetoothConnectionsTester.circuit.PinAffinityAndId
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.SimpleConnection

class Message(val someArray: ByteArray, val someShort: Short) {
    companion object {
        fun deserialize(byte_array: ByteArray): Message {
            val array = byte_array.copyOfRange(0, 5)
            val shrt = ((byte_array
                .get(7)
                .toInt() and 0xff) shl 8) or ((byte_array
                .get(6)
                .toInt()) and 0xff)

            return Message(array, shrt.toShort())
        }
    }
};

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

sealed class MessageToController {
    companion object {
        const val Tag = "MessageToController"
    }

    sealed class Command : MessageToController() {
        companion object {
            const val COMMAND_TAG: Byte = 200.toByte()
        }

        class MeasureAll() : Command() {
            companion object {
                const val COMMAND_SIZE_BYTES = Int.SIZE_BYTES.toByte()
                const val COMMAND_TAG: Int = 100
            }

            fun serialize(): ByteArray {
                val mutArray = mutableListOf<Byte>()

                mutArray.add(Command.COMMAND_TAG)
                mutArray.add(COMMAND_SIZE_BYTES)
                mutArray.addAll(COMMAND_TAG
                                    .toByteArray()
                                    .asList())

                return mutArray.toByteArray()
            }
        }
    }
}

sealed class MessageFromController {
    class Connectivity(val masterPin: PinAffinityAndId, val connections: List<SimpleConnection>) :
        MessageFromController() {
        companion object {
            fun deserialize(bytes: ByteArray): Connectivity {
                val pin = PinAffinityAndId.deserialize(bytes.slice(0..1))
                val number_of_connections = bytes.get(2)

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
    }
}