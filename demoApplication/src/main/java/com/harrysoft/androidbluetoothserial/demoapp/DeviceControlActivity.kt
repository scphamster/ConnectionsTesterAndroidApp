package com.harrysoft.androidbluetoothserial.demoapp

//import android.R
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.harrysoft.androidbluetoothserial.demoapp.device_interface.*

class DeviceControlActivity : AppCompatActivity() {
    private val model by lazy {
        ViewModelProvider.AndroidViewModelFactory
            .getInstance(application)
            .create(DeviceControlViewModel::class.java)
    }
    private val numberOfFoundBoards by lazy { findViewById<TextView>(R.id.number_of_found_boards_vw) }
    private val connectivityResults by lazy { findViewById<RecyclerView>(R.id.connectivity_results) }

    private inner class CheckResultViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {
        private val layout: RelativeLayout by lazy { view.findViewById(R.id.single_check_result) }
        private val pinNumber: TextView by lazy { view.findViewById(R.id.pin_description) }
        private val foundConnections: TextView by lazy { view.findViewById(R.id.connections) }

        fun setup(pin: Pin) {
            //todo: refactor and optimize
            if (pin.descriptor.name != null) pinNumber.text = pin.descriptor.name
            else if (pin.descriptor.customIdx != null) pinNumber.text = pin.descriptor.customIdx.toString()
            else if (pin.descriptor.group != null) {
                if (pin.descriptor.group.groupName != null) pinNumber.text = pin.descriptor.group.groupName
                else pinNumber.text = pin.descriptor.group.groupId.toString()
            }

            if (pin.isConnectedTo.isEmpty()) {
                foundConnections.text = "Not connected"
            }
            else {
                //todo: use name and other field if available
                foundConnections.text = pin.isConnectedTo.joinToString(" ") {
                    it.affinityAndId.boardAffinityId.toString() + ':' + it.affinityAndId.idxOnBoard.toString()
                }
            }
        }
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<CheckResultViewHolder>() {
        val pins = mutableListOf<Pin>()
        val itemsCount = pins.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckResultViewHolder {
            return CheckResultViewHolder(LayoutInflater
                                             .from(parent.context)
                                             .inflate(R.layout.check_result_item, parent, false))
        }

        override fun getItemCount() = itemsCount
        override fun onBindViewHolder(holder: CheckResultViewHolder, position: Int) {
            if (position >= itemsCount) {
                Log.e(Tag,
                      "in function onBindViewHolder pins number is lower than requested by position argument: $position")
                return
            }
            holder.setup(pins.get(position))
        }

        fun updateSingle(pin_to_update: Pin) {
            var counter = 0
            for (pin in pins) {
                if (pin.descriptor.affinityAndId == pin_to_update.descriptor.affinityAndId) {
                    pin.isConnectedTo = pin_to_update.isConnectedTo
                    notifyItemChanged(counter)
                    return
                }

                counter++
            }

            Log.e(Tag,
                  """Pin ${pin_to_update.descriptor.affinityAndId.boardAffinityId}:
                      |${pin_to_update.descriptor.affinityAndId.idxOnBoard} 
                      |is not found in stored pins list!""".trimMargin())
        }

        fun updatePinsFromBoards(boards: MutableList<IoBoard>) {
            if (boards.isEmpty()) {
                Log.e(Tag, "Boards are empty!")
                return
            }

            //todo: check if smart addition of pins would not be better option
            if (!pins.isEmpty()) {
                val size = pins.size
                pins.clear()

                notifyItemRangeRemoved(0, size - 1)
            }

            for (board in boards) {
                if (board.pins.isEmpty()) {
                    Log.e(Tag, "supplied board with id ${board.id} has empty list of pins")
                    return
                }

                for (pin in board.pins) {
                    pins.add(pin)
                }
            }

            //todo: not sure if this is a good handling of notifications, make research
            notifyItemRangeInserted(0, pins.size - 1)

            //test
            notifyDataSetChanged()
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

        model.commandHandler.boardsManager.boards.observe(this) {
            adapter.updatePinsFromBoards(it)
        }

        model.commandHandler.boardsManager.pinChangeCallback = { adapter.updateSingle(it) }

        setupAllListeners()

        Log.d(Tag, "device control created")
    }

    override fun onBackPressed() {
        finish()
    }

    private fun setupAllListeners() {
        model.commandHandler.numberOfConnectedBoards.observe(this) { boards_count: BoardCountT ->
            numberOfFoundBoards.text = numberOfFoundBoards.text
                .toString()
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