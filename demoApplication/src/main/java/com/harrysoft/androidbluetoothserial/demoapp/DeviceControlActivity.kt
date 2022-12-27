package com.harrysoft.androidbluetoothserial.demoapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import android.util.Log
import android.widget.Button
import android.widget.TextView
import kotlinx.android.synthetic.main.actty_device_controll.*

class DeviceControlActivity : AppCompatActivity() {
    private val model by lazy {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                .create(DeviceControlViewModel::class.java)
    }
    private val numberOfFoundBoards by lazy { findViewById<TextView>(R.id.number_of_found_boards_vw) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.actty_device_controll)

        if (!model.setupViewModel(intent.getStringExtra("name")!!, intent.getStringExtra("mac"))) {
            finish()
            return
        }

        setupAllListeners()

        Log.d(Tag, "device control created")
    }

    override fun onBackPressed() {
        super.onBackPressed()
//        finish()
    }

    private fun setupAllListeners() {
        model.numberOfConnectedBoards.observe(this) { boards_count: BoardCountT ->
            numberOfFoundBoards.text = numberOfFoundBoards.text.toString()
                    .format(boards_count)
        }

        model.connectionStatus.observe(this){
            connection_status: DeviceControlViewModel.ConnectionStatus ->
            when(connection_status) {
                DeviceControlViewModel.ConnectionStatus.CONNECTED-> {
                    model.test1()
                }

                else -> {}
            }

        }

        findViewById<Button>(R.id.cmd1_button).setOnClickListener(){
            model.connect()
        }

    }

    companion object {
        private val Tag: String = "DeviceControl"
    }
}