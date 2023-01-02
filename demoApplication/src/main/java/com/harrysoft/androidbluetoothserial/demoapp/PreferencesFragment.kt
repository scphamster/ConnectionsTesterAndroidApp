package com.harrysoft.androidbluetoothserial.demoapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.*
import com.jaiselrahman.filepicker.activity.FilePickerActivity
import com.jaiselrahman.filepicker.config.Configurations
import com.jaiselrahman.filepicker.model.MediaFile

class PreferencesFragment : PreferenceFragmentCompat() {
    companion object {
        const val Tag = "PreferencesFragment"
        private const val FILE_REQUEST_CODE = 1

        const val PREF_PIN_CONFIG_FILE_URI = "pref_pin_config_file_uri"
        const val PREF_PIN_CONFIG_FILE_NAME = "pref_pin_config_file_name"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val preference = findPreference<Preference>("pref_pinout_descriptor")

        context?.let {
            val pref_manager = PreferenceManager.getDefaultSharedPreferences(it)

            val name_of_config_file = pref_manager.getString(PREF_PIN_CONFIG_FILE_NAME, "")
            val file_uri = pref_manager.getString(PREF_PIN_CONFIG_FILE_URI, "")

            if (name_of_config_file == "" || file_uri == "") {
                preference?.summary = getString(R.string.pref_pinout_descriptor_filename_defaultval).toString()
            }
            else {
                preference?.summary = name_of_config_file.toString()
            }
        }

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

            context?.let {
                val pref_manager_editor = PreferenceManager
                    .getDefaultSharedPreferences(it)
                    .edit()

                pref_manager_editor.let {
                    it.putString(PREF_PIN_CONFIG_FILE_URI, file.uri.toString())
                    it.putString(PREF_PIN_CONFIG_FILE_NAME, file.name.toString())
                    it.apply()
                }
            }
        }
    }
}