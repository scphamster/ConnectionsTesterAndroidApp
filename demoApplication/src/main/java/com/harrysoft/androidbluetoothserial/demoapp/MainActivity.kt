package com.harrysoft.androidbluetoothserial.demoapp

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup

import android.widget.RelativeLayout
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProviders

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder

import com.harrysoft.androidbluetoothserial.demoapp.databinding.ActivityMainBinding

import android.util.Log

class MainActivity : AppCompatActivity() {
    private var viewModel: MainActivityViewModel? = null
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //test
        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        viewModel = ViewModelProviders.of(this)
                .get(MainActivityViewModel::class.java)

        // This method return false if there is an error, so if it does, we should close.
        if (!(viewModel!!.setupViewModel())) {
            finish()
            return
        }

        val deviceList = binding.mainDevices
        val swipeRefreshLayout = binding.mainSwiperefresh

        // Setup the RecyclerView
        deviceList.layoutManager = LinearLayoutManager(this)
        val adapter = DeviceAdapter()
        deviceList.adapter = adapter

        // Setup the SwipeRefreshLayout
        swipeRefreshLayout.setOnRefreshListener {
            viewModel?.refreshPairedDevices()
            swipeRefreshLayout.isRefreshing = false
        }

        // Start observing the data sent to us by the ViewModel
        viewModel?.pairedDeviceList?.observe(this@MainActivity) { deviceList: Collection<BluetoothDevice?> ->
            adapter.updateList(deviceList)
        }

        viewModel?.refreshPairedDevices()

        Log.d(TAG, "on create")
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

    // Called when clicking on a device entry to start the CommunicateActivity
    fun startTerminalWithDevice(deviceName: String?, macAddress: String?) {
        Intent(this, CommunicateActivity::class.java).also { intent ->
            intent.putExtra("device_name", deviceName)
            intent.putExtra("device_mac", macAddress)
            startActivity(intent)
        }
    }

    // A class to hold the data in the RecyclerView
    private inner class DeviceViewHolder internal constructor(view: View) : ViewHolder(view) {
        private val layout: RelativeLayout by lazy { view.findViewById(R.id.single_bt_device) }
        private val deviceName: TextView by lazy { view.findViewById(R.id.bluetooth_item_Name) }
        private val deviceAddress: TextView by lazy { view.findViewById(R.id.bluetooth_item_Adress) }

        fun setupView(device: BluetoothDevice?) {
            if (device != null) {
                deviceName.text = device.name
                deviceAddress.text = device.address
            }

            layout.setOnClickListener { view: View? ->
                startTerminalWithDevice(device?.name, device?.address)
            }

            layout.setOnLongClickListener{
                Intent(this@MainActivity, DeviceControlActivity::class.java).also{
                    intent ->
                    intent.putExtra("name", deviceName.text)
                    intent.putExtra("mac", deviceAddress.text)
                    startActivity(intent)
                }

                false
            }
        }
    }

    // A class to adapt our list of devices to the RecyclerView
    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceViewHolder>() {
        private var deviceList = arrayOfNulls<BluetoothDevice>(0)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder =
            DeviceViewHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item, parent, false))

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) = holder.setupView(deviceList[position])
        override fun getItemCount(): Int = deviceList.size

        fun updateList(deviceList: Collection<BluetoothDevice?>) {
            this.deviceList = deviceList.toTypedArray()
            notifyDataSetChanged()
        }
    }

    companion object {
        private const val TAG = "Main"
    }
}