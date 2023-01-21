package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import java.lang.ref.WeakReference

class Pin(val descriptor: PinDescriptor,
          var belongsToBoard: WeakReference<IoBoard> = WeakReference<IoBoard>(null),
          var connectionsListChangedFromPreviousCheck: Boolean = false) {
    var connections: MutableList<Connection> = mutableListOf()
        set(connections) {
            field = connections
            isHealthy = hasConnection(descriptor.pinAffinityAndId)
        }
    var isHealthy = false
        private set

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

    fun checkIfConnectionsListIsDifferent(checked_connections: List<Connection>, maxResistance: Float = 0f): Boolean {

        val connections_not_in_my_list = checked_connections.filter {
            !hasConnection(it)
        }

        for (connection in connections_not_in_my_list) {
            if (connection.resistance != null) {
                if (connection.resistance.value < maxResistance) return true
            }
            else return true
        }

        val connections_not_in_checked = connections.filter { my_connection ->
            checked_connections.find {
                my_connection.toPin == it.toPin
            } == null
        }

        for (connection in connections_not_in_checked) {
            if (connection.resistance != null) {
                if (connection.resistance.value < maxResistance) return true
            }
            else return true
        }

        return false
    }
}