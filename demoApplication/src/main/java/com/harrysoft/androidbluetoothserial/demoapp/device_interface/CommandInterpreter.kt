package com.harrysoft.androidbluetoothserial.demoapp.device_interface

import android.util.Log

typealias PinNumberT = Int
typealias PinNumT = Int

interface CommandInterpreter {
    enum class VoltageLevel {
        Low, High
    }

    enum class AnswerHeaders(val text: String) {
        VoltageLevel("VOL"), PinConnectivity("PIN")
    }

    companion object {
        val Tag = this::class.java.simpleName
    }

    fun sendCommand(cmd: Commands.SetVoltageAtPin)

    fun interpretControllerMessage(msg: String): ControllerMessage? {
        Log.d(Tag, "Message from controller: $msg")

        // -1 if not found
        var index_of_first_keyword: Int? = null
        var found_header: String? = null

        for (header in AnswerHeaders.values()) {
            val header_position = msg.indexOf(header.text)

            if (header_position != -1) {
                if (index_of_first_keyword == null || index_of_first_keyword > header_position) {
                    index_of_first_keyword = header_position
                    found_header = header.text
                }
            }
        }

        if (index_of_first_keyword == null || found_header == null) {
            Log.e(Tag, "No known header was found in message")
            return null
        }

        Log.d(Tag, "Message from controller with tag: $found_header")

        val truncated_msg = msg.substring(index_of_first_keyword + found_header.length + 1)

        Log.d(Tag, "truncated msg: $truncated_msg")

        var structured_answer: ControllerMessage

        when (found_header) {
            AnswerHeaders.PinConnectivity.text -> {
                val words = truncated_msg.split("\\s+".toRegex())

                for (word in words) {
                    Log.d(Tag, word)
                }

                val this_msg_is_about_pin = words.get(0)
                        .toIntOrNull()

                if (this_msg_is_about_pin == null) {
                    Log.e(Tag, "Inappropriate format of controller msg, unknown pin number")
                    return null
                }

                var connections = mutableListOf<PinNumT>()

                for (word_number in 2 until words.size) {
                    if (words[word_number] == "END") {
                        break
                    }

                    val connection = words[word_number].toIntOrNull()
                    if (connection == null) {
                        Log.e(Tag, "Bad connections format!")
                        return null
                    }

                    connections.add(connection)
                }

                structured_answer =
                    ControllerMessage.ConnectionsDescription(this_msg_is_about_pin, connections.toTypedArray())

                return structured_answer
            }
            else -> {
                return null
            }
        }
    }

    sealed class ControllerMessage {
        class ConnectionsDescription(val pin: PinNumT, val connectedTo: Array<PinNumT>) : ControllerMessage()
        class SelectedVoltageLevel(val level: VoltageLevel) : ControllerMessage()
    }

    abstract class Commands {
        abstract class AbstractCommand {}
        class SetVoltageAtPin(val pin: PinNumT) : AbstractCommand() {}
        class SetOutputVoltageLevel(val level: VoltageLevel) : AbstractCommand() {
            enum class VoltageLevel(lvl: String) {
                High("high"), Low("low")
            }
        }

        class CheckConnectivity(val pin: PinNumT = checkAllConnections) : AbstractCommand() {
            companion object {
                const val checkAllConnections = -1
            }
        }
    }
}

