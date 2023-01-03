package com.github.scphamster.bluetoothConnectionsTester

//import android.R
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.scphamster.bluetoothConnectionsTester.R
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.MeasurementsHandler
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.Pin

class DeviceControlActivity : AppCompatActivity() {
    private val model by lazy {
        ViewModelProvider.AndroidViewModelFactory
            .getInstance(application)
            .create(DeviceControlViewModel::class.java)
    }
    private val numberOfFoundBoards by lazy { findViewById<TextView>(R.id.number_of_found_boards_vw) }
    private val connectionsDisplay by lazy { findViewById<RecyclerView>(R.id.connectivity_results) }
    private inner class CheckResultViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {
        private val layout: RelativeLayout by lazy { view.findViewById(R.id.single_check_result) }
        private val pinNumber: TextView by lazy { view.findViewById(R.id.pin_description) }
        private val foundConnections: TextView by lazy { view.findViewById(R.id.connections) }

        fun setup(pin: Pin) {
            //todo: refactor and optimize
            if (pin.descriptor.name != null) pinNumber.text = pin.descriptor.name
            else if (pin.descriptor.customIdx != null) pinNumber.text = pin.descriptor.customIdx.toString()
            else if (pin.descriptor.group != null) {
                if (pin.descriptor.group.name != null) pinNumber.text = pin.descriptor.group.name
                else pinNumber.text =
                    pin.descriptor.group.id.toString() + ':' + pin.descriptor.affinityAndId.idxOnBoard.toString()
            }
            else {
                pinNumber.text =
                    pin.descriptor.affinityAndId.boardId.toString() + ':' + pin.descriptor.affinityAndId.idxOnBoard.toString()
            }

            if (pin.isConnectedTo.isEmpty()) {
                foundConnections.text = "Not connected"
            }
            else {
                //todo: use name and other field if available
                foundConnections.text = pin.isConnectedTo.joinToString(" ") {
                    it.affinityAndId.boardId.toString() + ':' + it.affinityAndId.idxOnBoard.toString()
                }
            }
        }
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<CheckResultViewHolder>() {
        val pins = mutableListOf<Pin>()
        val itemsCount
            get() = pins.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckResultViewHolder {
            return CheckResultViewHolder(LayoutInflater
                                             .from(parent.context)
                                             .inflate(R.layout.ctl_actty_recycler_view_item, parent, false))
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

            Log.e(Tag, """Pin ${pin_to_update.descriptor.affinityAndId.boardId}:
                      |${pin_to_update.descriptor.affinityAndId.idxOnBoard} 
                      |is not found in stored pins list!""".trimMargin())
        }

        fun updatePinSet(boards: MutableList<IoBoard>) {
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
        setContentView(R.layout.ctl_actty_device_controll)
        if (!model.setupViewModel(intent.getStringExtra("name")!!, intent.getStringExtra("mac"))) {
            Log.e(Tag, "No arguments obtained in DeviceControlActivity onCreate method!")

            finish()
            return
        }

        applyPreferences()
        setupEntryViewState()

        connectionsDisplay.layoutManager = LinearLayoutManager(this)
        val adapter = ResultsAdapter()
        connectionsDisplay.adapter = adapter

        model.measurementsHandler.boardsManager.boards.observe(this) {
            numberOfFoundBoards.text =
                getString(R.string.ctl_actty_number_of_connected_boards).format(model.measurementsHandler.boardsManager.getBoardsCount())
            adapter.updatePinSet(it)
        }

        model.measurementsHandler.boardsManager.pinChangeCallback = { adapter.updateSingle(it) }
        setupAllListeners()
        Log.d(Tag, "device control created")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ctl_actty_menu, menu)
        Log.d(Tag, "Menu button created!")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        val intent = Intent(this, PreferencesActty::class.java)
        startActivity(intent)

        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        finish()
    }

    private fun setupEntryViewState() {
        supportActionBar?.setTitle(getString(R.string.ctl_actty_tittle_connecting).format(intent.getStringExtra("name") + "..."))
        numberOfFoundBoards.text = getString(R.string.ctl_actty_number_of_connected_boards).format(0)

        val controller_search_progress = findViewById<ProgressBar>(R.id.searching_for_controller_progbar)
        controller_search_progress.visibility = View.VISIBLE

        //todo: use theme instead of manual setup
        window.statusBarColor = ContextCompat.getColor(this@DeviceControlActivity, R.color.ctl_actty_status_bar)
    }

    private fun setupAllListeners() {
        model.measurementsHandler.connectionStatus.observe(this) { connection_status: MeasurementsHandler.ConnectionStatus ->
            when (connection_status) {
                MeasurementsHandler.ConnectionStatus.CONNECTED -> {
                    val controller_search_progress = findViewById<ProgressBar>(R.id.searching_for_controller_progbar)

                    if (controller_search_progress.visibility == View.VISIBLE) {
                        controller_search_progress.visibility = View.INVISIBLE
                    }

//                    supportActionBar?.setTitle(getString(R.string.ctl_actty_tittle_connected).format(intent.getStringExtra(
//                        "name")))

                    supportActionBar?.hide()
                }

                else -> {}
            }

        }

        findViewById<Button>(R.id.cmd1_button).setOnClickListener() {
            model.measurementsHandler.connect()
        }

        findViewById<Button>(R.id.cmd2_button).setOnClickListener() {
            model.measurementsHandler.sendCommand(ControllerResponseInterpreter.Commands.CheckConnectivity())
            Log.d(Tag, "Command sent: ${getString(R.string.set_pin_cmd)}")
        }

        findViewById<Button>(R.id.ctl_actty_save_results_button).setOnClickListener(){
            model.measurementsHandler.storeMeasurementsResultsToFile()
        }
    }

    private fun applyPreferences() {
    }

    companion object {
        private val Tag: String = "DeviceControl"
    }
}