package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.ServerSocket

class RegistrationNewControllersSocket(val context: Context, val socketChannel: Channel<DeviceLink>) {
    companion object {
        const val Tag = "LinksController"
    }
    
    val workerSockets = mutableListOf<WorkSocket>()
    
    suspend fun entrySocketAsync() = withContext(Dispatchers.IO) {
        while (isActive) {
            val socket = ServerSocket(1500)
            val connected_socket = socket.accept()
            
            Log.d(Tag, "Someone connected: ${connected_socket.remoteSocketAddress}")
            
            val outputStream = connected_socket.getOutputStream()
            val inputStream = connected_socket.getInputStream()
            var receptionDeadline: Long = System.currentTimeMillis() + WorkSocket.DEADLINE_MS
            
            workerSockets.add(WorkSocket())
            val lastWorkSocket = workerSockets.last()
            launch {
                lastWorkSocket.start()
            }
            
            launch(Dispatchers.Default) {
                try {
                    socketChannel.send(lastWorkSocket)
                }
                catch (e: Exception) {
                    Log.e(Tag, "Exception during worksocket send: ${e.message}")
                }
            }
            
            while (lastWorkSocket.port == -1) { //
            }
            
            Log.d(Tag, "New work socket opened! Port: ${lastWorkSocket.port}")
            
            outputStream.write(lastWorkSocket.port.toByteArray())
            
            val msg = ReadAnswer(inputStream)
            
            if (msg == null) {
                Log.e(Tag, "New socket: answer not obtained! Restarting entry server!")
                continue
            }
            else {
                when (msg) {
                    is Msg.OperationConfirmation -> {
                        Log.d(Tag, "New socket: confirmation: ${msg.confirmationValue}")
                    }
                    
                    else -> {
                        Log.e(Tag, "New socket: Expected operation confirmation but obtained other type!")
                        continue
                    }
                }
            }
            
            outputStream.close()
            inputStream.close()
            connected_socket.close()
            socket.close()
        }
    }
    
    suspend fun ReadAnswer(inStream: java.io.InputStream, timeout: Long = Long.MAX_VALUE) =
        withContext<Msg?>(Dispatchers.IO) {
            val deadline = if (timeout == Long.MAX_VALUE) Long.MAX_VALUE
            else timeout
            
            while (inStream.available() == 0) {
                if (deadline < System.currentTimeMillis()) {
                    Log.e(Tag, "Timeout of answer retrieval!")
                    return@withContext null
                }
            }
            
            val buffer = ByteArray(inStream.available() + 64)
            inStream.read(buffer)
            val msg = Msg.deserialize(buffer)
            
            return@withContext msg
        }
}

fun Int.toByteArray(): ByteArray {
    val byteArray = ByteArray(Int.SIZE_BYTES)
    
    for (index in 0..(Int.SIZE_BYTES - 1)) {
        byteArray[index] = this.shr(8 * index)
            .toByte()
    }
    
    return byteArray
}