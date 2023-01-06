package com.github.scphamster.bluetoothConnectionsTester

//import android.R
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.*

class DeviceControlActivity : AppCompatActivity() {
    private val model by lazy {
        ViewModelProvider(this).get(DeviceControlViewModel::class.java)
    }
    private val measurementsView by lazy { findViewById<RecyclerView>(R.id.measurements_results) }
    private val popup by lazy {
        PopupMenu(this, supportActionBar?.customView?.findViewById(R.id.button_at_custom_action_bar))
    }

    private inner class CheckResultViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {
        private val layout: RelativeLayout by lazy { view.findViewById(R.id.single_check_result) }
        private val pinNumber: TextView by lazy { view.findViewById(R.id.pin_description) }
        private val foundConnections: TextView by lazy { view.findViewById(R.id.connections) }

        fun setup(pin: Pin) {
            pinNumber.text = pin.descriptor.getPrettyName()

            if (pin.isConnectedTo.isEmpty()) {
                foundConnections.text = "Not connected"
            }
            else {
                //todo: use name and other field if available
                foundConnections.text = pin.isConnectedTo.joinToString(" ") {
                    it.getPrettyName()
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

        setupEntryViewState()
        setupCallbacks()
        setupObservers()
        setupClickListeners()

        if (!model.setupViewModel(intent.getStringExtra("name")!!, intent.getStringExtra("mac"))) {
            Log.e(Tag, "No arguments obtained in DeviceControlActivity onCreate method!")

            finish()
            return
        }

    }

    override fun onBackPressed() {
        finish()
    }

    private fun setupEntryViewState() {
//        supportActionBar?.setTitle(
//            getString(R.string.ctl_actty_tittle_connecting).format(intent.getStringExtra("name") + "..."))

        supportActionBar?.setCustomView(R.layout.ctl_actty_action_bar)
        supportActionBar?.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM)
        popup.inflate(R.menu.ctl_actty_popup)

        //todo: use theme instead of manual setup
        window.statusBarColor = ContextCompat.getColor(this@DeviceControlActivity, R.color.ctl_actty_status_bar)

        measurementsView.layoutManager = LinearLayoutManager(this)
        val adapter = ResultsAdapter()
        measurementsView.adapter = adapter
    }

    private fun setupCallbacks() {
        model.measurementsHandler.boardsManager.pinChangeCallback = {
            (measurementsView.adapter as ResultsAdapter).updateSingle(it)
        }
    }

    private fun setupObservers() {
        model.measurementsHandler.boardsManager.boards.observe(this) {
//            supportActionBar?.setTitle(getString(R.string.ctl_actty_number_of_connected_boards).format(
//                model.measurementsHandler.boardsManager.getBoardsCount()))
            (measurementsView.adapter as ResultsAdapter).updatePinSet(it)
        }

        model.measurementsHandler.commander.dataLink.connectionStatus.observe(
            this) { connection_status: BluetoothBridge.ConnectionStatus ->
            when (connection_status) {
                BluetoothBridge.ConnectionStatus.CONNECTED -> {
                    val controller_search_progress = findViewById<ProgressBar>(R.id.searching_for_controller_progbar)

                    if (controller_search_progress.visibility == View.VISIBLE) {
                        controller_search_progress.visibility = View.INVISIBLE
                    }

                    model.measurementsHandler.commander.sendCommand(
                        ControllerResponseInterpreter.Commands.CheckHardware())
                }

                BluetoothBridge.ConnectionStatus.CONNECTING -> {
                    val controller_search_progress = findViewById<ProgressBar>(R.id.searching_for_controller_progbar)

                    if (controller_search_progress.visibility == View.INVISIBLE) {
                        controller_search_progress.visibility = View.VISIBLE
                    }
                }

                else -> {}
            }

        }
    }

    private fun setupClickListeners() {
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.ctl_actty_menu_settings_button -> {
                    val intent = Intent(this, PreferencesActty::class.java)
                    startActivity(intent)
                    return@setOnMenuItemClickListener true
                }

                else -> {
                    return@setOnMenuItemClickListener true
                }

            }

        }

        supportActionBar?.customView
            ?.findViewById<Button>(R.id.button_at_custom_action_bar)
            ?.setOnClickListener {
                popup.show()
            }

        findViewById<Button>(R.id.cmd2_button).setOnClickListener() {
            model.measurementsHandler.commander.sendCommand(ControllerResponseInterpreter.Commands.CheckConnectivity())
            Log.d(Tag, "Command sent: ${getString(R.string.set_pin_cmd)}")
        }

        findViewById<Button>(R.id.ctl_actty_save_results_button).setOnClickListener() {
            model.storeMeasurementsToFile()
        }
    }

    private fun applyPreferences() {
    }

    private fun toast(msg: String?) {
        Toast
            .makeText(this, msg, Toast.LENGTH_LONG)
            .show()
    }

    companion object {
        private val Tag: String = "DeviceControl"
    }
}