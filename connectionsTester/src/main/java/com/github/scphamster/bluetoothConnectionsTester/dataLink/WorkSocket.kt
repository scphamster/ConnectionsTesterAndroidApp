package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.BoardAddrT
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class WorkSocket : DeviceLink {
    companion object {
        const val Tag = "WorkSocket"
        const val DEADLINE_MS = 1000
        private const val OUT_CHANNEL_SIZE = 10
    }
    
    var boardAddr: BoardAddrT = -1
    var port: Int = -1
    
    override val outputDataChannel = Channel<Collection<Byte>>(OUT_CHANNEL_SIZE)
    override val inputDataChannel = Channel<Collection<Byte>>(OUT_CHANNEL_SIZE)
    
    lateinit private var outStream: OutputStream
    lateinit private var inStream: InputStream
    lateinit private var socket: Socket
    
    private val defaultDispatcher = Dispatchers.IO
    private val mutex = Mutex()
    
    suspend fun start() = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket(0)
        port = serverSocket.localPort
        
        Log.d(Tag, "New working socket: ${serverSocket.localPort}, and port is : ${port}")
        
        socket = serverSocket.accept()
        outStream = socket.getOutputStream()
        inStream = socket.getInputStream()
        
        var inputJob = Job() as Job
        var outputJob = Job() as Job
        try {
            inputJob = launch {
                inputChannelTask()
            }
            outputJob = launch {
                outputChannelTask()
            }
            
            awaitCancellation()
        }
        catch (e: Exception) {
            Log.d("$Tag:MAIN", "Cancelled, Message: ${e.message}")
        } finally {
            inputJob.cancel("end of work")
            outputJob.cancel("end of work")
            serverSocket.close()
            socket.close()
        }
    }
    
    private suspend fun inputChannelTask() = withContext(Dispatchers.IO) {
        while (isActive) {
            while (inStream.available() == 0) continue
            
            val buffer = ByteArray(inStream.available() + 64)
            val bytesReceived = inStream.read(buffer)
            val data = buffer.slice(0..(bytesReceived - 1))
            Log.d("$Tag:ICT", "New data arrived, size: ${data.size}")
            
            for (byte in data) {
                Log.d("$Tag:ICT", "$byte")
            }
            
            inputDataChannel.send(data)
        }
    }
    
    private suspend fun outputChannelTask() = withContext(Dispatchers.IO) {
        while (isActive) {
            val result = outputDataChannel.receiveCatching()
            val data = result.getOrNull()
            if (data == null) {
                if (!isActive) break
                
                Log.e("$Tag:OCT", "data is null!")
                continue
            }
            
            if (data.size == 0) {
                Log.e("$Tag:OCT", "data size is zero!")
                continue
            }
            
            val bytes = data.size.toByteArray()
                .toMutableList()
            bytes.addAll(data.toList())
            
            
            mutex.withLock {
                outStream.write(bytes.toByteArray())
            }
        }
        
        outStream.close()
    }
}