package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import android.content.Context
import android.widget.Toast

class ErrorHandler(val context: Context) {
    fun handleError(error_description: String) {
        toast(error_description)
    }

    private fun toast(msg: String?) {

        val error_info = if (msg == null) "Unknown error, no description, some fatal error occurred"
        else msg

        Toast
            .makeText(context, error_info, Toast.LENGTH_LONG)
            .show()
    }
}