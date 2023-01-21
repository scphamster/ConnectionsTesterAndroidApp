package com.github.scphamster.bluetoothConnectionsTester

import android.content.Context
import android.icu.util.Calendar
import java.io.FileOutputStream

class ToFileLogger(private val context: Context) {
    companion object {
        const val file_name = "log.txt"
    }

    private val calendar by lazy { Calendar.getInstance() }

    enum class LogLevel(val text: String) {
        Info("I:"),
        Debug("D:"),
        Warning("W:"),
        Error("E:"),
        Fatal("F:")
    }

    fun Log(level: LogLevel, tag: String, msg: String) {
        val outputStream = context.openFileOutput(file_name, Context.MODE_APPEND)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val currentTime = "$hour:$minute:$second::"

        outputStream.write(
            currentTime.toByteArray() + level.text.toByteArray() + tag.toByteArray() + ": ".toByteArray() + msg.toByteArray())
        outputStream.close()
    }

    fun LogE(tag: String, msg: String) {
        Log(LogLevel.Error, tag, msg)
    }

    fun LogI(tag: String, msg: String) {
        Log(LogLevel.Info, tag, msg)
    }

    fun LogD(tag: String, msg: String) {
        Log(LogLevel.Debug, tag, msg)
    }

    fun LogW(tag: String, msg: String) {
        Log(LogLevel.Warning, tag, msg)
    }
}