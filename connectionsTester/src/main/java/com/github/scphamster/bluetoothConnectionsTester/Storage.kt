package com.github.scphamster.bluetoothConnectionsTester

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.FileNotFoundException
import java.io.OutputStream

class Storage {
    companion object {
        suspend fun storeToFile(workbook: XSSFWorkbook, context: Context) =
            withContext(Dispatchers.IO) {
                val file_storage_uri = PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getString(PreferencesFragment.Companion.SharedPreferenceKey.ResultsFileUri.text, "")

                val file_storage_name = PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getString(PreferencesFragment.Companion.SharedPreferenceKey.ResultsFileName.text, "")

                if (file_storage_uri == "") {
                    throw Error(
                        "No file for storage selected! Go to settings and set it via: Specify file where to store results")
                }

                val outputStream: OutputStream? = try {
                    context.contentResolver.openOutputStream(Uri.parse(file_storage_uri))
                }
                catch (e: FileNotFoundException) {
                    throw Error("File was not found, ${e.message}")
                }

                if (outputStream == null) {
                    throw Error("File $file_storage_name not found! Go to settings and set new output file!")
                }

                workbook.write(outputStream)
                outputStream.close()
            }

        suspend fun getWorkBookFromFile(context: Context): XSSFWorkbook = withContext(Dispatchers.IO){
            val file_uri = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(PreferencesFragment.Companion.SharedPreferenceKey.PinoutConfigFileUri.text, "")

            val file_name = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(PreferencesFragment.Companion.SharedPreferenceKey.PinoutConfigFileName.text, "")

            if (file_uri == "") throw Error("No file for pinout description found!")

            val inputStream = context.contentResolver.openInputStream(Uri.parse(file_uri))
            val workbook = XSSFWorkbook(inputStream)

            inputStream?.close()

            workbook
        }
    }
}