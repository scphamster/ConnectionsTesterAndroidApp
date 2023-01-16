package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import java.lang.ref.WeakReference
import kotlin.math.absoluteValue
import kotlin.math.ln
import kotlin.math.floor

typealias IoBoardIndexT = Int
typealias PinNumT = Int
typealias CircuitParamT = Float
typealias VoltageT = CircuitParamT
typealias ResistanceT = CircuitParamT

data class PinGroup(val id: Int, val name: String? = null) {
    fun getPrettyName(): String {
        if (name != null) {
            return name
        }
        else {
            return id.toString()
        }
    }
}

fun getMultiplierFromString(text: String): Double {
    return when (text) {
        "p" -> 1e-12
        "n" -> 1e-9
        "u" -> 1e-6
        "m" -> 1e-3
        "k" -> 1e3
        "M" -> 1e6
        "G" -> 1e9
        else -> 1.0
    }
}

interface ElectricalValue {
    val value: CircuitParamT
    val precision: Int
    fun toValueWithMultiplier(): String {
        val exponent = floor((ln(value) / ln(10.0))).toInt()
        val multiplier_and_equalizer = when (exponent) {
            in -15..-13 -> "f" to 1e15
            in -12..-10 -> "p" to 1e12
            in -9..-7 -> "n" to 1e9
            in -6..-4 -> "u" to 1e6
            in -3..-1 -> "m" to 1e3
            in 0..2 -> "" to 1.0
            in 3..5 -> "k" to 1e-3
            in 6..8 -> "M" to 1e-6
            in 9..11 -> "G" to 1e-9
            in 12..14 -> "T" to 1e-12
            else -> "" to 1.0
        }

        val format_pattern = "%.${precision}f"
        val value_as_text = String.format(format_pattern, value * multiplier_and_equalizer.second)

        return "$value_as_text${multiplier_and_equalizer.first}"
    }

    override fun toString(): String
}

class Resistance(override val value: CircuitParamT, override val precision: Int = 2) : ElectricalValue {
    val sign = "\u03A9"

    override fun toString(): String {
        return "${toValueWithMultiplier()}$sign"
    }
}

class Voltage(override val value: CircuitParamT, override val precision: Int = 2) : ElectricalValue {
    override fun toString(): String {
        return "${toValueWithMultiplier()}V"
    }
}

interface PinIdentifier {
    /**
     * @brief composes affinity and id into pattern like affinity:id example: 37:1
     */
    fun getPrettyName(): String
    public val pinAffinityAndId: PinAffinityAndId
}

data class PinAffinityAndId(val boardId: IoBoardIndexT, val idxOnBoard: PinNumT) : PinIdentifier {
    override fun getPrettyName(): String {
        return "$boardId:$idxOnBoard"
    }

    override val pinAffinityAndId: PinAffinityAndId
        get() = PinAffinityAndId(boardId, idxOnBoard)
}

class Connection(val toPin: PinIdentifier,
                 val voltage: Voltage? = null,
                 val resistance: Resistance? = null,
                 val value_changed_from_previous_check: Boolean = false) {
    override fun toString(): String {
        val electrical = if (voltage != null) "(${voltage.toString()})"
        else if (resistance != null) "(${resistance.toString()})"
        else ""

        return toPin.getPrettyName() + electrical
    }

    fun checkIfDifferent(connection: Connection?,
                         min_difference_abs: Number = 20,
                         min_difference_percent: Number = 20): Boolean? {
        if (connection == null) return null

        val multiplier = min_difference_percent.toDouble() / 100

        resistance?.let {
            if (connection.resistance == null) return true
            else if ((resistance.value - connection.resistance.value).absoluteValue > (min_difference_abs.toDouble() + multiplier * resistance.value)) return true
            else return false
        }
        voltage?.let {
            if (connection.voltage == null) return true
            else if ((voltage.value - connection.voltage.value).absoluteValue > (min_difference_abs.toDouble() + multiplier * voltage.value)) return true
            else return false
        }

        return false
    }
}

data class PinDescriptor(val affinityAndId: PinAffinityAndId,
                         val UID: PinNumT? = null,
                         var name: String? = null,
                         var group: PinGroup? = null) : PinIdentifier {
    override fun getPrettyName(): String {
        val string_builder = StringBuilder()

        if (group != null) {
            string_builder.append(group?.getPrettyName())
        }
        else {
            string_builder.append(affinityAndId.boardId.toString())
        }

        string_builder.append(":")

        if (name != null) {
            string_builder.append(name)
        }
        else {
            string_builder.append(affinityAndId.idxOnBoard.toString())
        }

        return string_builder.toString()
    }

    override val pinAffinityAndId: PinAffinityAndId
        get() = affinityAndId

    fun clearPinAndGroupNames() {
        group?.let {
            group = PinGroup(it.id, null)
        }

        name = null
    }
}

data class Pin(val descriptor: PinDescriptor,
               var connections: MutableList<Connection> = mutableListOf(),
               var belongsToBoard: WeakReference<IoBoard> = WeakReference<IoBoard>(null),
               var connectionsListChangedFromPreviousCheck: Boolean = false) {
    fun hasConnection(connection: Connection): Boolean {
        if (connections.isEmpty()) return false

        val connection = connections.find { some_connection ->
            some_connection.toPin.pinAffinityAndId == connection.toPin.pinAffinityAndId
        }

        return connection != null
    }

    fun hasConnection(searched_pin_affinity_and_id: PinIdentifier): Boolean {
        if (connections.isEmpty()) return false

        val connection = connections.find { some_connection ->
            some_connection.toPin.pinAffinityAndId == searched_pin_affinity_and_id
        }

        return connection != null
    }

    fun getConnection(searched_pin_affinity_and_id: PinIdentifier): Connection? {
        if (connections.isEmpty()) return null

        return connections.find { some_connection ->
            some_connection.toPin.pinAffinityAndId == searched_pin_affinity_and_id
        }
    }
}

data class IoBoard(val id: IoBoardIndexT, val pins: MutableList<Pin> = mutableListOf()) {
    companion object {
        const val pinsCountOnSingleBoard = 32
    }
}

data class Connections(val pin: Pin, var connectedWith: MutableList<Pin>) {}

fun String.toResistance(): Resistance? {
    val integers = getAllIntegers()
    val floats = getAllFloats()

    var resistance = if (!integers.isEmpty()) {
        integers
            .get(0)
            .toFloat()
    }
    else if (!floats.isEmpty()) {
        floats.get(0)
    }
    else null

    if (resistance == null) return null

    val multiplier_pattern = "[kM]".toRegex()

    if (contains(multiplier_pattern)) {
        val multiplier_regex = "[a-zA-Z]".toRegex()
        val multiplier_character = multiplier_regex.find(this)?.value
        multiplier_character?.let {
            val multiplier = getMultiplierFromString(multiplier_character)
            resistance *= multiplier.toFloat()
        }
    }

    return Resistance(resistance)
}