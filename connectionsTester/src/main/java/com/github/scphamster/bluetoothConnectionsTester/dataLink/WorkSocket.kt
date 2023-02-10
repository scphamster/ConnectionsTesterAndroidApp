package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import kotlin.properties.Delegates

class WorkSocket {
    companion object {
        const val Tag = "WorkSocket"
        const val DEADLINE_MS = 1000
    }

    private val defaultDispatcher = Dispatchers.IO
    var port: Int = -1
    val p = MutableLiveData<Int>()

    suspend fun run() {
        val socket = ServerSocket(0)
        port = socket.localPort

        Log.d(Tag, "New working socket: ${socket.localPort}, and port is : ${port}")

        val connected_socket = socket.accept()

        val outputStream = connected_socket.getOutputStream()
        val inputStream = connected_socket.getInputStream()
        var receptionDeadline: Long = System.currentTimeMillis() + WorkSocket.DEADLINE_MS

        //test
        val cmd = MessageToController.Command
            .MeasureAll()
            .serialize()

        while (true) {
            outputStream.write(cmd)

            while (inputStream.available() == 0) {
            }

            val buffer = ByteArray(inputStream.available() + 64)
            val n_bytes_received = inputStream.read(buffer)
//            inputStream.mark()
            for ((idx, byte) in buffer.withIndex()) {
                Log.d(Tag, "$idx: $byte")
            }

//            if (buffer.get(0) != 50.toByte()) {
//                Log.e(Tag, "Arrived message with first byte not equal 50!")
//            }




            delay(500)
        }
    }
}