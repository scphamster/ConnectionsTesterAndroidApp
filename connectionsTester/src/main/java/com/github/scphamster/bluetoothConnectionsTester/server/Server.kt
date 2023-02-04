package com.github.scphamster.bluetoothConnectionsTester.server

import android.util.Log
import android.util.Size
import com.github.scphamster.bluetoothConnectionsTester.DeviceControlActivity
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.Socket

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer

class Message(val someArray:ByteArray, val someShort: Short) {
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

class MyServer(val hostname: String, val port: Int) {
    companion object {
        private const val Tag = "server"
        fun getIP(): String? {
            try {
                val networkInterfaces = NetworkInterface.getNetworkInterfaces()
                while (networkInterfaces.hasMoreElements()) {
                    val netInterface: NetworkInterface = networkInterfaces.nextElement()

                    if (netInterface
                            .getName()
                            .contains("wlan")
                    ) {

                        val ipAddresses = netInterface.getInetAddresses()

                        while (ipAddresses.hasMoreElements()) {
                            val ip = ipAddresses.nextElement()

                            if (!ip.isLoopbackAddress() && ip.getAddress().size == 4) {
                                ip
                                    .getHostAddress()
                                    ?.let { return it }
                            }
                        }
                    }
                }
            }
            catch (e: Exception) {
//                Log.e(DeviceControlActivity.Tag, "${e.message}")
            }

            return null
        }
    }

    init {
//        socket = Socket(hostname, port)
    }

    suspend fun testWrite(str: String) = withContext(Dispatchers.IO) {
        Log.d("Socket", "Starting write")

        val socket = ServerSocket(port)

        Log.d(Tag, "waiting for connection of client!")

        val connected_socket = socket.accept()

        Log.d(Tag, "Someone connected!")

        val outputStream = connected_socket.getOutputStream()
        val inputStream = connected_socket.getInputStream()

        launch(Dispatchers.IO) {
            while (true) {
                val data = str.toByteArray()
                outputStream.write(data)
                Log.d(Tag, "data written")

                outputStream.flush()

                if (inputStream.available() > 0) {
                    Log.d(Tag, "input stream is not empty")

                    val byte_array = ByteArray(inputStream.available() + 100) { 0 }

                    val bytes_obtained = inputStream.read(byte_array)

                    val new_msg = try {
                        Message.deserialize(byte_array)
                    }
                    catch (e: Exception) {
                        Log.e(Tag, "exception while creation Message from array!: ${e.message}")
                        continue
                    }

                    Log.d(Tag, "Creation successful! someArray[0] = ${
                        new_msg.someArray.get(0)
                    }, short: ${new_msg.someShort}")
                }

                delay(500)
            }
        }
    }
}