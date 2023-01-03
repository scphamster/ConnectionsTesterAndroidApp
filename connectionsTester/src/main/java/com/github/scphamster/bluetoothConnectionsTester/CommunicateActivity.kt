package com.github.scphamster.bluetoothConnectionsTester

import android.os.Bundle
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.github.scphamster.bluetoothConnectionsTester.CommunicateViewModel.ConnectionStatus
import com.github.scphamster.bluetoothConnectionsTester.R

class CommunicateActivity : AppCompatActivity() {
    private var connectionText: TextView? = null
    private var messagesView: TextView? = null
    private var messageBox: EditText? = null
    private var sendButton: Button? = null
    private var connectButton: Button? = null

    //    private val viewModel: CommunicateViewModel by lazy {ViewModelProviders.of(this).get(CommunicateViewModel::class.java)}
    private val viewModel: CommunicateViewModel by lazy {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                .create(CommunicateViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_communicate)

        // Enable the back button in the action bar if possible
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // This method return false if there is an error, so if it does, we should close.
        if (!viewModel.setupViewModel(intent.getStringExtra("device_name")!!, intent.getStringExtra("device_mac"))) {
            finish()
            return
        }

        // Setup our Views
        connectionText = findViewById(R.id.terminal_connection_status)
        messagesView = findViewById(R.id.terminal_messages)
        messageBox = findViewById(R.id.terminal_msg_box)
        sendButton = findViewById(R.id.terminal_send_button)
        connectButton = findViewById(R.id.terminal_connect_button)

        // Start observing the data sent to us by the ViewModel
        viewModel.connectionStatus.observe(this) { connectionStatus: ConnectionStatus ->
            onConnectionStatus(connectionStatus)
        }
        viewModel.getDeviceName()
                .observe(this) { name: String? -> title = getString(R.string.device_name_format, name) }

        viewModel.getMessages()
                .observe(this) { message: String? ->
                    var message = message

                    if (message != null) {
                        if (message.isEmpty()) {
                            message = getString(R.string.no_messages)
                        }
                    }

                    messagesView?.setText(message)
                }
        viewModel.message.observe(this) { message: String? ->
            // Only update the message if the ViewModel is trying to reset it
            if (TextUtils.isEmpty(message)) {
                messageBox?.setText(message)
            }
        }

        // Setup the send button click action
        sendButton?.setOnClickListener { v: View? ->
            viewModel.sendMessage(messageBox?.getText()
                    .toString())
        }
    }

    // Called when the ViewModel updates us of our connectivity status
    private fun onConnectionStatus(connectionStatus: ConnectionStatus) {
        when (connectionStatus) {
            ConnectionStatus.CONNECTED -> {
                connectionText?.setText(R.string.status_connected)
                messageBox?.isEnabled = true
                sendButton?.isEnabled = true
                connectButton?.isEnabled = true
                connectButton?.setText(R.string.disconnect)
                connectButton?.setOnClickListener { v: View? -> viewModel.disconnect() }
            }

            ConnectionStatus.CONNECTING -> {
                connectionText!!.setText(R.string.status_connecting)
                messageBox!!.isEnabled = false
                sendButton!!.isEnabled = false
                connectButton!!.isEnabled = false
                connectButton!!.setText(R.string.connect)
            }

            ConnectionStatus.DISCONNECTED -> {
                connectionText!!.setText(R.string.status_disconnected)
                messageBox!!.isEnabled = false
                sendButton!!.isEnabled = false
                connectButton!!.isEnabled = true
                connectButton!!.setText(R.string.connect)
                connectButton!!.setOnClickListener { v: View? -> viewModel.connect() }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                // If the back button was pressed, handle it the normal way
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        // Close the activity
        finish()
    }
}