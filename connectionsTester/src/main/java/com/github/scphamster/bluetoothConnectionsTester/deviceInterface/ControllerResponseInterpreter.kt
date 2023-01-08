package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import android.util.Log
import org.apache.commons.math3.util.IntegerSequence

class ControllerResponseInterpreter {
    enum class VoltageLevel {
        Low,
        High
    }

    enum class MessageHeader(val text: String) {
        //todo: find better way to implement "unknown", empty string will not work btw.
        Unknown("_"),
        VoltageLevel("VOL"),
        PinConnectivityBoolean("CONNECT"),
        PinConnectivityResistance("RESISTANCES"),
        PinConnectivityVoltage("VOLTAGES"),
        HardwareDescription("HW")
    }

    enum class Keywords(val text: String) {
        ArgumentsStart("->"),
        EndOfMessage("END"),
        ValueAndAffinitySplitter(":")
    }

    abstract class Commands {
        abstract class AbstractCommand {}
        class SetVoltageAtPin(val pin: PinNumT) : AbstractCommand() {}
        class SetOutputVoltageLevel(val level: VoltageLevel) : AbstractCommand() {
            enum class VoltageLevel(lvl: String) {
                High("high"),
                Low("low")
            }
        }

        class CheckConnectivity(val domain: AnswerDomain, val pinAffinityAndId: PinAffinityAndId? = null) {
            val base = "check"

            enum class AnswerDomain(val text: String) {
                SimpleConnectionFlag("connections"),
                Voltage("voltages"),
                Resistance("resistances")
            }
        }

        class CheckHardware() : AbstractCommand() {}
    }

    sealed class ControllerMessage {
        class ConnectionsDescription(val ofPin: PinAffinityAndId, val connections: Array<Connection>) :
            ControllerMessage()

        class SelectedVoltageLevel(val level: VoltageLevel) : ControllerMessage()
        class HardwareDescription(val boardsOnLine: Array<IoBoardIndexT>) : ControllerMessage()
    }

    private data class StructuredAnswer(var header: MessageHeader,
                                        var argument: String = "",
                                        var values: Array<String> = arrayOf<String>(),
                                        var msgIsHealthy: Boolean = false)

    companion object {
        val Tag = "CommandInterpreter"
        const val pinAndAffinityNumbersNum = 2
    }

    lateinit var onConnectionsDescriptionCallback: ((ControllerMessage.ConnectionsDescription) -> Unit)
    lateinit var onHardwareDescriptionCallback: ((ControllerMessage.HardwareDescription) -> Unit)

    /**
     * @brief Returns interpreted message from controller and remains of that same message string after first
     *          Keywords.EndOfMessage word
     */
    fun parseAndSplitSingleCommandFromString(msg: String): Pair<ControllerMessage?, String?> {
        Log.i(Tag, "Message from controller: $msg")
        val (first_msg, rest_of_messages) = extractAndSplitOnFirstMsg(msg)

        if (first_msg == null) return Pair(null, null)

        val structured_msg = makeMsgStructured(first_msg)
        if (!structured_msg.msgIsHealthy) {
            Log.e(Tag, "Message is unhealthy!")
            return Pair(null, rest_of_messages)
        }

        val controller_message = parseStructuredMsg(structured_msg)
        if (controller_message == null) {
            Log.e(Tag, "Could not successfully parse message")
        }

        return Pair(controller_message, rest_of_messages)
    }

    private fun messageIsWellTerminated(msg: String) = msg.indexOf(Keywords.EndOfMessage.text) != -1

    private fun extractAndSplitOnFirstMsg(msg: String): Pair<String?, String?> {
        val terminator = Keywords.EndOfMessage.text
        val indx_of_end = msg.indexOf(terminator)
        if (indx_of_end == -1) return Pair(null, null)

        val split_messages_at = indx_of_end + terminator.length

        val primary_msg = msg
            .substring(0, split_messages_at)
            .trim()
        val remains: String = msg.substring(split_messages_at)

        if (messageIsWellTerminated(remains)) return Pair(primary_msg, remains)
        else return Pair(primary_msg, null)
    }

