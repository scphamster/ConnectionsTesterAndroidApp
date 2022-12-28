package com.harrysoft.androidbluetoothserial.demoapp.device_interface

typealias PinNumberT = Int

interface CommandInterpreter
{
    enum class VoltageLevel {
        Low, High
    }

    fun setPin(pin: PinNumberT): String {
        return "set $pin"
    }

    fun setVoltageLevel(voltage_level: VoltageLevel): String {
        when (voltage_level) {
            VoltageLevel.High -> {
                return "voltage high"
            }

            VoltageLevel.Low -> {
                return "voltage low"
            }
        }
    }

    fun getConnections(): String {
        return "check all"
    }

    fun getConnections(for_pin: PinNumberT): String {
        return "check $for_pin"
    }
}