package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.github.scphamster.bluetoothConnectionsTester.circuit.BoardAddrT
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class WorkSocket : DeviceLink {
    companion object {
        const val Tag = "WorkSocket"
        const val DEADLINE_MS = 1000
        private const val OUT_CHANNEL_SIZE = 10
        private const val SOCKET_CONNECTION_TIMEOUT = 10_000
    }
    
    var boardAddr: BoardAddrT = -1
    var port: Int = -1
    
    override val id: Int
        get() = port
    override val outputDataChannel = Channel<Collection<Byte>>(OUT_CHANNEL_SIZE)
    override val inputDataChannel = Channel<Collection<Byte>>(OUT_CHANNEL_SIZE)
    
    lateinit private var outStream: OutputStream
    lateinit private var inStream: InputStream
    lateinit private var socket: Socket
    
    private val defaultDispatcher = Dispatchers.IO
    private val mutex = Mutex()
    
    private lateinit var inputJob: Job
    private lateinit var outputJob: Job
    private lateinit var serverSocket: ServerSocket
    
    override suspend fun start() = withContext<Unit>(Dispatchers.IO) {
        Log.d(Tag, "Starting new working socket")
        serverSocket = ServerSocket(0)
        port = serverSocket.localPort
        
        Log.d(Tag, "New working socket: ${serverSocket.localPort}")
        
        serverSocket.soTimeout = SOCKET_CONNECTION_TIMEOUT
        
        socket = serverSocket.accept() //        }

        socket.setPerformancePreferences(0, 10, 5)
        
        outStream = socket.getOutputStream()
        inStream = socket.getInputStream()
        
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
            Log.d("$Tag:MAIN", "Port $port is closing, cause: ${e.message}")
        } finally {
            inputJob.cancel("end of work")
            outputJob.cancel("end of work")
            serverSocket.close()
            socket.close()
        }
    }
    
    override fun stop() {
        if (::inputJob.isInitialized) inputJob.cancel("Stop invoked")
        if (::outputJob.isInitialized) outputJob.cancel("Stop invoked")
        if(::serverSocket.isInitialized && !serverSocket.isClosed) serverSocket.close()
    }
    
    private fun readN(len: Int): Pair<Array<Byte>, Boolean> {
        val buffer = ByteArray(len)
        var obtained = 0
        while (obtained < len) {
            val nowObtained = inStream.read(buffer, obtained, len - obtained)
            if (nowObtained < 0) break;
            
            obtained += nowObtained
        }
        
        return Pair(buffer.toTypedArray(), obtained == len)
    }
    
    private suspend fun inputChannelTask() = withContext(Dispatchers.IO) {
        while (isActive) {
            val messageSizeBuffer = readN(Int.SIZE_BYTES)
            if (messageSizeBuffer.second != true) {
                Log.e(Tag, "Not obtained all requested bytes!")
                continue
            }
            
            val messageSize = Int(messageSizeBuffer.first.iterator())
            
            val messageBuffer = readN(messageSize)
            
            if (!messageBuffer.second) {
                Log.e(Tag, "Not full message was obtained!")
                continue
            }
            Log.d("$Tag:ICT", "New data arrived, size: $messageSize")
            
            inputDataChannel.send(messageBuffer.first.toList())
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