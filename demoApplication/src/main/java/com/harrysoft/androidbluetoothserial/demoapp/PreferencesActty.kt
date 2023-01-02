package com.harrysoft.androidbluetoothserial.demoapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.jaiselrahman.filepicker.activity.FilePickerActivity
import com.jaiselrahman.filepicker.model.MediaFile
import com.opencsv.CSVReader
import java.io.InputStreamReader

class PreferencesActty : AppCompatActivity() {
    companion object {
        private const val FILE_REQUEST_CODE = 1
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.pref_actty_layout)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.pref_actty_container, PreferencesFragment())
            .commit()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)


    }
}