package com.github.scphamster.bluetoothConnectionsTester.device

import android.util.Log
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.MutableLiveData

class ErrorHandler(val context: Context) {
    val errorMessages: MutableLiveData<MutableList<String>> = MutableLiveData<MutableList<String>>()

    init{
        Log.d("EHANDLER", "CHECK")
    }

    fun handleError(error_description: String?) {
        toast(error_description)
    }

    private fun toast(msg: String?) {
        val error_info = if (msg == null) "Unknown error, no description, some fatal error occurred"
        else msg.toString()

        Toast
            .makeText(context, error_info, Toast.LENGTH_LONG)
            .show()

        val messages = errorMessages.value ?: mutableListOf()
        messages.add("${messages.size + 1}: " + error_info)
        errorMessages.value = messages
    }
}