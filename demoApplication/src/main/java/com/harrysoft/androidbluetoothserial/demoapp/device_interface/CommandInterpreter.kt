package com.harrysoft.androidbluetoothserial.demoapp.device_interface

import android.util.Log

typealias PinNumberT = Int
typealias PinNumT = Int

interface CommandInterpreter {
    enum class VoltageLevel {
        Low, High
    }
    enum class AnswerHeaders(val text: String) {
        Connectivity("CON"), VoltageLevel("VOL"), PinConnectivity("PIN")
    }

    companion object {
        val Tag = this::class.java.simpleName
    }

    fun sendCommand(cmd: Commands.SetVoltageAtPin)

    fun interpretControllerMessage(msg: String): ControllerMessage? {
        Log.d(Tag, "Message from controller: $msg")

        // -1 if not found
        var index_of_first_keyword: Int? = null
        var found_header:String? = null

        for (header in AnswerHeaders.values()) {
            val header_position = msg.indexOf(header.text)

            if (header_position != -1) {
                if (index_of_first_keyword == null || index_of_first_keyword > header_position) {
                    index_of_first_keyword = header_position
                    found_header = header.text
                }
            }
        }

        if (index_of_first_keyword == null) {
            Log.e(Tag, "No known header was found in message")
            return null
        }

        Log.d(Tag, "Message from controller with tag: $found_header")

        val truncated_msg = msg.substring(index_of_first_keyword)



        return null
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

