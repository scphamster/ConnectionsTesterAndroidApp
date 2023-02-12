package com.github.scphamster.bluetoothConnectionsTester

//import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.circuit.Pin
import com.github.scphamster.bluetoothConnectionsTester.dataLink.BluetoothBridge
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.HardwareMonitorActty

class DeviceControlActivity : AppCompatActivity() {
    companion object {
        private val Tag: String = "DeviceControl"
        private const val SETTINGS_REQUEST_CODE = 1
    }

    private val model by lazy {
        ViewModelProvider(this).get(DeviceControlViewModel::class.java)
    }
    private val measurementsView by lazy { findViewById<RecyclerView>(R.id.measurements_results) }
    private val actionBarText by lazy {
        supportActionBar?.customView?.findViewById<TextView>(R.id.ctl_actty_actionBar_text)
    }
    private val menu by lazy {
        PopupMenu(this, supportActionBar?.customView?.findViewById(R.id.button_at_custom_action_bar))
    }
    private val warningSign by lazy { findViewById<ImageView>(R.id.ctl_actty_warning_sign) }

    private inner class CheckResultViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {
        private val layout: RelativeLayout by lazy { view.findViewById(R.id.single_check_result) }
        private val pinNumber: TextView by lazy { view.findViewById(R.id.pin_description) }
        private val foundConnections: TextView by lazy { view.findViewById(R.id.connections) }
        private var results_are_for_pin: Pin? = null

        init {
            layout.setOnClickListener {
                val my_pin = results_are_for_pin
                if (my_pin == null) return@setOnClickListener

                model.checkConnections(my_pin)
            }
        }

        fun setup(pin: Pin) {
            results_are_for_pin = pin
            pinNumber.text = pin.descriptor.getPrettyName()
            val RMax = model.maxDetectableResistance

            val span_text_builder = SpannableStringBuilder()
            val normal_color = Color.GREEN
            val difference_color = Color.YELLOW
            for (connection in pin.connections) {
                if (connection.toPin.pinAffinityAndId == pin.descriptor.pinAffinityAndId) continue

                val text_color = if (connection.value_changed_from_previous_check) ForegroundColorSpan(difference_color)
                else if (connection.first_occurrence) ForegroundColorSpan(Color.MAGENTA)
                else ForegroundColorSpan(normal_color)

                if (connection.resistance != null) {
                    if (connection.resistance.value < RMax) span_text_builder.append(connection.toString(), text_color,
                                                                                     SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else span_text_builder.append(connection.toString(), text_color, SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (!pin.isHealthy) {
                foundConnections.text = "Unhealthy!"
                pinNumber.setTextColor(resources.getColor(R.color.unhealthy_pin))
                foundConnections.setTextColor(resources.getColor(R.color.unhealthy_pin))
            }
            else {
                if (pin.connections.size == 1) {
                    foundConnections.text = "Not connected";
                    foundConnections.setTextColor(Color.GRAY)
                }
                else if (span_text_builder.isEmpty()) {
                    foundConnections.text = "Not connected (High R connections not shown)"
                    foundConnections.setTextColor(Color.GRAY)
                }
                else foundConnections.text = span_text_builder

                if (pin.connectionsListChangedFromPreviousCheck) {
                    pinNumber.setTextColor(resources.getColor(R.color.pin_with_altered_connections))
                }
                else {
                    pinNumber.setTextColor(resources.getColor(R.color.pin_with_unaltered_connections))
                }
            }
        }
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<CheckResultViewHolder>() {
        val pins = mutableListOf<Pin>()
        val itemsCount
            get() = pins.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckResultViewHolder {
            val vh = CheckResultViewHolder(LayoutInflater
                                               .from(parent.context)
                                               .inflate(R.layout.ctl_actty_recycler_view_item, parent, false))

            return vh
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
                    pin.connections = pin_to_update.connections
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
//            notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ctl_actty_device_controll)

        setupEntryViewState()
        setupCallbacks()
        setupObservers()
        setupClickListeners()

        setupThresholdResistance()

        if (!model.setupViewModel(intent.getStringExtra("name")!!, intent.getStringExtra("mac"))) {
            Log.e(Tag, "No arguments obtained in DeviceControlActivity onCreate method!")

            finish()
            return
        }

        model.startServer()
    }

    private fun setupEntryViewState() {
        supportActionBar?.setCustomView(R.layout.ctl_actty_action_bar)
        supportActionBar?.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM)

        menu.inflate(R.menu.ctl_actty_popup)

        actionBarText?.text =
            getString(R.string.ctl_actty_tittle_connecting).format(intent.getStringExtra("name") + "...")

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
            actionBarText?.text = getString(R.string.ctl_actty_number_of_connected_boards).format(
                model.measurementsHandler.boardsManager.getBoardsCount())
            (measurementsView.adapter as ResultsAdapter).updatePinSet(it)
        }

        model.measurementsHandler.commander.dataLink.connectionStatus.observe(
            this) { connection_status: BluetoothBridge.ConnectionStatus ->
            when (connection_status) {
                BluetoothBridge.ConnectionStatus.CONNECTED -> {
                    val controller_search_progress = findViewById<ProgressBar>(R.id.searching_for_controller_progbar)
                    actionBarText?.text = "Connected"
                    if (controller_search_progress.visibility == View.VISIBLE) {
                        controller_search_progress.visibility = View.INVISIBLE
                    }

                    if (model.controllerIsNotConfigured) {
                        model.initializeHardware()
                    }
                }

                BluetoothBridge.ConnectionStatus.CONNECTING -> {
                    val controller_search_progress = findViewById<ProgressBar>(R.id.searching_for_controller_progbar)
                    actionBarText?.text = "Connecting"
                    if (controller_search_progress.visibility == View.INVISIBLE) {
                        controller_search_progress.visibility = View.VISIBLE
                    }
                }

                BluetoothBridge.ConnectionStatus.DISCONNECTED -> {
                    actionBarText?.text = "Disconnected!"
                    model.reconnectToController()
                }

            }

        }

        model.errorHandler.errorMessages.observe(this) { warnings ->
            warningSign.visibility = View.VISIBLE

        }

        warningSign.setOnClickListener() { sign ->
            AlertDialog
                .Builder(this)
                .setTitle("Warning")
                .setMessage(model.errorHandler.errorMessages.value?.joinToString { it + " " })
                .create()
                .show()
            sign.visibility = View.INVISIBLE

            model.errorHandler.errorMessages.value?.clear()
        }
    }

    private fun setupClickListeners() {
        val preferences_activity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { answer ->
                if (answer.resultCode == Activity.RESULT_OK) {
                    val new_pinout_file_has_been_chosen = answer.data?.getBooleanExtra(
                        PreferencesFragment.Companion.MessageToInvoker.NewPinoutConfigFileChosen.text, false)

                    if (new_pinout_file_has_been_chosen != null && new_pinout_file_has_been_chosen) {
                        model.getPinoutConfigFile()
                    }
                }

                onPreferencesActivityClosed()
            }

        menu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.ctl_actty_menu_settings_button -> {

                    preferences_activity.launch(Intent(this, PreferencesActty::class.java))

                    return@setOnMenuItemClickListener true
                }

                R.id.ctl_actty_menu_calibrate_button -> {
                    model.calibrate()
                    return@setOnMenuItemClickListener true
                }

                R.id.ctl_actty_menu_disconnect_button -> {
                    model.disconnect()
                    return@setOnMenuItemClickListener true
                }

                R.id.ctl_actty_menu_refresh_button -> {
                    model.refreshHardware()
                    return@setOnMenuItemClickListener true
                }

                R.id.ctl_actty_menu_hardware_monitor_button -> {
                    Intent(this@DeviceControlActivity, HardwareMonitorActty::class.java).also {
                        startActivity(it)
                    }
                    false
                }

                else -> {
                    return@setOnMenuItemClickListener true
                }
            }
        }

        supportActionBar?.customView
            ?.findViewById<Button>(R.id.button_at_custom_action_bar)
            ?.setOnClickListener {
                menu.show()
            }

        findViewById<Button>(R.id.check_connections).setOnClickListener() {
            model.checkConnections()
            Log.d(Tag, "Command sent: ${getString(R.string.set_pin_cmd)}")
        }

        findViewById<Button>(R.id.ctl_actty_save_results_button).setOnClickListener() {
            model.storeMeasurementsToFile()
        }
    }

    private fun onPreferencesActivityClosed() {
        setupThresholdResistance()
        model.setupVoltageLevel()
    }

    private fun setupThresholdResistance() {
        val pref_manager = PreferenceManager.getDefaultSharedPreferences(this)
        val max_resistance =
            pref_manager.getString(PreferencesFragment.Companion.SharedPreferenceKey.MaximumResistance.text, "")
        max_resistance?.let {
            model.setMinimumResistanceToBeRecognizedAsConnection(it)
        }
    }

    private fun toast(msg: String?) {
        Toast
            .makeText(this, msg, Toast.LENGTH_LONG)
            .show()
    }
}