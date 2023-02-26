package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WorkSocket(val keepAliveMessage: KeepAliveMessage? = null) : DeviceLink {
    companion object {
        private const val Tag = "WorkSocket"
        private const val SOCKET_CONNECTION_TIMEOUT = 10_000
        private const val AUTOMATIC_SOCKET_NUMBER_FLAG = 0
        private const val SOCKET_PERF_BANDWIDTH = 5
        private const val SOCKET_PERF_LATENCY = 10
        private const val SOCKET_PERF_CONNECTION_TIME = 0
    }
    
    data class KeepAliveMessage(val message: Collection<Byte>, val sendPeriodMs: Long)
    class SocketIsDeadException(val msg: String) : Exception(msg)
    
    var port: Int = -1
    
    val isAlive = AtomicBoolean(false)
    override val isReady = AtomicBoolean(false)
    override val id: Int
        get() = port
    override val outputDataChannel = Channel<Collection<Byte>>(Channel.UNLIMITED)
    override val inputDataChannel = Channel<Collection<Byte>>(Channel.UNLIMITED)
    
    private val lastSendOperationTimeMs = AtomicLong(0)
    private val lastReadOperationTimeMs = AtomicLong(0)
    private val Tag: String
        get() = Companion.Tag + "$port"
    
    lateinit private var serverSocket: ServerSocket
    lateinit private var socket: Socket
    lateinit private var outStream: OutputStream
    lateinit private var inStream: InputStream
    lateinit private var inputJob: Deferred<Unit>
    lateinit private var outputJob: Deferred<Unit>
    
    override suspend fun run() = withContext<Unit>(Dispatchers.IO) {
        val Tag = Tag + ":MAIN"
        
        Log.d(Tag, "Starting new working socket")
        try {
            serverSocket = ServerSocket(AUTOMATIC_SOCKET_NUMBER_FLAG)
            port = serverSocket.localPort
            
            Log.d(Tag, "New working socket has port number: ${serverSocket.localPort}")
            
            serverSocket.soTimeout = SOCKET_CONNECTION_TIMEOUT
            socket = serverSocket.accept()
            socket.setPerformancePreferences(SOCKET_PERF_CONNECTION_TIME, SOCKET_PERF_LATENCY, SOCKET_PERF_BANDWIDTH)
            socket.keepAlive = true
            
            outStream = socket.getOutputStream()
            inStream = socket.getInputStream()

            inputJob = async {
                inputChannelTask()
            }
            outputJob = async {
                outputChannelTask()
            }
            
            val keepAliveJob = if (keepAliveMessage != null) {
                async {
                    keepAliveWriteTask()
                } //                launch {
                //                    keepAliveReadTask()
                //                }
            }
            else null
            
            isReady.set(true)
            val jobs = arrayListOf(inputJob, outputJob)
            
            if (keepAliveJob != null) jobs.add(keepAliveJob)
            
            jobs.awaitAll()
        }
        catch (e: CancellationException) {
            Log.d(Tag, "WorkSocket cancelled due to: ${e.message} : ${e.cause}")
        }
        catch (e: Exception) {
            Log.e(Tag, "Unexpected exception: ${e.message} : ${e.cause}")
        } finally {
            Log.d(Tag, "Returning from worksocket")
            stop()
        }
    }
    
    override fun stop() {
        if (::inputJob.isInitialized && inputJob.isActive) {
            Log.d(Tag, "input job canceling")
            inputJob.cancel("Stop invoked")
        }
        if (::outputJob.isInitialized && outputJob.isActive) {
            Log.d(Tag, "output job canceling")
            outputJob.cancel("Stop invoked")
        }
        
        if (::socket.isInitialized && !socket.isClosed) {
            Log.d(Tag, "closing socket ")
            socket.close()
        }
        
        if (::serverSocket.isInitialized && !serverSocket.isClosed) {
            Log.d(Tag, "server socket is closing")
            serverSocket.close()
            Log.d(Tag, "Server socket is closed")
        }
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
    
    private suspend fun keepAliveWriteTask() = withContext(Dispatchers.Default) {
        if (keepAliveMessage == null) return@withContext
        lastSendOperationTimeMs.set(System.currentTimeMillis())
        val Tag = Tag + ":KAWT"
        
        while (isActive) {
            outputDataChannel.send(keepAliveMessage.message)
            delay(keepAliveMessage.sendPeriodMs)
            Log.v(Tag, "KeepAlive send")
        }
    }
    
    private suspend fun keepAliveReadTask() = withContext(Dispatchers.Default) {
        if (keepAliveMessage == null) return@withContext
        lastReadOperationTimeMs.set(System.currentTimeMillis())
        
        while (isActive) {
            if (System.currentTimeMillis() > lastReadOperationTimeMs.get() + System.currentTimeMillis()) throw SocketIsDeadException(
                "timeout at keepAlive reading task, period is : ${keepAliveMessage.sendPeriodMs}ms")
        }
    }
    
    private suspend fun inputChannelTask() = withContext(Dispatchers.IO) {
        while (isActive) {
            val messageSizeBuffer = readN(Int.SIZE_BYTES)
            lastReadOperationTimeMs.set(System.currentTimeMillis())
            
            if (messageSizeBuffer.second != true) {
                Log.e(Tag, "Not obtained all requested bytes!")
                continue
            }
            
            val messageSize = Int(messageSizeBuffer.first.iterator())
            
            val messageBuffer = readN(messageSize)
            lastReadOperationTimeMs.set(System.currentTimeMillis())
            
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
            
            try {
                outStream.write(bytes.toByteArray())
            }
            catch (e: Exception) {
                Log.e("$Tag:OCT", "Exception during output stream write: ${e.message}")
                return@withContext
            }
            
            lastSendOperationTimeMs.set(System.currentTimeMillis())
        }
        
        outStream.close()
    }
}