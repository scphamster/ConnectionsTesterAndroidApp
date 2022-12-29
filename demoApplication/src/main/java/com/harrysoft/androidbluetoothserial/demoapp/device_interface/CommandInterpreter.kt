package com.harrysoft.androidbluetoothserial.demoapp.device_interface

typealias PinNumberT = Int
typealias PinNumT = Int

interface CommandInterpreter {
    enum class VoltageLevel {
        Low, High
    }



    abstract class Commands {
        public class SetVoltageAtPin(val pin: PinNumT) {}
        class SetOutputVoltageLevel(val level: VoltageLevel) {
            enum class VoltageLevel(lvl: String) {
                High("high"), Low("low")
            }
        }

        class CheckConnectivity(val pin: PinNumT = checkAllConnections) {
            companion object {
                const val checkAllConnections = -1
            }
        }
    }
}

