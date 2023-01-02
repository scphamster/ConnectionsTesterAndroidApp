package com.harrysoft.androidbluetoothserial.demoapp

import android.util.Log
import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class PreferencesFragment: PreferenceFragmentCompat() {
    companion object{
        const val Tag = "PreferencesFragment"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        Log.d(Tag, "Preferences fragment has been created")

    }
}