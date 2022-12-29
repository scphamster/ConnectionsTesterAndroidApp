package com.harrysoft.androidbluetoothserial.demoapp

//import android.R
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.*
import kotlinx.android.synthetic.main.actty_device_controll.*

class DeviceControlActivity : AppCompatActivity() {
    private val model by lazy {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                .create(DeviceControlViewModel::class.java)
    }
    private val numberOfFoundBoards by lazy { findViewById<TextView>(R.id.number_of_found_boards_vw) }
    private val connectivityResults by lazy { findViewById<RecyclerView>(R.id.connectivity_results) }

    private inner class CheckResultViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {
        private val layout: RelativeLayout by lazy { view.findViewById(R.id.single_check_result) }
        private val pinNumber: TextView by lazy { view.findViewById(R.id.pin_description) }
        private val foundConnections: TextView by lazy { view.findViewById(R.id.connections) }

        fun setup(pin_data: Pair<String, Array<PinNumberT>>) {
            pinNumber.text = pin_data.first

            if (pin_data.second.isEmpty()) {
                foundConnections.text = "Not connected"
            }
            else {
                foundConnections.text = pin_data.second.joinToString(" ")
            }
        }
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<CheckResultViewHolder>() {
        private var numberOfPins = 0
        private var pinsConnections: MutableList<PinConnections> = mutableListOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckResultViewHolder {
            return CheckResultViewHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.check_result_item, parent, false))
        }

        override fun getItemCount() = model.commandHandler.ioBoards.pinsConnections.value?.size ?: 0

        override fun onBindViewHolder(holder: CheckResultViewHolder, position: Int) {
            if (position >= pinsConnections.size) {
                Log.e(Tag,
                    "in function onBindViewHolder pins number is lower than requested by position argument: $position")
                return
            }

            holder.setup(Pair(pinsConnections.get(position).pin.toString(),
                pinsConnections.get(position).connections.toTypedArray()))
        }

        fun updateAll(pins_connections: Collection<PinConnections>) {
            pinsConnections = pins_connections.toMutableList()
            notifyDataSetChanged()
        }

        fun updateOne(connections_for_pin: PinConnections) {
        }

        fun updateSinglePinConnections(pin: PinNumT, connections: PinConnections) {
            pinsConnections[pin] = connections
            notifyItemChanged(pin)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.actty_device_controll)

        if (!model.setupViewModel(intent.getStringExtra("name")!!, intent.getStringExtra("mac"))) {
            finish()
            return
        }

        connectivityResults.layoutManager = LinearLayoutManager(this)
        val adapter = ResultsAdapter()
        connectivityResults.adapter = adapter

        model.commandHandler.ioBoards.pinsConnections.observe(this) { connections: Collection<PinConnections> ->
            adapter.updateAll(connections)
        }

        setupAllListeners()

        Log.d(Tag, "device control created")
    }

    override fun onBackPressed() {
        finish()
    }

    private fun setupAllListeners() {
        model.commandHandler.numberOfConnectedBoards.observe(this) { boards_count: BoardCountT ->
            numberOfFoundBoards.text = numberOfFoundBoards.text.toString()
                    .format(boards_count)
        }

        model.commandHandler.connectionStatus.observe(this) { connection_status: CommandHandler.ConnectionStatus ->
            when (connection_status) {
                CommandHandler.ConnectionStatus.CONNECTED -> {
                    model.commandHandler.test1()
                }

                else -> {}
            }

        }

        findViewById<Button>(R.id.cmd1_button).setOnClickListener() {
            model.commandHandler.connect()
        }

        findViewById<Button>(R.id.cmd2_button).setOnClickListener() {
            model.commandHandler.sendCommand(CommandInterpreter.Commands.CheckConnectivity())
            Log.d(Tag, "Command sent: ${getString(R.string.set_pin_cmd)}")
        }
    }

    companion object {
        private val Tag: String = "DeviceControl"
    }
}