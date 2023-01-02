package com.harrysoft.androidbluetoothserial.demoapp

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class PreferencesActty : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.pref_actty_layout)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.pref_actty_container, PreferencesFragment())
            .commit()
    }

}