    private fun makeMsgStructured(msg: String): StructuredAnswer {
        // -1 if not found
        var index_of_first_keyword = Int.MAX_VALUE
        val structuredAnswer = StructuredAnswer(MessageHeader.Unknown)

        for (header in MessageHeader.values()) {
            val header_position = msg.indexOf(header.text)

            if (header_position == -1) continue

            if (index_of_first_keyword > header_position) {
                index_of_first_keyword = header_position
                structuredAnswer.header = header
            }
        }

        if (structuredAnswer.header == null) {
            Log.e(Tag, "No known header was found in message")
            return structuredAnswer
        }

        val msg_without_header = msg
            .substring(index_of_first_keyword + structuredAnswer.header.text.length)
            .trim()

        val words = getWordsFromString(msg_without_header)
        if (words.isEmpty()) {
            Log.e(Tag, "Message with empty body, only header arrived!")
            return structuredAnswer
        }

        structuredAnswer.argument = words.removeAt(0)
        if (words.isEmpty()) {
            Log.e(Tag, "Message without terminator!")
            return structuredAnswer
        }

        if (words.get(0) == Keywords.EndOfMessage.text) {
            structuredAnswer.msgIsHealthy = true;
            return structuredAnswer
        }

        val values_delimiter = words.removeAt(0)
        if (values_delimiter != Keywords.ArgumentsStart.text) {
            Log.e(Tag, "Message with inappropriate argument : values delimiter!")
            return structuredAnswer
        }

        val values = mutableListOf<String>()
        for (value_as_text in words) {
            if (value_as_text == Keywords.EndOfMessage.text) {
                structuredAnswer.msgIsHealthy = true
                break
            }

            values.add(value_as_text)
        }

        if (!structuredAnswer.msgIsHealthy) Log.e(Tag, "Message without terminator!")

        structuredAnswer.values = values.toTypedArray()
        return structuredAnswer
    }

    private fun getWordsFromString(str: String): MutableList<String> = str
        .trim()
        .split("\\s+".toRegex())
        .toMutableList()

    private fun stringToPinAffinityAndId(str: String): PinAffinityAndId? {
        val clean_str = str.trim()

        val pin_and_affinity_text = clean_str.split(Keywords.ValueAndAffinitySplitter.text)


        if (pin_and_affinity_text.size > pinAndAffinityNumbersNum) {
            Log.e(Tag, """PinDescriptor format error: Number of Numbers: ${pin_and_affinity_text.size}, 
                |when required size is $pinAndAffinityNumbersNum""".trimMargin())

            return null
        }

        val affinity = pin_and_affinity_text
            .get(0)
            .toIntOrNull()
        if (affinity == null) {
            Log.e(Tag, "Pin descriptor first value is not an integer!")
            return null
        }

        val pin_number = pin_and_affinity_text
            .get(1)
            .toIntOrNull()
        if (pin_number == null) {
            Log.e(Tag, "Pin descriptor second argument is not an integer!")
            return null
        }

        return PinAffinityAndId(affinity, pin_number)
    }

    private fun stringToBoardAddress(str: String): IoBoardIndexT? {
        val address_as_text = str.trim()

        val address = address_as_text.toIntOrNull()

        if (address == null) {
            Log.e(Tag, "Board address is not of integer format!")
            return null
        }

        return address
    }

    private fun parseStructuredMsg(msg: StructuredAnswer): ControllerMessage? {
        when (msg.header) {
            MessageHeader.PinConnectivityBoolean -> return parseConnectivityMsg(msg)
            MessageHeader.PinConnectivityVoltage -> return parseConnectivityMsg(msg)
            MessageHeader.PinConnectivityResistance -> return parseConnectivityMsg(msg)

            MessageHeader.HardwareDescription -> {
                val boards_addresses = mutableListOf<IoBoardIndexT>()

                for (address_text in msg.values) {
                    val addr = stringToBoardAddress(address_text)
                    if (addr == null) return null

                    boards_addresses.add(addr)
                }

                return ControllerMessage.HardwareDescription(boards_addresses.toTypedArray())
            }

            else -> {
                return null
            }
        }
    }

    private fun parseConnectivityMsg(msg: StructuredAnswer): ControllerMessage? {
        val affinity_and_id = msg.argument.toAffinityAndId()
        if (affinity_and_id == null) return null

        val connections = mutableListOf<Connection>()

        for (value in msg.values) {

            val connection_description = if (msg.header == MessageHeader.PinConnectivityBoolean) {
                val affinityAndId = value.toAffinityAndId()
                if (affinityAndId == null) {
                    Log.e(Tag, "One of pin descriptors is of wrong format!")
                    return null
                }
                Connection(affinity_and_id, null, null)
            }
            else if (msg.header == MessageHeader.PinConnectivityVoltage) {
                value.toConnection(msg.header)
            }
            else {
                value.toConnectionWithResistance()
            }

            connections.add(connection_description)
        }

        return ControllerMessage.ConnectionsDescription(affinity_and_id, connections.toTypedArray())
    }

