package com.harrysoft.androidbluetoothserial.demoapp

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.jaiselrahman.filepicker.activity.FilePickerActivity
import com.jaiselrahman.filepicker.config.Configurations
import com.jaiselrahman.filepicker.model.MediaFile

import android.util.Log
import org.json.JSONObject
import java.io.*
import com.opencsv.*

class MainActivity : AppCompatActivity() {
    private inner class DeviceViewHolder internal constructor(view: View) : ViewHolder(view) {
        private val layout: RelativeLayout by lazy { view.findViewById(R.id.single_bt_device) }
        private val deviceName: TextView by lazy { view.findViewById(R.id.bluetooth_item_Name) }
        private val deviceAddress: TextView by lazy { view.findViewById(R.id.bluetooth_item_Adress) }

        fun setupView(device: BluetoothDevice?) {
            if (device != null) {
                deviceName.text = device.name
                deviceAddress.text = device.address

                lastUsedDeviceMacAddress?.let {
                    if (deviceAddress.text.toString() == it) {
                        deviceAddress.setTextColor(ContextCompat.getColor(this@MainActivity,
                                                                          R.color.color_last_used_device))
                        deviceName.setTextColor(ContextCompat.getColor(this@MainActivity,
                                                                       R.color.color_last_used_device))
                    }
                }
            }

            layout.setOnClickListener { view: View? ->
                onDeviceChoice(deviceAddress.text.toString())
                startTerminalWithDevice(device?.name, device?.address)
            }

            layout.setOnLongClickListener {
                onDeviceChoice(deviceAddress.text.toString())

                Intent(this@MainActivity, DeviceControlActivity::class.java).also { intent ->
                    intent.putExtra("name", deviceName.text)
                    intent.putExtra("mac", deviceAddress.text)
                    startActivity(intent)
                }

                false
            }
        }
    }
    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceViewHolder>() {
        private var deviceList = arrayOfNulls<BluetoothDevice>(0)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder =
            DeviceViewHolder(LayoutInflater
                                 .from(parent.context)
                                 .inflate(R.layout.list_item, parent, false))

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) = holder.setupView(deviceList[position])
        override fun getItemCount(): Int = deviceList.size

        fun updateList(deviceList: Collection<BluetoothDevice?>) {
            this.deviceList = deviceList.toTypedArray()
            notifyDataSetChanged()
        }
    }
    companion object {
        private const val Tag = "Main"
        private const val FILE_REQUEST_CODE = 1

        private enum class StateKey(val text: String) {
            LastUsedBluetoothDevice("last_bt_device")
        }
    }

    private var viewModel: MainActivityViewModel? = null
    private var lastUsedDeviceMacAddress: String? = null
    private val buttonOpenConfigsJson: Button by lazy { findViewById(R.id.main_actty_button_open_json) }
    private var csvList = mutableListOf<Array<String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lastUsedDeviceMacAddress
        getPreferences(Context.MODE_PRIVATE)
            .getString(StateKey.LastUsedBluetoothDevice.text, "")
            .toString()

        setContentView(R.layout.activity_main)
        viewModel = ViewModelProviders
            .of(this)
            .get(MainActivityViewModel::class.java)

        if (!(viewModel!!.setupViewModel())) {
            finish()
            return
        }

        val deviceList = findViewById<RecyclerView>(R.id.main_devices)
        val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(R.id.main_swiperefresh)

        deviceList.layoutManager = LinearLayoutManager(this)
        val adapter = DeviceAdapter()
        deviceList.adapter = adapter

        swipeRefreshLayout.setOnRefreshListener {
            viewModel?.refreshPairedDevices()
            swipeRefreshLayout.isRefreshing = false
        }

        buttonOpenConfigsJson.setOnClickListener{
            val intent = Intent(this, FilePickerActivity::class.java)
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
        }

        viewModel?.pairedDeviceList?.observe(this@MainActivity) { deviceList: Collection<BluetoothDevice?> ->
            adapter.updateList(deviceList)
        }

        viewModel?.refreshPairedDevices()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            val files: ArrayList<MediaFile> = data!!.getParcelableArrayListExtra(FilePickerActivity.MEDIA_FILES)!!
            if (files.size > 1) {
                Log.e(Tag, "only one file should be allowed for selection!")
                return
            }

            Log.i(Tag, files.get(0).name.toString())
            Log.i(Tag, files.get(0).uri.toString())

            val _file = files.get(0)

            val contentResolver = contentResolver
            val inputStream = contentResolver.openInputStream(Uri.parse(_file.uri.toString()))
            val inputStreamReader = InputStreamReader(inputStream, "utf-8")
            val csvReader = CSVReader(inputStreamReader)

            csvList = csvReader.readAll()

        }

    }

    override fun onSaveInstanceState(savedState: Bundle) {
        super.onSaveInstanceState(savedState)
        lastUsedDeviceMacAddress?.let {
            savedState.putString(StateKey.LastUsedBluetoothDevice.text, it)
            getPreferences(Context.MODE_PRIVATE)
                .edit()
                .putString(StateKey.LastUsedBluetoothDevice.text, lastUsedDeviceMacAddress)
                .apply()

        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        finish()
    }

    private fun onDeviceChoice(mac: String?) {
        mac?.let { lastUsedDeviceMacAddress = it }
    }

    private fun startTerminalWithDevice(deviceName: String?, macAddress: String?) {
        Intent(this, CommunicateActivity::class.java).also { intent ->
            intent.putExtra("device_name", deviceName)
            intent.putExtra("device_mac", macAddress)
            startActivity(intent)
        }
    }
}