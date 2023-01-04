package com.github.scphamster.bluetoothConnectionsTester

import android.util.Log
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.preference.*
import com.jaiselrahman.filepicker.activity.FilePickerActivity
import com.jaiselrahman.filepicker.config.Configurations
import com.jaiselrahman.filepicker.model.MediaFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectOutputStream

class PreferencesFragment : PreferenceFragmentCompat() {
    companion object {
        const val Tag = "PreferencesFragment"

        public enum class IntentResultCode(val value: Int) {
            ChooseConfigsFile(1),
            ChooseWhereToStoreFile(2)
        }

        public enum class SharedPreferenceKey(val text: String) {
            PinoutConfigFileUri("pref_pinout_config_file_uri"),
            PinoutConfigFileName("pref_pinout_config_file_name"),
            ResultsFileUri("pref_results_file_uri"),
            ResultsFileName("pref_results_file_name")
        }

        private enum class FileExtensions(val text: String) {
            PinConfig("xlsx"),
            Results("xlsx")
        }

        private enum class PreferenceId(val text: String) {
            Pinout("pref_pinout_descriptor"),
            Results("pref_results_file")
        }
    }

    private val pinout_file_preference by lazy { findPreference<PreferenceScreen>(PreferenceId.Pinout.text) }
    private val where_to_store_results_file by lazy { findPreference<PreferenceScreen>(PreferenceId.Results.text) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setupOnClickCallbacks()
        setupButtonsSummary()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != AppCompatActivity.RESULT_OK) return

        when (requestCode) {
            IntentResultCode.ChooseConfigsFile.value -> {
                val files: ArrayList<MediaFile> = data!!.getParcelableArrayListExtra(FilePickerActivity.MEDIA_FILES)!!

                if (files.size > 1) {
                    return
                }


                saveFileChoiceToSharedPreferences(PreferenceId.Pinout.text,
                                                  files.get(0),
                                                  SharedPreferenceKey.PinoutConfigFileUri.text,
                                                  SharedPreferenceKey.PinoutConfigFileName.text)
            }

            IntentResultCode.ChooseWhereToStoreFile.value -> {
                val files: ArrayList<MediaFile> = data!!.getParcelableArrayListExtra(FilePickerActivity.MEDIA_FILES)!!

                if (files.size > 1) {
                    return
                }
                val file = File(files.get(0).uri.path)
                Log.d(Tag, "AbsolutePath: ${file.absolutePath}")

                val fos = FileOutputStream(file)
                fos.write("Dupa".toByteArray())

                saveFileChoiceToSharedPreferences(PreferenceId.Results.text,
                                                  files.get(0),
                                                  SharedPreferenceKey.ResultsFileUri.text,
                                                  SharedPreferenceKey.ResultsFileName.text)
            }
        }
    }

    private fun setupOnClickCallbacks() {
        pinout_file_preference?.setOnPreferenceClickListener {
            val intent = Intent(context, FilePickerActivity::class.java)
            intent.putExtra(FilePickerActivity.CONFIGS,
                            Configurations
                                .Builder()
                                .setCheckPermission(true)
                                .setShowImages(false)
                                .setShowVideos(false)
                                .setSuffixes(FileExtensions.PinConfig.text)
                                .setShowFiles(true)
                                .setSingleChoiceMode(true)
                                .enableImageCapture(false)
                                .setSkipZeroSizeFiles(false)
                                .build())
            startActivityForResult(intent, IntentResultCode.ChooseConfigsFile.value)

            true
        }

        where_to_store_results_file?.setOnPreferenceClickListener {
            val intent = Intent(context, FilePickerActivity::class.java)
            intent.putExtra(FilePickerActivity.CONFIGS,
                            Configurations
                                .Builder()
                                .setCheckPermission(true)
                                .setShowImages(false)
                                .setShowVideos(false)
                                .setSuffixes(FileExtensions.Results.text)
                                .setShowFiles(true)
                                .setSingleChoiceMode(true)
                                .enableImageCapture(false)
                                .setSkipZeroSizeFiles(false)
                                .build())
            startActivityForResult(intent, IntentResultCode.ChooseWhereToStoreFile.value)


            true
        }
    }

    private fun setupButtonsSummary() {
        context?.let {
            val pref_manager = PreferenceManager.getDefaultSharedPreferences(it)

            val name_of_config_file = pref_manager.getString(SharedPreferenceKey.PinoutConfigFileName.text, "")
            val config_file_uri = pref_manager.getString(SharedPreferenceKey.PinoutConfigFileUri.text, "")
            if (name_of_config_file == "" || config_file_uri == "") {
                pinout_file_preference?.summary = getString(R.string.pref_pinout_filename_defaultval).toString()
            }
            else {
                pinout_file_preference?.summary = name_of_config_file.toString()
            }

            val name_of_where_to_store_file = pref_manager.getString(SharedPreferenceKey.ResultsFileName.text, "")
            val where_to_store_file_uri = pref_manager.getString(SharedPreferenceKey.ResultsFileUri.text, "")
            if (name_of_where_to_store_file == "" || where_to_store_file_uri == "") {
                where_to_store_results_file?.summary = getString(R.string.pref_pinout_filename_defaultval).toString()
            }
            else {
                where_to_store_results_file?.summary = name_of_where_to_store_file.toString()
            }
        }
    }

    private fun saveFileChoiceToSharedPreferences(view_preference_id: String,
                                                  file: MediaFile,
                                                  file_uri_preference_key: String,
                                                  file_name_preference_key: String) {

        val this_preference = findPreference<PreferenceScreen>(view_preference_id)
        this_preference?.summary = file.name.toString()

        context?.let {
            val pref_manager_editor = PreferenceManager
                .getDefaultSharedPreferences(it)
                .edit()

            pref_manager_editor.let {
                it.putString(file_uri_preference_key, file.uri.toString())
                it.putString(file_name_preference_key, file.name.toString())
                it.apply()
            }
        }
    }
}