    fun handleMessage(message: String) {
        var msg: String? = message

        while (msg != null) {
            val (controller_msg, rest) = parseAndSplitSingleCommandFromString(msg)
            if (controller_msg == null) return

            msg = rest
            handleControllerMsg(controller_msg)
        }
    }

    private fun handleControllerMsg(msg: ControllerMessage) {
        when (msg) {
            is ControllerMessage.ConnectionsDescription -> {
                if (::onConnectionsDescriptionCallback.isInitialized) onConnectionsDescriptionCallback(msg)
            }

            is ControllerMessage.HardwareDescription -> {
                if (::onHardwareDescriptionCallback.isInitialized) onHardwareDescriptionCallback(msg)
            }

            else -> {
                return
            }
        }
    }
}

//todo: implement through regex
private fun String.toAffinityAndId(): PinAffinityAndId? {
    val clean_str = this.trim()

    val numbers_as_text_list = clean_str.split(ControllerResponseInterpreter.Keywords.ValueAndAffinitySplitter.text)


    if (numbers_as_text_list.size > ControllerResponseInterpreter.pinAndAffinityNumbersNum) {
        Log.e(ControllerResponseInterpreter.Tag,
              """PinDescriptor format error: Number of Numbers: ${numbers_as_text_list.size}, 
                |when required size is ${ControllerResponseInterpreter.pinAndAffinityNumbersNum}""".trimMargin())

        return null
    }

    val affinity = numbers_as_text_list
        .get(0)
        .toIntOrNull()
    if (affinity == null) {
        Log.e(ControllerResponseInterpreter.Tag, "Pin descriptor first value is not an integer!")
        return null
    }

    val pin_number = numbers_as_text_list
        .get(1)
        .toIntOrNull()
    if (pin_number == null) {
        Log.e(ControllerResponseInterpreter.Tag, "Pin descriptor second argument is not an integer!")
        return null
    }

    return PinAffinityAndId(affinity, pin_number)
}

private fun String.toConnectionWithResistance(): Connection {
    val regex = "\\d[.]?\\d+".toRegex()

    val numbers_as_text = regex
        .findAll(this)
        .map { it.value }
        .toList()

    val board_number = numbers_as_text
        .get(0)
        .toInt()
    val pin_number = numbers_as_text
        .get(1)
        .toInt()
    val resistance = numbers_as_text
        .get(2)
        .toFloat()

    return Connection(PinAffinityAndId(board_number, pin_number), null, resistance)
}

fun String.getAllIntegers(): List<Number> {
    //workaround of inability to set arbitrary size in negative lookbehind
    val LOOKBEHIND_QUANTIFIER_UPPER_LIMIT = 100

    //find only integers, will reject: 123.456
    val regex = "(?<!(\\d{0,100}\\.\\d{0,100}))[-]?\\d+(?!\\d*\\.\\d*)".toRegex()

    val integers = regex
        .findAll(this)
        .map { it.value.toInt() }
        .toList()

    return integers
}

fun String.getAllFloats(): List<Float> {
    //workaround of inability to set arbitrary size in negative lookbehind
    val LOOKBEHIND_QUANTIFIER_UPPER_LIMIT = 100

    //find only floats (not integers) will reject: 12.345.678
    val regex = "(?<!(\\d{0,100}\\.\\d{0,100}))[-]?\\d+[.]\\d+(?!\\d*\\.\\d*)".toRegex()
    val floats = regex.findAll(this).map{it.value.toFloat()}.toList()

    return floats
}

fun String.toConnection(header: ControllerResponseInterpreter.MessageHeader): Connection {
    // matches pattern of connection description with optional circuit parameter in parentheses, example: 37:1(33.4) or 37:1
    val regex = "\\d+[:]\\d+([(][-]?\\d+[.]?\\d+[)])?".toRegex()


    return Connection(PinAffinityAndId(1, 1), null, null)

//    return Connection(PinAffinityAndId(board_number, pin_number), voltage, null)
}