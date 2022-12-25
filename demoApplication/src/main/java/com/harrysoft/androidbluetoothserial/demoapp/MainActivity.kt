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
//import android.support.design.widget.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

//import kotlinx.android.synthetic.main.activity_main.*
//import kotlinx.android.synthetic.main.list_item.*

class MainActivity : AppCompatActivity() {
    private var viewModel: MainActivityViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        // Setup our activity
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup our ViewModel
        viewModel = ViewModelProviders.of(this).get(MainActivityViewModel::class.java)

        // This method return false if there is an error, so if it does, we should close.
        if (!viewModel!!.setupViewModel()) {
            finish()
            return
        }

        // Setup our Views
        val deviceList = findViewById<RecyclerView>(R.id.main_devices)
        val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(R.id.main_swiperefresh)

        // Setup the RecyclerView
        deviceList.layoutManager = LinearLayoutManager(this)
        val adapter = DeviceAdapter()
        deviceList.adapter = adapter

        // Setup the SwipeRefreshLayout
        swipeRefreshLayout.setOnRefreshListener {
            viewModel!!.refreshPairedDevices()
            swipeRefreshLayout.isRefreshing = false
        }

        // Start observing the data sent to us by the ViewModel
        viewModel?.pairedDeviceList?.observe(this@MainActivity) { deviceList: Collection<BluetoothDevice?> ->
            adapter.updateList(
                deviceList
            )
        }

        viewModel?.refreshPairedDevices()
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
    fun startCommunicationsWithDevice(deviceName: String?, macAddress: String?) {
        val intent = Intent(this, CommunicateActivity::class.java)
        intent.putExtra("device_name", deviceName)
        intent.putExtra("device_mac", macAddress)
        startActivity(intent)
    }

    // A class to hold the data in the RecyclerView
    private inner class DeviceViewHolder internal constructor(view: View) : ViewHolder(view) {
        private val layout: RelativeLayout
        private val deviceName: TextView
        private val deviceAddress: TextView

        init {
            layout = view.findViewById(R.id.list_item);
            deviceName = view.findViewById(R.id.bluetooth_item_Name)
            deviceAddress = view.findViewById(R.id.bluetooth_item_Adress)

//            layout = list_item
//            deviceName = bluetooth_item_Name
//            deviceAddress = bluetooth_item_Adress
        }

        fun setupView(device: BluetoothDevice?) {
            if (device != null) {
                deviceName.text = device.name
                deviceAddress.text = device.address
            }

            layout.setOnClickListener { view: View? ->
                startCommunicationsWithDevice(
                    device?.name, device?.address
                )
            }
        }
    }

    // A class to adapt our list of devices to the RecyclerView
    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceViewHolder>() {
        private var deviceList = arrayOfNulls<BluetoothDevice>(0)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            return DeviceViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.list_item, parent, false))
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            holder.setupView(deviceList[position])
        }

        override fun getItemCount(): Int {
            return deviceList.size
        }

        fun updateList(deviceList: Collection<BluetoothDevice?>) {
            this.deviceList = deviceList.toTypedArray()
            notifyDataSetChanged()
        }
    }
}