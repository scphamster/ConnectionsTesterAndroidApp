package com.github.scphamster.bluetoothConnectionsTester.circuit

class CircuitParameters {
}

data class RawVoltageADCValue(val voltage: Short) {
    companion object {
        const val SIZE_BYTES = 1
        const val MIN_VAL = 0
        const val MAX_VAL = 200

        fun deserialize(bytes: List<Byte>): RawVoltageADCValue {
            if (bytes.size != SIZE_BYTES)
                throw (IllegalArgumentException("Supplied bytes number: ${bytes.size}, while needed: $SIZE_BYTES"))

            val v = bytes.get(0).toInt()
            if ((v < MIN_VAL) || (v > MAX_VAL)) {
                throw(IllegalArgumentException("Supplied value is $v, and is out of acceptable range of values"))
            }

            return RawVoltageADCValue(v.toShort())
        }
    }
}