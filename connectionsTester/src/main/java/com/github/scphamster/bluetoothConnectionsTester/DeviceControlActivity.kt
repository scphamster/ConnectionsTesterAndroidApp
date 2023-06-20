package com.github.scphamster.bluetoothConnectionsTester

//import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.StrictMode
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.scphamster.bluetoothConnectionsTester.circuit.IoBoard
import com.github.scphamster.bluetoothConnectionsTester.circuit.Pin
import com.github.scphamster.bluetoothConnectionsTester.device.Director
import com.github.scphamster.bluetoothConnectionsTester.device.HardwareMonitorActty

class DeviceControlActivity : AppCompatActivity() {
    companion object {
        private val Tag: String = "DeviceControl"
        private const val SETTINGS_REQUEST_CODE = 1
    }

    private val model by lazy { ViewModelProvider(this).get(DeviceControlViewModel::class.java) }
    private val measurementsView by lazy { findViewById<RecyclerView>(R.id.measurements_results) }
    private val actionBarText by lazy { supportActionBar?.customView?.findViewById<TextView>(R.id.ctl_actty_actionBar_text) }
    private val menu by lazy{ PopupMenu(this, supportActionBar?.customView?.findViewById(R.id.button_at_custom_action_bar)) }
    private val warningSign by lazy { findViewById<ImageView>(R.id.ctl_actty_warning_sign) }

    private inner class MeasResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val relLayout: RelativeLayout by lazy { view.findViewById(R.id.single_check_result) }
        private val pinNumberView: TextView by lazy { view.findViewById(R.id.pin_description) }
        private val connectionsView: TextView by lazy { view.findViewById(R.id.connections) }
        private lateinit var pin: Pin

        init {
            relLayout.setOnClickListener {
                model.checkConnections(pin)
            }
        }

