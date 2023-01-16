package com.github.scphamster.bluetoothConnectionsTester

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.*
import com.jaiselrahman.filepicker.activity.FilePickerActivity
import com.jaiselrahman.filepicker.config.Configurations
import com.jaiselrahman.filepicker.model.MediaFile
import kotlin.math.max

class PreferencesFragment : PreferenceFragmentCompat() {
    companion object {
        const val Tag = "PreferencesFragment"

        public enum class IntentResultCode(val value: Int) {
            ChooseConfigsFile(1),
            ChooseWhereToStoreFile(2)
        }

        public enum class MessageToInvoker(val text: String) {
            NewPinoutConfigFileChosen("new config file")
        }

        public enum class SharedPreferenceKey(val text: String) {
            PinoutConfigFileUri("pref_pinout_config_file_uri"),
            PinoutConfigFileName("pref_pinout_config_file_name"),
            ResultsFileUri("pref_results_file_uri"),
            ResultsFileName("pref_results_file_name"),
            MaximumResistance("maximum_resistance_as_connection"),
            SequentialModeScan("sequential_boards_scan")
        }

        private enum class FileExtensions(val text: String) {
            PinConfig("xlsx"),
            Results("xlsx")
        }

        private enum class PreferenceId(val text: String) {
            Pinout("pref_pinout_descriptor"),
            Results("pref_results_file"),
            MaximumResistance("maximum_resistance_as_connection")
        }
    }

    interface DataBridgeToActivity {
        fun setIntent(intent: Intent)
    }

    private val pinoutFilePreference by lazy { findPreference<PreferenceScreen>(PreferenceId.Pinout.text) }
    private val whereToStoreResultsFile by lazy { findPreference<PreferenceScreen>(PreferenceId.Results.text) }
    private val maximumResistanceAsConnection by lazy {
        findPreference<EditTextPreference>(PreferenceId.MaximumResistance.text)
    }
    private lateinit var dataBridgeToActivity: DataBridgeToActivity
    override fun onAttach(context: Context) {
        super.onAttach(context)

        dataBridgeToActivity = context as DataBridgeToActivity
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setupOnClickCallbacks()
        setupCallbacks()
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

                dataBridgeToActivity.setIntent(
                    Intent().putExtra(MessageToInvoker.NewPinoutConfigFileChosen.text, true))

                saveFileChoiceToSharedPreferences(PreferenceId.Pinout.text, files.get(0),
                                                  SharedPreferenceKey.PinoutConfigFileUri.text,
                                                  SharedPreferenceKey.PinoutConfigFileName.text)
            }

            IntentResultCode.ChooseWhereToStoreFile.value -> {
                val files: ArrayList<MediaFile> = data!!.getParcelableArrayListExtra(FilePickerActivity.MEDIA_FILES)!!

                if (files.size > 1) {
                    return
                }

                saveFileChoiceToSharedPreferences(PreferenceId.Results.text, files.get(0),
                                                  SharedPreferenceKey.ResultsFileUri.text,
                                                  SharedPreferenceKey.ResultsFileName.text)
            }
        }
    }

    private fun setupMaximumResistanceSummary(new_summary: String) {
        if (new_summary == "") {
            maximumResistanceAsConnection?.summary = "Not set"
        }
        else {
            maximumResistanceAsConnection?.summary = new_summary
        }
    }

    private fun setupOnClickCallbacks() {
        pinoutFilePreference?.setOnPreferenceClickListener {
            val intent = Intent(context, FilePickerActivity::class.java)
            intent.putExtra(FilePickerActivity.CONFIGS, Configurations
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

        whereToStoreResultsFile?.setOnPreferenceClickListener {
            val intent = Intent(context, FilePickerActivity::class.java)
            intent.putExtra(FilePickerActivity.CONFIGS, Configurations
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
    private fun setupCallbacks() {
        maximumResistanceAsConnection?.setOnPreferenceChangeListener { preference, newValue ->
            val max_resistance_as_text = newValue.toString()
            setupMaximumResistanceSummary(max_resistance_as_text)
            true
        }
    }
    private fun setupButtonsSummary() {
        context?.let {
            val pref_manager = PreferenceManager.getDefaultSharedPreferences(it)

            val name_of_config_file = pref_manager.getString(SharedPreferenceKey.PinoutConfigFileName.text, "")
            val config_file_uri = pref_manager.getString(SharedPreferenceKey.PinoutConfigFileUri.text, "")
            if (name_of_config_file == "" || config_file_uri == "") {
                pinoutFilePreference?.summary = getString(R.string.pref_pinout_filename_defaultval).toString()
            }
            else {
                pinoutFilePreference?.summary = name_of_config_file.toString()
            }

            val name_of_where_to_store_file = pref_manager.getString(SharedPreferenceKey.ResultsFileName.text, "")
            val where_to_store_file_uri = pref_manager.getString(SharedPreferenceKey.ResultsFileUri.text, "")
            if (name_of_where_to_store_file == "" || where_to_store_file_uri == "") {
                whereToStoreResultsFile?.summary = getString(R.string.pref_pinout_filename_defaultval).toString()
            }
            else {
                whereToStoreResultsFile?.summary = name_of_where_to_store_file.toString()
            }

            val max_resistance_as_text = pref_manager.getString(PreferenceId.MaximumResistance.text, "")
            max_resistance_as_text?.let {
                setupMaximumResistanceSummary(it)
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