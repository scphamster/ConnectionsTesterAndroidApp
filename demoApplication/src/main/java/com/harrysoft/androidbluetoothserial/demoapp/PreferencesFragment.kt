package com.harrysoft.androidbluetoothserial.demoapp

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceClickListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.jaiselrahman.filepicker.activity.FilePickerActivity
import com.jaiselrahman.filepicker.config.Configurations
import com.jaiselrahman.filepicker.model.MediaFile
import com.opencsv.CSVReader
import java.io.InputStreamReader

class PreferencesFragment : PreferenceFragmentCompat() {
    companion object {
        const val Tag = "PreferencesFragment"
        private const val FILE_REQUEST_CODE = 1
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val preference = findPreference<Preference>("pref_pinout_descriptor")

        preference?.setOnPreferenceClickListener {
            val intent = Intent(context, FilePickerActivity::class.java)
            intent.putExtra(FilePickerActivity.CONFIGS,
                            Configurations
                                .Builder()
                                .setCheckPermission(true)
                                .setShowImages(false)
                                .setShowVideos(false)
                                .setSuffixes("csv")
                                .setShowFiles(true)
                                .setSingleChoiceMode(true)
                                .enableImageCapture(false)
                                .setSkipZeroSizeFiles(false)
                                .build())
            startActivityForResult(intent, FILE_REQUEST_CODE)


            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_REQUEST_CODE && resultCode == AppCompatActivity.RESULT_OK) {
            val files: ArrayList<MediaFile> = data!!.getParcelableArrayListExtra(FilePickerActivity.MEDIA_FILES)!!

            if (files.size > 1) {
                return
            }

            val file = files.get(0)
            val this_preference = findPreference<PreferenceScreen>("pref_pinout_descriptor")
            this_preference?.summary = file.name.toString()
        }
    }
}