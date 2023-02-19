package com.github.scphamster.bluetoothConnectionsTester.circuit

class CircuitParameters {
}

data class RawVoltageADCValue(val value: Short) {
    companion object {
        const val SIZE_BYTES = 1
        const val MIN_VAL = 0
        const val MAX_VAL = 200

        fun deserialize(bytes: Iterator<Byte>): RawVoltageADCValue {
            val v = bytes.next().toUByte().toInt()
            if ((v < MIN_VAL) || (v > MAX_VAL)) {
                throw(IllegalArgumentException("Supplied value is $v, and is out of acceptable range of values"))
            }

            return RawVoltageADCValue(v.toShort())
        }
    }
}