        fun setup(pinToBeHandled: Pin) {
            pin = pinToBeHandled
            pinNumberView.text = pinToBeHandled.descriptor.getPrettyName()

            // RMax is used to differentiate connections with their visibility
            val rMax = model.maxDetectableResistance

            val spanTextBuilder = SpannableStringBuilder()
            val stableCol = Color.GREEN
            val differentFromPrevCol = Color.YELLOW
            for (connection in pinToBeHandled.connections) {
                if (connection.toPin.pinAffinityAndId == pinToBeHandled.descriptor.pinAffinityAndId) continue

                val textColor = if (connection.valueChangedFromPreviousCheck) ForegroundColorSpan(differentFromPrevCol)
                else if (connection.firstOccurrence) ForegroundColorSpan(Color.MAGENTA)
                else ForegroundColorSpan(stableCol)

                if (connection.resistance != null) {
                    if (connection.resistance.value < rMax) spanTextBuilder.append(
                        connection.toString(),
                        textColor,
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else spanTextBuilder.append(connection.toString(), textColor, SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (!pinToBeHandled.isHealthy) {
                connectionsView.text = "Unhealthy!"
                pinNumberView.setTextColor(resources.getColor(R.color.unhealthy_pin))
                connectionsView.setTextColor(resources.getColor(R.color.unhealthy_pin))
            } else {
                if (pinToBeHandled.connections.size == 1) {
                    connectionsView.text = "Not connected";
                    connectionsView.setTextColor(Color.GRAY)
                } else if (spanTextBuilder.isEmpty()) {
                    connectionsView.text = "Not connected (High R connections not shown)"
                    connectionsView.setTextColor(Color.GRAY)
                } else connectionsView.text = spanTextBuilder

                if (pinToBeHandled.connectionsListChangedFromPreviousCheck) {
                    pinNumberView.setTextColor(resources.getColor(R.color.pin_with_altered_connections))
                } else {
                    pinNumberView.setTextColor(resources.getColor(R.color.pin_with_unaltered_connections))
                }
            }
        }
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<MeasResultViewHolder>() {
        val pins = mutableListOf<Pin>()
        val itemsCount
            get() = pins.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeasResultViewHolder {
            val vh = MeasResultViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.ctl_actty_recycler_view_item, parent, false)
            )

            return vh
        }

        override fun getItemCount() = itemsCount
        override fun onBindViewHolder(holder: MeasResultViewHolder, position: Int) {
            if (position >= itemsCount) {
                Log.e(
                    Tag,
                    "in function onBindViewHolder pins number is lower than requested by position argument: $position"
                )
                return
            }
            holder.setup(pins[position])
        }

        fun updateSingle(pin_to_update: Pin) {
            var counter = 0
            for (pin in pins) {
                if (pin.descriptor.affinityAndId == pin_to_update.descriptor.affinityAndId) {
                    pin.connections = pin_to_update.connections
                    notifyItemChanged(counter)
                    Log.d(Tag, "Pin connectivity updated: $pin")
                    return
                }

                counter++
            }

            Log.e(
                Tag, """Pin ${pin_to_update.descriptor.affinityAndId.boardAddress}:
                      |${pin_to_update.descriptor.affinityAndId.pinID}
                      |is not found in stored pins list!""".trimMargin()
            )
        }

        fun updatePinSet(boards: MutableList<IoBoard>) {
            if (boards.isEmpty()) {
                val pinsCount = pins.size
                pins.clear()
                notifyItemRangeRemoved(0, pinsCount)

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
                    Log.e(Tag, "supplied board with id ${board.address} has empty list of pins")
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

        if (!model.setupViewModel()) {
            Log.e(Tag, "No arguments obtained in DeviceControlActivity onCreate method!")

            finish()
            return
        }

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
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
            actionBarText?.text =
                getString(R.string.ctl_actty_number_of_connected_boards).format(model.measurementsHandler.boardsManager.getBoardsCount())
            (measurementsView.adapter as ResultsAdapter).updatePinSet(it)
        }

        model.measurementsDirector.machineState.state.observe(this) { state ->
            when (state) {
                Director.State.Operating -> {
                    val controller_search_progress = findViewById<ProgressBar>(R.id.searching_for_controller_progbar)
                    actionBarText?.text = "Controlers found: ${model.measurementsDirector.controllers.size}"
                    if (controller_search_progress.visibility == View.VISIBLE) {
                        controller_search_progress.visibility = View.INVISIBLE
                    }
                }

                Director.State.SearchingForControllers -> {
                    val controller_search_progress = findViewById<ProgressBar>(R.id.searching_for_controller_progbar)
                    actionBarText?.text = "Connecting"
                    if (controller_search_progress.visibility == View.INVISIBLE) {
                        controller_search_progress.visibility = View.VISIBLE
                    }
                }

                Director.State.InitializingDirector -> {
                    actionBarText?.text = "Initializing"
                }

                Director.State.NoBoardsAvailable -> {
                    actionBarText?.text = "Controllers: ${model.measurementsDirector.controllers.size}, no boards found"
                    model.errorHandler.handleError("No boards found with connected controllers")
                }

                Director.State.RecoveryFromFailure -> {
                    actionBarText?.text = "Reconnecting to controller"
                    model.errorHandler.handleError("One of controllers disconnected!")
                }

                Director.State.UpdatingBoards -> {
                    actionBarText?.text = "Updating boards"
                }
            }
        }

        model.errorHandler.errorMessages.observe(this) { warnings ->
            warningSign.visibility = View.VISIBLE
        }

        warningSign.setOnClickListener() { sign ->
            AlertDialog.Builder(this)
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
                    val new_pinout_file_has_been_chosen =
                        answer.data?.getBooleanExtra(
                            PreferencesFragment.Companion.MessageToInvoker.NewPinoutConfigFileChosen.text,
                            false
                        )

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
//                    model.calibrate()
                    return@setOnMenuItemClickListener true
                }

                R.id.ctl_actty_menu_disconnect_button -> {
//                    model.disconnect()
                    return@setOnMenuItemClickListener true
                }

                R.id.ctl_actty_menu_refresh_button -> {
//                    model.refreshHardware()
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

        supportActionBar?.customView?.findViewById<Button>(R.id.button_at_custom_action_bar)
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
        model.setupMinimumResistance()
        model.setupVoltageLevel()
    }

    private fun toast(msg: String?) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG)
            .show()
    }
}