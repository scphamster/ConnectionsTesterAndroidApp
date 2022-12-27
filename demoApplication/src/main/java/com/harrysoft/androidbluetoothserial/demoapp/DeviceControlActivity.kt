package com.harrysoft.androidbluetoothserial.demoapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class DeviceControlActivity : AppCompatActivity(){

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.actty_device_controll)
    }

    override fun onBackPressed() {
        super.onBackPressed()
//        finish()
    }
}