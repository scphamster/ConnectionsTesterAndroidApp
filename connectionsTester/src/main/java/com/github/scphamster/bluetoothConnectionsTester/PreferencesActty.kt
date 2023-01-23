package com.github.scphamster.bluetoothConnectionsTester

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class PreferencesActty : AppCompatActivity(), PreferencesFragment.DataBridgeToActivity {
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

    override fun setIntent(intent: Intent) {
        setResult(Activity.RESULT_OK, intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)


    }
}